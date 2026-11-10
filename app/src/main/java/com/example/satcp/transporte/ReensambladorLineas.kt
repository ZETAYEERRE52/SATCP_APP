package com.example.satcp.transporte

import java.io.ByteArrayOutputStream

/**
 * Une las notificaciones de TX hasta cada `\n` (§2): un mensaje puede llegar partido en varias
 * notificaciones y una notificación puede traer el final de un mensaje y el comienzo del siguiente.
 *
 * Acumula bytes y no texto, porque un carácter UTF-8 de varios bytes (una tilde) puede quedar
 * partido entre dos notificaciones.
 */
class ReensambladorLineas(private val maximoBytes: Int = 16 * 1024) {

    private val pendiente = ByteArrayOutputStream()

    /** Agrega un fragmento y devuelve los mensajes que quedaron completos, sin el `\n`. */
    fun agregar(fragmento: ByteArray): List<String> {
        val lineas = mutableListOf<String>()
        for (byte in fragmento) {
            if (byte == FIN_LINEA) {
                val linea = pendiente.toString(Charsets.UTF_8.name()).trim()
                pendiente.reset()
                if (linea.isNotEmpty()) lineas += linea
            } else {
                pendiente.write(byte.toInt())
                // Un mensaje que nunca termina sería basura del enlace: se descarta para no crecer sin límite.
                if (pendiente.size() > maximoBytes) pendiente.reset()
            }
        }
        return lineas
    }

    /** Descarta lo acumulado, p. ej. al reconectar. */
    fun reiniciar() = pendiente.reset()

    private companion object {
        const val FIN_LINEA = '\n'.code.toByte()
    }
}
