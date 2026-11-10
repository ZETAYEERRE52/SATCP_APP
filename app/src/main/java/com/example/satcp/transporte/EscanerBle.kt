package com.example.satcp.transporte

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Un equipo que anuncia el servicio UART. */
data class DispositivoBle(val direccion: String, val nombre: String?, val rssi: Int)

class EscaneoFallido(codigo: Int) : Exception("No se pudo buscar por Bluetooth (código $codigo)")

/** Busca equipos SATCP filtrando por el UUID del servicio UART, que viaja en el anuncio (§2). */
@SuppressLint("MissingPermission")
class EscanerBle(contexto: Context) {

    private val adaptador = contexto.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    val hayBluetooth: Boolean get() = adaptador != null
    val encendido: Boolean get() = adaptador?.isEnabled == true

    /** Emite la lista de equipos vistos cada vez que cambia. Al dejar de recolectar se detiene el escaneo. */
    fun escanear(): Flow<List<DispositivoBle>> = callbackFlow {
        val escaner = adaptador?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw BluetoothApagado()
        val vistos = LinkedHashMap<String, DispositivoBle>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dispositivo = DispositivoBle(
                    direccion = result.device.address,
                    nombre = result.scanRecord?.deviceName ?: result.device.name,
                    rssi = result.rssi,
                )
                val lista = synchronized(vistos) {
                    vistos[dispositivo.direccion] = dispositivo
                    vistos.values.toList()
                }
                trySend(lista)
            }

            override fun onScanFailed(errorCode: Int) {
                close(EscaneoFallido(errorCode))
            }
        }

        val filtro = ScanFilter.Builder().setServiceUuid(ParcelUuid(UuidsSatcp.SERVICIO_UART)).build()
        val ajustes = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        trySend(emptyList())
        escaner.startScan(listOf(filtro), ajustes, callback)

        awaitClose { runCatching { escaner.stopScan(callback) } }
    }
}
