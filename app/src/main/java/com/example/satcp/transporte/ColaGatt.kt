package com.example.satcp.transporte

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/** UUIDs de §2. */
object UuidsSatcp {
    val SERVICIO_UART: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val SERVICIO_IMAGEN: UUID = UUID.fromString("8D6F0001-3C4E-4B1A-9F5E-5A7C0A7E5A01")
    val DATOS_IMAGEN: UUID = UUID.fromString("8D6F0002-3C4E-4B1A-9F5E-5A7C0A7E5A01")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

/** Una operación GATT no se pudo iniciar, terminó con error o no respondió. */
class OperacionGattFallida(operacion: String, detalle: String) : IOException("GATT $operacion: $detalle")

/**
 * Android admite una sola operación GATT en curso: si se lanza otra antes de que llegue el callback
 * de la anterior, falla en silencio. Esta cola las ejecuta de a una (MTU, descubrimiento,
 * descriptores y escrituras) y espera el callback de cada una.
 */
class ColaGatt(private val tiempoMaximoMs: Long = 5_000) {

    private val turno = Mutex()
    @Volatile private var enCurso: CompletableDeferred<Int>? = null

    /**
     * Ejecuta [iniciar] (que llama a la API de BluetoothGatt y devuelve si la aceptó) y espera
     * a que el callback llame a [completar].
     *
     * @throws OperacionGattFallida si no se inicia, si el estado no es éxito o si no hay respuesta.
     */
    suspend fun ejecutar(operacion: String, iniciar: () -> Boolean) {
        turno.withLock {
            val espera = CompletableDeferred<Int>()
            enCurso = espera
            try {
                if (!iniciar()) throw OperacionGattFallida(operacion, "Android no la aceptó")
                val estado = withTimeout(tiempoMaximoMs) { espera.await() }
                if (estado != GATT_EXITO) throw OperacionGattFallida(operacion, "estado $estado")
            } catch (e: TimeoutCancellationException) {
                // Se convierte para que no se confunda con una cancelación de quien llama.
                throw OperacionGattFallida(operacion, "sin respuesta en $tiempoMaximoMs ms")
            } finally {
                enCurso = null
            }
        }
    }

    /** Lo llaman los callbacks de BluetoothGattCallback con su `status`. */
    fun completar(estado: Int) {
        enCurso?.complete(estado)
    }

    /** Se perdió la conexión: la operación en curso falla en vez de esperar su tiempo máximo. */
    fun cancelarTodo() {
        enCurso?.completeExceptionally(OperacionGattFallida("en curso", "se perdió la conexión"))
    }

    private companion object {
        /** BluetoothGatt.GATT_SUCCESS; se repite aquí para probar la cola sin Android. */
        const val GATT_EXITO = 0
    }
}
