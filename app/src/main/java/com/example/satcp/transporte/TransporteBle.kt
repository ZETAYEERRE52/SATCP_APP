package com.example.satcp.transporte

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

class BluetoothApagado : IOException("El Bluetooth está apagado")

class ConexionBleFallida(detalle: String) : IOException("Bluetooth: $detalle")

/**
 * Conexión GATT con el servicio UART de Nordic (§2): comandos por RX, respuestas y eventos por TX,
 * y bytes de la foto por la característica de imagen.
 *
 * Los permisos de Bluetooth los pide la pantalla de conexión antes de crear este transporte.
 */
@SuppressLint("MissingPermission")
class TransporteBle(
    contexto: Context,
    val direccion: String,
    val nombre: String?,
) : Transporte {

    override val tipo = TipoTransporte.BLE

    private val contexto = contexto.applicationContext
    private val adaptador = this.contexto.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var cola: ColaGatt? = null
    @Volatile private var mtu = MTU_POR_DEFECTO

    /** Impide que los fragmentos de dos mensajes se intercalen en RX. */
    private val turnoEnvio = Mutex()

    override fun conectar(): Flow<EventoTransporte> = callbackFlow {
        val colaSesion = ColaGatt()
        val reensamblador = ReensambladorLineas()
        val enlazado = CompletableDeferred<Unit>()
        cola = colaSesion
        mtu = MTU_POR_DEFECTO

        fun procesarNotificacion(caracteristica: BluetoothGattCharacteristic, valor: ByteArray) {
            when (caracteristica.uuid) {
                UuidsSatcp.TX -> reensamblador.agregar(valor).forEach { trySend(EventoTransporte.Texto(it)) }
                UuidsSatcp.DATOS_IMAGEN -> trySend(EventoTransporte.BytesImagen(valor.copyOf()))
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        enlazado.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        colaSesion.cancelarTodo()
                        val error = ConexionBleFallida("desconectado (estado $status)")
                        enlazado.completeExceptionally(error)
                        close(error)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) this@TransporteBle.mtu = mtu
                colaSesion.completar(status)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = colaSesion.completar(status)

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
                colaSesion.completar(status)

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) = colaSesion.completar(status)

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) = procesarNotificacion(characteristic, value)

            @Deprecated("Solo la usa Android 12 o anterior")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                // Desde Android 13 llega la versión con `value`; esta queda para las anteriores.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    procesarNotificacion(characteristic, characteristic.value ?: return)
                }
            }
        }

        try {
            val dispositivo = adaptador?.takeIf { it.isEnabled }?.getRemoteDevice(direccion) ?: throw BluetoothApagado()
            val conexion = dispositivo.connectGatt(contexto, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw ConexionBleFallida("no se pudo iniciar la conexión")
            gatt = conexion

            try {
                withTimeout(TIEMPO_CONEXION_MS) { prepararConexion(conexion, colaSesion, enlazado) }
            } catch (e: TimeoutCancellationException) {
                throw ConexionBleFallida("el equipo no respondió")
            }
            send(EventoTransporte.Abierto)
            awaitClose()
        } finally {
            rx = null
            cola = null
            gatt?.let {
                it.disconnect()
                it.close()
            }
            gatt = null
            colaSesion.cancelarTodo()
        }
    }.buffer(Channel.UNLIMITED)

    /** Pasos de §2: MTU 517, descubrir servicios y activar las notificaciones. */
    private suspend fun prepararConexion(conexion: BluetoothGatt, colaSesion: ColaGatt, enlazado: CompletableDeferred<Unit>) {
        enlazado.await()

        try {
            colaSesion.ejecutar("MTU") { conexion.requestMtu(MTU_PEDIDO) }
        } catch (e: OperacionGattFallida) {
            // Se sigue con 20 bytes por notificación: funciona, solo que las fotos tardan más.
            Log.w(ETIQUETA, "No se pudo subir el MTU", e)
        }

        colaSesion.ejecutar("descubrir servicios") { conexion.discoverServices() }
        val uart = conexion.getService(UuidsSatcp.SERVICIO_UART)
            ?: throw ConexionBleFallida("el equipo no tiene el servicio UART")
        val tx = uart.getCharacteristic(UuidsSatcp.TX) ?: throw ConexionBleFallida("falta la característica TX")
        val caracteristicaRx = uart.getCharacteristic(UuidsSatcp.RX)
            ?: throw ConexionBleFallida("falta la característica RX")

        // Primero la imagen: al activar TX el ESP32 envía enseguida un evento estado.
        conexion.getService(UuidsSatcp.SERVICIO_IMAGEN)?.getCharacteristic(UuidsSatcp.DATOS_IMAGEN)?.let {
            activarNotificaciones(conexion, colaSesion, it)
        }
        rx = caracteristicaRx
        activarNotificaciones(conexion, colaSesion, tx)
    }

    @Suppress("DEPRECATION")
    private suspend fun activarNotificaciones(
        conexion: BluetoothGatt,
        colaSesion: ColaGatt,
        caracteristica: BluetoothGattCharacteristic,
    ) {
        if (!conexion.setCharacteristicNotification(caracteristica, true)) {
            throw ConexionBleFallida("no se pudieron activar las notificaciones")
        }
        val cccd = caracteristica.getDescriptor(UuidsSatcp.CCCD) ?: throw ConexionBleFallida("falta el descriptor CCCD")
        val valor = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        colaSesion.ejecutar("activar notificaciones") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                conexion.writeDescriptor(cccd, valor) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = valor
                conexion.writeDescriptor(cccd)
            }
        }
    }

    /** Envía el comando terminado en `\n`, partido en escrituras de MTU − 3 bytes si hace falta. */
    @Suppress("DEPRECATION")
    override suspend fun enviar(texto: String) {
        val bytes = (texto + "\n").toByteArray(Charsets.UTF_8)
        turnoEnvio.withLock {
            val conexion = gatt ?: throw SinConexion()
            val caracteristica = rx ?: throw SinConexion()
            val colaSesion = cola ?: throw SinConexion()
            val tamanio = (mtu - 3).coerceIn(MTU_POR_DEFECTO - 3, MAXIMO_POR_ESCRITURA)
            var desde = 0
            while (desde < bytes.size) {
                val fragmento = bytes.copyOfRange(desde, minOf(desde + tamanio, bytes.size))
                colaSesion.ejecutar("escribir RX") {
                    val tipo = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        conexion.writeCharacteristic(caracteristica, fragmento, tipo) == BluetoothStatusCodes.SUCCESS
                    } else {
                        caracteristica.writeType = tipo
                        caracteristica.value = fragmento
                        conexion.writeCharacteristic(caracteristica)
                    }
                }
                desde += fragmento.size
            }
        }
    }

    private companion object {
        const val MTU_POR_DEFECTO = 23
        const val MTU_PEDIDO = 517
        const val MAXIMO_POR_ESCRITURA = 512
        const val TIEMPO_CONEXION_MS = 20_000L
        const val ETIQUETA = "TransporteBle"
    }
}
