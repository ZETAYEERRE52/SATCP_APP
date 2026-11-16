package com.example.satcp.ui.conexion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.SatcpApp
import com.example.satcp.servicio.UltimaConexion
import com.example.satcp.transporte.BluetoothApagado
import com.example.satcp.transporte.DispositivoBle
import com.example.satcp.transporte.ServicioSatcp
import com.example.satcp.transporte.esIpv4Valida
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ModoConexion { RED_PROPIA, RED_CASA, BLUETOOTH }

enum class ErrorEscaneo { APAGADO, FALLO }

data class UiConexion(
    val modo: ModoConexion = ModoConexion.RED_PROPIA,
    val ipManual: String = "",
    val ipInvalida: Boolean = false,
    val equipos: List<ServicioSatcp> = emptyList(),
    val errorBusqueda: Boolean = false,
    val dispositivosBle: List<DispositivoBle> = emptyList(),
    val errorEscaneo: ErrorEscaneo? = null,
    val ultima: UltimaConexion? = null,
)

class ConexionViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val app = aplicacion as SatcpApp

    private val _ui = MutableStateFlow(estadoInicial())
    val ui: StateFlow<UiConexion> = _ui.asStateFlow()

    private var busqueda: Job? = null
    private var escaneo: Job? = null

    val hayBluetooth: Boolean get() = app.escaner.hayBluetooth
    val bluetoothEncendido: Boolean get() = app.escaner.encendido

    private fun estadoInicial(): UiConexion {
        val ultima = app.preferencias.ultimaConexion
        return UiConexion(
            modo = when (ultima) {
                is UltimaConexion.RedCasa -> ModoConexion.RED_CASA
                UltimaConexion.RedPropia -> ModoConexion.RED_PROPIA
                // La primera configuración se hace por Bluetooth.
                is UltimaConexion.Bluetooth, null -> ModoConexion.BLUETOOTH
            },
            ipManual = (ultima as? UltimaConexion.RedCasa)?.ip.orEmpty(),
            ultima = ultima,
        )
    }

    fun elegirModo(modo: ModoConexion) = _ui.update { it.copy(modo = modo) }

    fun cambiarIp(ip: String) = _ui.update { it.copy(ipManual = ip.trim(), ipInvalida = false) }

    /** La pantalla la inicia mientras se muestra la opción de la red de la casa. */
    fun iniciarBusqueda() {
        if (busqueda?.isActive == true) return
        _ui.update { it.copy(errorBusqueda = false) }
        busqueda = viewModelScope.launch {
            app.descubridor.descubrir()
                .catch { _ui.update { it.copy(errorBusqueda = true) } }
                .collect { equipos -> _ui.update { it.copy(equipos = equipos) } }
        }
    }

    fun detenerBusqueda() {
        busqueda?.cancel()
        busqueda = null
    }

    /** La pantalla lo inicia con los permisos concedidos y el Bluetooth encendido. */
    fun iniciarEscaneo() {
        if (escaneo?.isActive == true) return
        _ui.update { it.copy(errorEscaneo = null, dispositivosBle = emptyList()) }
        escaneo = viewModelScope.launch {
            app.escaner.escanear()
                .catch { error ->
                    val tipo = if (error is BluetoothApagado) ErrorEscaneo.APAGADO else ErrorEscaneo.FALLO
                    _ui.update { it.copy(errorEscaneo = tipo) }
                }
                .collect { lista -> _ui.update { it.copy(dispositivosBle = lista.sortedByDescending(DispositivoBle::rssi)) } }
        }
    }

    fun detenerEscaneo() {
        escaneo?.cancel()
        escaneo = null
    }

    fun conectarBluetooth(dispositivo: DispositivoBle) =
        conectarCon(UltimaConexion.Bluetooth(dispositivo.direccion, dispositivo.nombre))

    fun conectarRedPropia() = conectarCon(UltimaConexion.RedPropia)

    fun conectarEquipo(equipo: ServicioSatcp) = conectarCon(UltimaConexion.RedCasa(equipo.ip, equipo.puerto))

    fun conectarIpManual() {
        val ip = _ui.value.ipManual
        if (!esIpv4Valida(ip)) {
            _ui.update { it.copy(ipInvalida = true) }
            return
        }
        conectarCon(UltimaConexion.RedCasa(ip, PUERTO_HTTP))
    }

    fun reconectarUltima() {
        _ui.value.ultima?.let(::conectarCon)
    }

    /**
     * Un solo transporte a la vez: Wi-Fi y Bluetooth comparten la antena del ESP32, y usar los dos
     * degrada mucho el Wi-Fi. [SatcpApp.conectarCon] cierra el transporte anterior (desconecta el
     * GATT); aquí además se detiene la búsqueda del otro.
     */
    private fun conectarCon(modo: UltimaConexion) {
        detenerEscaneo()
        detenerBusqueda()
        _ui.update { it.copy(ultima = modo) }
        app.conectarCon(modo)
    }

    private companion object {
        const val PUERTO_HTTP = 80
    }
}
