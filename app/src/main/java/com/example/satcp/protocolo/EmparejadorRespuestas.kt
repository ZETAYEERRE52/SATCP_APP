package com.example.satcp.protocolo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** No llegó la respuesta a [cmd] dentro del tiempo de espera. */
class TiempoEsperaAgotado(val cmd: String, val milisegundos: Long) :
    Exception("Sin respuesta a '$cmd' en $milisegundos ms")

/** La conexión se cerró mientras se esperaba la respuesta. */
class ConexionCerrada : Exception("La conexión se cerró")

/**
 * Asigna a cada comando un `id` numérico creciente y empareja la respuesta que vuelve con el mismo `id`.
 */
class EmparejadorRespuestas {

    private val siguienteId = AtomicLong(1)
    private val pendientes = ConcurrentHashMap<Long, CompletableDeferred<Respuesta>>()

    val cantidadPendientes: Int get() = pendientes.size

    /**
     * Codifica [comando], lo pasa a [enviar] y espera su respuesta.
     * La espera se registra antes de enviar, para no perder una respuesta que llegue muy rápido.
     *
     * @throws TiempoEsperaAgotado si no llega a tiempo.
     * @throws ConexionCerrada si se llama a [cancelarTodos] mientras tanto.
     */
    suspend fun enviarYEsperar(
        comando: Comando,
        tiempoEsperaMs: Long,
        enviar: suspend (texto: String) -> Unit,
    ): Respuesta {
        val id = siguienteId.getAndIncrement()
        val texto = CodificadorProtocolo.codificar(comando, id)
        val espera = CompletableDeferred<Respuesta>()
        pendientes[id] = espera
        try {
            enviar(texto)
            return withTimeout(tiempoEsperaMs) { espera.await() }
        } catch (e: TimeoutCancellationException) {
            throw TiempoEsperaAgotado(comando.nombre, tiempoEsperaMs)
        } finally {
            pendientes.remove(id)
        }
    }

    /** Entrega una respuesta a quien la espera. Devuelve `false` si nadie esperaba ese `id`. */
    fun entregar(respuesta: Respuesta): Boolean {
        val id = respuesta.id ?: return false
        return pendientes.remove(id)?.complete(respuesta) ?: false
    }

    /** Despierta con [ConexionCerrada] a todos los que esperan. Se llama al perder la conexión. */
    fun cancelarTodos() {
        val esperas = pendientes.values.toList()
        pendientes.clear()
        esperas.forEach { it.completeExceptionally(ConexionCerrada()) }
    }
}
