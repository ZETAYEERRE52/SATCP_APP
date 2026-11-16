package com.example.satcp.servicio

import com.example.satcp.protocolo.Alerta
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.OrigenAlerta
import kotlinx.serialization.Serializable

/**
 * Qué disparó una alerta, deducido de lo que envía el equipo. El protocolo no dice cuál de los
 * disparadores de temperatura actuó (salto brusco, superar la referencia, subida sostenida, falla del
 * sensor): la app lo deduce comparando `alerta.temperatura` con la referencia más el umbral.
 */
@Serializable
enum class DisparoAlerta {
    /** La temperatura llegó a la referencia más el umbral de estufa encendida. */
    SUPERO_REFERENCIA,

    /** Revisión por temperatura sin llegar a la referencia más el umbral: un salto brusco o una subida rápida. */
    SUBIDA_BRUSCA,

    /** Origen `temperatura` sin lectura: el sensor dejó de responder y se revisó con la cámara. */
    SENSOR_SIN_RESPUESTA,

    /** La cámara vio fuego o humo al verificar una temperatura ambiente estable, sin subida de temperatura. */
    VERIFICACION_AUTOMATICA,
    MANUAL,
    HORARIO,
    REINICIO,
    DESCONOCIDO,
}

fun disparoDe(alerta: Alerta, estado: DatosEstado?): DisparoAlerta = when (alerta.origen) {
    OrigenAlerta.AUTOCALIBRACION -> DisparoAlerta.VERIFICACION_AUTOMATICA
    OrigenAlerta.MANUAL -> DisparoAlerta.MANUAL
    OrigenAlerta.HORARIO -> DisparoAlerta.HORARIO
    OrigenAlerta.REINICIO -> DisparoAlerta.REINICIO
    OrigenAlerta.DESCONOCIDO -> DisparoAlerta.DESCONOCIDO
    OrigenAlerta.TEMPERATURA -> {
        val temperatura = alerta.temperatura
        val umbral = estado?.umbralRevision
        when {
            temperatura == null -> DisparoAlerta.SENSOR_SIN_RESPUESTA
            umbral != null && temperatura >= umbral -> DisparoAlerta.SUPERO_REFERENCIA
            else -> DisparoAlerta.SUBIDA_BRUSCA
        }
    }
}

/** Alertas que no vienen de una subida que superó la referencia: las que conviene revisar como posible falso positivo. */
val DisparoAlerta.esSospechoso: Boolean
    get() = this == DisparoAlerta.VERIFICACION_AUTOMATICA || this == DisparoAlerta.SUBIDA_BRUSCA ||
        this == DisparoAlerta.SENSOR_SIN_RESPUESTA
