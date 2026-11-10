package com.example.satcp.servicio

import com.example.satcp.protocolo.DatosFoto
import com.example.satcp.transporte.CausaFallo

/** Por qué una foto clasificada llegó sin imagen (la clasificación sigue siendo válida). */
enum class AvisoImagen {
    /** El CRC-32 no coincidió: la imagen se descartó. */
    CRC_INVALIDO,

    /** El CRC coincidió pero no se pudo descifrar: la imagen está alterada y se descartó. */
    ALTERADA,

    /** Llegaron menos bytes de los anunciados en `imagen_inicio`. */
    INCOMPLETA,

    /** Llegó `imagen_error`: la transferencia se interrumpió. */
    INTERRUMPIDA,

    /** `imagen_error` con `sin_suscripcion`: las notificaciones de imagen no estaban activas. */
    SIN_SUSCRIPCION,

    /** La transferencia por Bluetooth no terminó a tiempo. */
    SIN_TERMINAR,

    /** `entrega: no_disponible`: el ESP32 no tuvo memoria para la copia. */
    NO_DISPONIBLE,

    /** El `GET` de la foto respondió `404`. */
    NO_ENCONTRADA,

    /** Falló la descarga por HTTP. */
    DESCARGA_FALLIDA,
}

sealed interface ResultadoFoto {
    /**
     * El ESP32 tomó la foto. [jpeg] es null si no se pudo obtener la imagen; [aviso] dice por qué, y
     * [causa] da el detalle de red cuando falló la descarga por HTTP.
     */
    class Tomada(
        val datos: DatosFoto,
        val jpeg: ByteArray?,
        val aviso: AvisoImagen?,
        val causa: CausaFallo? = null,
    ) : ResultadoFoto

    /** Respuesta con `ok: false`, p. ej. `camara_ocupada` (ya reintentada una vez) o `camara_no_disponible`. */
    data class Rechazada(val error: String?, val mensaje: String?) : ResultadoFoto
}
