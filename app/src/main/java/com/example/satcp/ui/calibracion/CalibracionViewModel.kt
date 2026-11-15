package com.example.satcp.ui.calibracion

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.Registro
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.Estufa
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.TiempoEsperaAgotado
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Por qué no empezó la calibración. */
enum class FalloInicio { NO_DISPONIBLE, RECHAZADO, SIN_CONEXION, SIN_RESPUESTA }

data class UiCalibracion(
    val etapa: EtapaCalibracion = EtapaCalibracion.INICIO,
    /** `null` mientras no llega el primer `estado`. */
    val estufa: Estufa? = null,
    val fase: Fase = Fase.DESCONOCIDA,
    val fallo: FalloInicio? = null,
    /** El `mensaje` de la respuesta rechazada, tal como lo envía el firmware. */
    val mensajeFallo: String? = null,
) {
    /** `calibrar_estufa` solo se acepta en `MONITOREO`; así se evita casi siempre el `no_disponible`. */
    val puedeComenzar: Boolean get() = fase == Fase.MONITOREO && etapa != EtapaCalibracion.ENVIANDO
}

private data class Envio(val enviando: Boolean = false, val fallo: FalloInicio? = null, val mensaje: String? = null)

/**
 * Calibración con la estufa (firmware 2.1.0). Vive mientras viva la actividad, así que al salir de la
 * pantalla y volver se retoma; todo lo demás sale del bloque `estufa` de cada `estado`.
 */
class CalibracionViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val cliente = (aplicacion as SatcpApp).cliente
    private val seguimiento = MutableStateFlow(Seguimiento.NINGUNO)
    private val envio = MutableStateFlow(Envio())
    private var aceptadaEnMs = 0L
    private var consulta: Job? = null

    init {
        viewModelScope.launch {
            cliente.estado.collect { estado -> seguimiento.update { avanzarSeguimiento(it, estado) } }
        }
    }

    val ui: StateFlow<UiCalibracion> =
        combine(seguimiento, envio, cliente.estado) { seg, env, estado ->
            UiCalibracion(
                etapa = etapaCalibracion(seg, estado, env.enviando),
                estufa = estado?.estufa,
                fase = estado?.fase ?: Fase.DESCONOCIDA,
                fallo = env.fallo,
                mensajeFallo = env.mensaje,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiCalibracion())

    /** Envía `calibrar_estufa`. También sirve para "Reintentar". */
    fun comenzar() {
        if (envio.value.enviando) return
        envio.value = Envio(enviando = true)
        seguimiento.value = Seguimiento.NINGUNO
        viewModelScope.launch {
            envio.value = try {
                val respuesta = cliente.enviar(Comando.CalibrarEstufa)
                Registro.info("calibrar_estufa: ok=${respuesta.ok} error=${respuesta.error} mensaje=${respuesta.mensaje}")
                when {
                    respuesta.ok -> {
                        aceptadaEnMs = SystemClock.elapsedRealtime()
                        // Si el `estado` con la calibración llegó antes que la respuesta, ya está EN_CURSO.
                        seguimiento.update { if (it == Seguimiento.NINGUNO) Seguimiento.ACEPTADA else it }
                        Envio()
                    }
                    respuesta.error == CodigosError.NO_DISPONIBLE -> Envio(fallo = FalloInicio.NO_DISPONIBLE, mensaje = respuesta.mensaje)
                    else -> Envio(fallo = FalloInicio.RECHAZADO, mensaje = respuesta.mensaje ?: respuesta.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TiempoEsperaAgotado) {
                Envio(fallo = FalloInicio.SIN_RESPUESTA)
            } catch (e: Exception) {
                Envio(fallo = FalloInicio.SIN_CONEXION)
            }
        }
    }

    /** Del resultado al inicio. */
    fun volverAlInicio() {
        seguimiento.value = Seguimiento.NINGUNO
        envio.value = Envio()
    }

    /** Al salir de la pantalla: un resultado ya visto no se vuelve a mostrar; una calibración en curso, sí. */
    fun alSalir() {
        seguimiento.update { if (it == Seguimiento.TERMINADA) Seguimiento.NINGUNO else it }
        envio.update { if (it.enviando) it else Envio() }
    }

    /**
     * Mientras la pantalla esté visible y haya calibración, pide `estado` cada segundo: el equipo solo
     * lo envía cada 5 s. Al ocultarse deja de pedirlo.
     */
    fun alMostrarse() {
        consulta?.cancel()
        consulta = viewModelScope.launch {
            while (true) {
                delay(INTERVALO_CONSULTA_MS)
                vencerAceptada()
                val estado = cliente.estado.value
                val activa = seguimiento.value == Seguimiento.ACEPTADA ||
                    seguimiento.value == Seguimiento.EN_CURSO ||
                    (estado != null && calibracionEnCurso(estado))
                if (!activa) continue
                try {
                    cliente.pedirEstado()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Sin conexión o sin respuesta: se reintenta en la siguiente vuelta.
                }
            }
        }
    }

    fun alOcultarse() {
        consulta?.cancel()
        consulta = null
    }

    /** El equipo aceptó pero nunca se vio la calibración en curso (terminó enseguida): se muestra el resultado. */
    private fun vencerAceptada() {
        if (seguimiento.value != Seguimiento.ACEPTADA) return
        if (SystemClock.elapsedRealtime() - aceptadaEnMs < ESPERA_INICIO_MS) return
        Registro.aviso("calibrar_estufa: no llegó ningún estado con la calibración en curso")
        seguimiento.update { if (it == Seguimiento.ACEPTADA) Seguimiento.TERMINADA else it }
    }

    companion object {
        const val INTERVALO_CONSULTA_MS = 1_000L
        const val ESPERA_INICIO_MS = 15_000L

        /** Duración de cada paso en el firmware, para las barras de progreso. */
        const val DURACION_REFERENCIA_S = 10
        const val DURACION_MAXIMA_MEDICION_S = 180
    }
}
