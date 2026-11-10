package com.example.satcp.transporte

import com.example.satcp.protocolo.ImagenInicio
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Cómo terminó una transferencia de foto por Bluetooth (§6). */
sealed interface ResultadoImagen {
    val id: Long

    data class Completa(override val id: Long, val jpeg: ByteArray) : ResultadoImagen {
        override fun equals(other: Any?) = other is Completa && other.id == id && other.jpeg.contentEquals(jpeg)
        override fun hashCode() = 31 * id.hashCode() + jpeg.contentHashCode()
    }

    data class CrcInvalido(override val id: Long, val esperado: Long, val calculado: Long) : ResultadoImagen

    data class Incompleta(override val id: Long, val esperados: Int, val recibidos: Int) : ResultadoImagen

    /** Llegó `imagen_error`; [error] es p. ej. `sin_suscripcion`, o null si se interrumpió. */
    data class Interrumpida(override val id: Long, val error: String?) : ResultadoImagen
}

/**
 * Arma el JPEG con las notificaciones de la característica de imagen que llegan entre
 * `imagen_inicio` e `imagen_fin`, y verifica el CRC-32 anunciado.
 */
class ReceptorImagenBle {

    private var inicio: ImagenInicio? = null
    private val bytes = ByteArrayOutputStream()

    /** `true` entre `imagen_inicio` y `imagen_fin`/`imagen_error`. */
    val enCurso: Boolean get() = inicio != null

    val recibidos: Int get() = bytes.size()

    fun iniciar(evento: ImagenInicio) {
        inicio = evento
        bytes.reset()
    }

    /** Bytes de una notificación. Fuera de una transferencia se ignoran. */
    fun agregarFragmento(fragmento: ByteArray) {
        if (inicio == null) return
        bytes.write(fragmento)
    }

    /** Llegó `imagen_fin`. Devuelve null si el `id` no corresponde a la transferencia en curso. */
    fun finalizar(id: Long): ResultadoImagen? {
        val actual = inicio?.takeIf { it.id == id } ?: return null
        val jpeg = bytes.toByteArray()
        limpiar()
        if (jpeg.size != actual.bytes) return ResultadoImagen.Incompleta(id, actual.bytes, jpeg.size)
        val calculado = CRC32().apply { update(jpeg) }.value
        if (calculado != actual.crc32) return ResultadoImagen.CrcInvalido(id, actual.crc32, calculado)
        return ResultadoImagen.Completa(id, jpeg)
    }

    /**
     * Llegó `imagen_error`. Con `sin_suscripcion` no hubo `imagen_inicio`, así que se acepta
     * aunque no haya transferencia en curso.
     */
    fun interrumpir(id: Long, error: String?): ResultadoImagen {
        if (inicio?.id == id) limpiar()
        return ResultadoImagen.Interrumpida(id, error)
    }

    /** Se perdió la conexión: se abandona lo recibido. */
    fun limpiar() {
        inicio = null
        bytes.reset()
    }
}
