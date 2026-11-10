package com.example.satcp.ui.configuracion

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.ConexionCerrada
import com.example.satcp.protocolo.DatosInfo
import com.example.satcp.protocolo.TiempoEsperaAgotado
import com.example.satcp.protocolo.VERSION_PROTOCOLO
import com.example.satcp.seguridad.Criptografia
import com.example.satcp.servicio.EstadoConexion
import com.example.satcp.servicio.RedGuardada
import com.example.satcp.transporte.SinConexion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ResultadoComando {
    LISTO,
    WIFI_ERROR,
    RECHAZADO,
    SIN_CONEXION,
    SIN_RESPUESTA,

    /** El equipo aceptó la red, pero en [ConfiguracionViewModel.ESPERA_IP_MS] no informó IP en la casa. */
    SIN_IP,
}

data class FormularioWifi(
    val ssid: String = "",
    val clave: String = "",
    val redAbierta: Boolean = false,
    val enviando: Boolean = false,
    val resultado: ResultadoComando? = null,
    /** El `mensaje` de la respuesta, tal como lo envía el firmware. */
    val mensaje: String? = null,
) {
    val ssidValido: Boolean get() = ssid.isNotEmpty() && ssid.length <= Comando.Wifi.MAX_SSID
    val claveValida: Boolean get() = redAbierta || (clave.isNotEmpty() && clave.length <= Comando.Wifi.MAX_CLAVE)
}

enum class ResultadoCambioClave { LISTA, RECHAZADA, SIN_CONEXION, SIN_RESPUESTA }

data class FormularioClave(
    val nueva: String = "",
    val repetida: String = "",
    val enviando: Boolean = false,
    val resultado: ResultadoCambioClave? = null,
) {
    val valida: Boolean get() = Criptografia.esClaveValida(nueva)
    val coinciden: Boolean get() = nueva == repetida

    // Sin datos de la clave en el texto del objeto, por si alguna vez se imprime.
    override fun toString() = "FormularioClave(enviando=$enviando, resultado=$resultado)"
}

data class UiConfiguracion(
    val wifi: FormularioWifi = FormularioWifi(),
    val cambioClave: FormularioClave = FormularioClave(),
    val info: DatosInfo? = null,
    val actualizandoInfo: Boolean = false,
    val errorInfo: ResultadoComando? = null,
    /** `conexion.wifi_casa_ip` del último evento `estado`. */
    val ipCasa: String? = null,
    val redesGuardadas: List<RedGuardada> = emptyList(),
) {
    val incompatible: Boolean get() = info?.protocolo != null && info.protocolo != VERSION_PROTOCOLO
}

class ConfiguracionViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val app = aplicacion as SatcpApp
    private val cliente = app.cliente
    private val almacenRedes = app.redes

    private val formulario = MutableStateFlow(FormularioWifi())
    private val formularioClave = MutableStateFlow(FormularioClave())
    private val estadoInfo = MutableStateFlow<Pair<Boolean, ResultadoComando?>>(false to null)

    val ui: StateFlow<UiConfiguracion> =
        combine(
            combine(formulario, formularioClave, ::Pair),
            cliente.info,
            cliente.estado,
            estadoInfo,
            almacenRedes.redes,
        ) { (wifi, clave), info, estado, (actualizando, error), redes ->
            UiConfiguracion(wifi, clave, info, actualizando, error, estado?.conexion?.wifiCasaIp, redes)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiConfiguracion())

    fun cambiarClaveNueva(texto: String) =
        formularioClave.update { it.copy(nueva = texto.take(MAXIMO_CLAVE), resultado = null) }

    fun cambiarClaveRepetida(texto: String) =
        formularioClave.update { it.copy(repetida = texto.take(MAXIMO_CLAVE), resultado = null) }

    /** Envía `cambiar_clave`. Tras el `ok`, guarda la clave y la app reconecta sola (§4). */
    fun enviarCambioClave() {
        val actual = formularioClave.value
        if (actual.enviando || !actual.valida || !actual.coinciden) return
        formularioClave.update { it.copy(enviando = true, resultado = null) }
        viewModelScope.launch {
            val resultado = try {
                val respuesta = cliente.enviar(Comando.CambiarClave(actual.nueva))
                if (respuesta.ok) {
                    app.aplicarClaveNueva(actual.nueva)
                    ResultadoCambioClave.LISTA
                } else {
                    ResultadoCambioClave.RECHAZADA
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TiempoEsperaAgotado) {
                ResultadoCambioClave.SIN_RESPUESTA
            } catch (e: Exception) {
                ResultadoCambioClave.SIN_CONEXION
            }
            formularioClave.value = if (resultado == ResultadoCambioClave.LISTA) {
                FormularioClave(resultado = resultado)
            } else {
                formularioClave.value.copy(enviando = false, resultado = resultado)
            }
        }
    }

    fun cambiarSsid(ssid: String) = formulario.update { it.copy(ssid = ssid.take(Comando.Wifi.MAX_SSID), resultado = null) }

    fun cambiarClave(clave: String) = formulario.update { it.copy(clave = clave.take(Comando.Wifi.MAX_CLAVE), resultado = null) }

    fun cambiarRedAbierta(abierta: Boolean) = formulario.update { it.copy(redAbierta = abierta, resultado = null) }

    fun enviarWifi() {
        val actual = formulario.value
        if (!actual.ssidValido || !actual.claveValida) return
        enviarRed(RedGuardada(actual.ssid, if (actual.redAbierta) null else actual.clave))
    }

    /** Envía una red ya guardada. La clave no pasa al formulario, para que no se pueda ver con "Mostrar". */
    fun usarRedGuardada(red: RedGuardada) {
        if (formulario.value.enviando) return
        formulario.update { it.copy(ssid = red.ssid, clave = "", redAbierta = red.clave == null) }
        enviarRed(red)
    }

    fun borrarRedGuardada(red: RedGuardada) = almacenRedes.borrar(red.ssid)

    private fun enviarRed(red: RedGuardada) {
        if (formulario.value.enviando) return
        formulario.update { it.copy(enviando = true, resultado = null, mensaje = null) }
        viewModelScope.launch {
            val (resultado, mensaje) = try {
                val respuesta = cliente.enviar(Comando.Wifi(red.ssid, red.clave))
                // Sin la clave: solo la red y lo que respondió el equipo.
                Log.i(ETIQUETA, "Comando wifi para \"${red.ssid}\": ok=${respuesta.ok} error=${respuesta.error}")
                when {
                    respuesta.ok -> {
                        almacenRedes.guardar(red)
                        ResultadoComando.LISTO
                    }
                    respuesta.error == CodigosError.WIFI_ERROR -> ResultadoComando.WIFI_ERROR
                    else -> ResultadoComando.RECHAZADO
                } to respuesta.mensaje
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallaDe(e) to null
            }
            formulario.update { it.copy(enviando = false, resultado = resultado, mensaje = mensaje) }
            if (resultado == ResultadoComando.LISTO) esperarIp(red.ssid)
        }
    }

    /**
     * El `ok` de `wifi` solo dice que el equipo guardó la red. Si en [ESPERA_IP_MS] no informa una IP
     * en `conexion.wifi_casa_ip`, se avisa: lo más común es una red de 5 GHz o un nombre mal escrito.
     * Los primeros segundos se ignoran, porque el equipo aún puede estar informando la IP de la red anterior.
     */
    private fun esperarIp(ssid: String) {
        esperaIp?.cancel()
        esperaIp = viewModelScope.launch {
            delay(GRACIA_IP_MS)
            val ip = withTimeoutOrNull(ESPERA_IP_MS - GRACIA_IP_MS) {
                cliente.estado.first { !it?.conexion?.wifiCasaIp.isNullOrBlank() }
            }
            when {
                ip != null -> Log.i(ETIQUETA, "El equipo obtuvo IP en \"$ssid\"")
                // Sin conexión con el equipo no se sabe qué pasó: no se culpa a la red.
                cliente.estadoConexion.value != EstadoConexion.Conectado -> Unit
                formulario.value.resultado == ResultadoComando.LISTO -> {
                    Log.w(ETIQUETA, "El equipo no obtuvo IP en \"$ssid\" tras ${ESPERA_IP_MS / 1_000} s")
                    formulario.update { it.copy(resultado = ResultadoComando.SIN_IP) }
                }
            }
        }
    }

    fun actualizarInfo() {
        if (estadoInfo.value.first) return
        estadoInfo.value = true to null
        viewModelScope.launch {
            val error = try {
                if (cliente.actualizarInfo() == null) ResultadoComando.RECHAZADO else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallaDe(e)
            }
            estadoInfo.value = false to error
        }
    }

    private fun fallaDe(e: Exception) = when (e) {
        is SinConexion, is ConexionCerrada -> ResultadoComando.SIN_CONEXION
        is TiempoEsperaAgotado -> ResultadoComando.SIN_RESPUESTA
        else -> ResultadoComando.RECHAZADO
    }

    private var esperaIp: Job? = null

    companion object {
        private const val MAXIMO_CLAVE = 63
        private const val ETIQUETA = "Configuracion"

        /** Cuánto se espera la IP tras el `ok` de `wifi`. El firmware hace 6 intentos de unos 5 s. */
        const val ESPERA_IP_MS = 30_000L
        private const val GRACIA_IP_MS = 2_000L
    }
}
