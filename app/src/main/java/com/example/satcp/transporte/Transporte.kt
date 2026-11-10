package com.example.satcp.transporte

import kotlinx.coroutines.flow.Flow

enum class TipoTransporte { WIFI, BLE }

/** Lo que entrega un transporte mientras la conexión está abierta. */
sealed interface EventoTransporte {
    /** La conexión quedó lista para enviar comandos. */
    data object Abierto : EventoTransporte

    /** Un mensaje JSON completo: una trama de WebSocket o una línea de TX ya reensamblada. */
    data class Texto(val texto: String) : EventoTransporte

    /** Bytes de una notificación de la característica de imagen (solo Bluetooth). */
    class BytesImagen(val bytes: ByteArray) : EventoTransporte
}

/** Se intentó enviar sin una conexión abierta. */
class SinConexion : Exception("No hay conexión con el SATCP")

/**
 * Un camino hacia el ESP32. Las dos implementaciones llevan exactamente los mismos mensajes,
 * así que la capa de protocolo no sabe cuál está usando.
 */
interface Transporte {
    val tipo: TipoTransporte

    /**
     * Abre la conexión y emite lo que llega hasta que se cierra. El flujo termina normalmente si el
     * ESP32 cierra la conexión y con una excepción si falla. Dejar de recolectarlo cierra la conexión.
     */
    fun conectar(): Flow<EventoTransporte>

    /** Envía un comando ya codificado (sin `\n`; el transporte agrega lo que necesite). */
    suspend fun enviar(texto: String)

    /**
     * Descarga por HTTP la ruta indicada (p. ej. `/foto/ultima.jpg`). Devuelve null si responde `404`.
     * Solo existe por Wi-Fi.
     */
    suspend fun descargar(ruta: String): ByteArray? =
        throw UnsupportedOperationException("Este transporte no descarga por HTTP")

    /** Libera lo que el transporte mantiene entre reconexiones. Se llama al desconectar. */
    fun cerrar() = Unit
}

/** Espera antes del intento de reconexión número [intento] (0, 1, 2...): 1, 2, 4, 8, 16 y luego 30 s. */
fun esperaReconexionMs(intento: Int): Long = minOf(30_000L, 1_000L shl intento.coerceIn(0, 5))
