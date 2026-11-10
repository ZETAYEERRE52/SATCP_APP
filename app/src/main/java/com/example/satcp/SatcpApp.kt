package com.example.satcp

import android.app.Application
import com.example.satcp.seguridad.AlmacenClave
import com.example.satcp.servicio.AlmacenRedes
import com.example.satcp.servicio.ClienteSatcp
import com.example.satcp.servicio.Preferencias
import com.example.satcp.servicio.UltimaConexion
import com.example.satcp.transporte.DescubridorNsd
import com.example.satcp.transporte.DestinoWifi
import com.example.satcp.transporte.EscanerBle
import com.example.satcp.transporte.TransporteBle
import com.example.satcp.transporte.TransporteWifi
import com.example.satcp.transporte.esIpv4Valida
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Objetos que comparten las pantallas y el servicio durante toda la vida del proceso. */
class SatcpApp : Application() {

    val alcance = MainScope()
    val almacenClave by lazy { AlmacenClave(this) }
    /**
     * El cliente del protocolo corre en su propio hilo en serie, fuera del hilo principal: así el
     * WebSocket, el descifrado y el vigilante de 15 s no dependen de la pantalla ni del video.
     */
    val cliente by lazy {
        val hiloCliente = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1, "ClienteSatcp"))
        ClienteSatcp(hiloCliente, leerClave = { almacenClave.clave.value })
    }
    val preferencias by lazy { Preferencias(this) }
    val redes by lazy { AlmacenRedes(this) }
    val descubridor by lazy { DescubridorNsd(this) }
    val escaner by lazy { EscanerBle(this) }

    fun crearTransporteWifi(destino: DestinoWifi) = TransporteWifi(
        this,
        destino,
        buscarEquipo = if (destino is DestinoWifi.RedCasa) ::relocalizarEquipo else null,
        claveRed = almacenClave.clave.value,
    )

    /** Rehace la conexión actual con la clave guardada: suelta la red `SATCP` y vuelve a unirse. */
    fun reconectar() {
        val nuevo = when (val actual = cliente.transporte.value) {
            is TransporteWifi -> crearTransporteWifi(actual.destino)
            is TransporteBle -> TransporteBle(this, actual.direccion, actual.nombre)
            else -> return
        }
        cliente.conectar(nuevo)
    }

    /**
     * El equipo aceptó `cambiar_clave` (§4). Un segundo después cierra todas las sesiones y cambia la
     * contraseña de la red `SATCP`, así que se espera a que lo haga antes de reconectar.
     */
    fun aplicarClaveNueva(clave: String) {
        almacenClave.guardar(clave)
        alcance.launch {
            delay(ESPERA_CAMBIO_CLAVE_MS)
            reconectar()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Guarda la IP de la casa que informa el equipo, aunque la conexión sea por SATCP o Bluetooth.
        alcance.launch {
            cliente.estado
                .map { it?.conexion?.wifiCasaIp?.trim() }
                .filterNotNull()
                .filter(::esIpv4Valida)
                .distinctUntilChanged()
                .collect { preferencias.ipCasaInformada = it }
        }
    }

    /**
     * Busca de nuevo el equipo en la red de la casa, por si el router le cambió la IP. Si mDNS no lo
     * encuentra, prueba la última IP que el propio equipo informó. Un equipo ajeno en esa IP no sirve
     * de nada: sin la clave no abre la sesión cifrada.
     */
    suspend fun relocalizarEquipo(): DestinoWifi.RedCasa? {
        val destino = descubridor.buscarUnico()?.let { DestinoWifi.RedCasa(it.ip, it.puerto) }
            ?: preferencias.ipCasaInformada?.let { DestinoWifi.RedCasa(it) }
            ?: return null
        preferencias.ultimaConexion = UltimaConexion.RedCasa(destino.ip, destino.puerto)
        return destino
    }

    private companion object {
        const val ESPERA_CAMBIO_CLAVE_MS = 2_000L
    }
}
