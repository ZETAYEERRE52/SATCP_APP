package com.example.satcp.ui.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.VERSION_PROTOCOLO
import com.example.satcp.servicio.EstadoConexion
import com.example.satcp.transporte.DestinoWifi
import com.example.satcp.transporte.Transporte
import com.example.satcp.transporte.TransporteBle
import com.example.satcp.transporte.TransporteWifi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Por dónde está conectada la app, para mostrarlo en pantalla. */
sealed interface DescripcionTransporte {
    data object RedPropia : DescripcionTransporte
    data class RedCasa(val ip: String) : DescripcionTransporte
    data class Bluetooth(val nombre: String) : DescripcionTransporte
}

data class UiPanel(
    val conexion: EstadoConexion = EstadoConexion.Desconectado,
    val transporte: DescripcionTransporte? = null,
    /** Último estado recibido. Se conserva al perder la conexión, marcado con [datosViejos]. */
    val estado: DatosEstado? = null,
    /** `protocolo` de `info`; si no es [VERSION_PROTOCOLO], la app y el firmware son incompatibles. */
    val protocoloEquipo: Int? = null,
) {
    val incompatible: Boolean get() = protocoloEquipo != null && protocoloEquipo != VERSION_PROTOCOLO

    val datosViejos: Boolean get() = estado != null && conexion != EstadoConexion.Conectado
}

class PanelViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val cliente = (aplicacion as SatcpApp).cliente

    val ui: StateFlow<UiPanel> = combine(
        cliente.estadoConexion,
        cliente.transporte,
        cliente.estado,
        cliente.info,
    ) { conexion, transporte, estado, info ->
        UiPanel(conexion, transporte?.let { describir(it, estado) }, estado, info?.protocolo)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiPanel())

    fun desconectar() = cliente.desconectar()

    private fun describir(transporte: Transporte, estado: DatosEstado?): DescripcionTransporte = when (transporte) {
        is TransporteWifi -> when (val destino = transporte.destino) {
            DestinoWifi.RedPropia -> DescripcionTransporte.RedPropia
            // La IP que informa el propio equipo está al día aunque el router se la haya cambiado.
            is DestinoWifi.RedCasa -> DescripcionTransporte.RedCasa(estado?.conexion?.wifiCasaIp ?: destino.ip)
        }
        is TransporteBle -> DescripcionTransporte.Bluetooth(transporte.nombre ?: transporte.direccion)
        else -> DescripcionTransporte.Bluetooth("?")
    }
}
