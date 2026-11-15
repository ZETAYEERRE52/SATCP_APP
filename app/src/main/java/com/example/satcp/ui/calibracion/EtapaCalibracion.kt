package com.example.satcp.ui.calibracion

import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.PasoCalibracion

/**
 * Lo que la pantalla sabe de la calibración más allá del bloque `estufa`: si este teléfono la pidió o
 * la vio en curso. Con eso distingue "terminó ahora" (mostrar el resultado) de "terminó hace rato".
 */
enum class Seguimiento {
    /** No hay calibración que seguir: se muestra el inicio. */
    NINGUNO,

    /** El equipo respondió `ok`, pero aún no llegó un `estado` con la calibración en curso. */
    ACEPTADA,

    /** Se vio la calibración en curso. */
    EN_CURSO,

    /** Se vio en curso y después terminó: se muestra `ultimo_resultado`. */
    TERMINADA,
}

enum class EtapaCalibracion { INICIO, ENVIANDO, PREPARANDO, REFERENCIA, MIDIENDO, RESULTADO }

/** La calibración está en marcha según el equipo. */
fun calibracionEnCurso(estado: DatosEstado): Boolean =
    estado.estufa.calibracion != null || estado.fase == Fase.CALIBRACION_ESTUFA

/**
 * Avanza el [actual] con un `estado` nuevo. Sin estado (conexión perdida) se conserva: al reconectar,
 * el primer `estado` dice si la calibración sigue o ya terminó.
 */
fun avanzarSeguimiento(actual: Seguimiento, estado: DatosEstado?): Seguimiento = when {
    estado == null -> actual
    calibracionEnCurso(estado) -> Seguimiento.EN_CURSO
    actual == Seguimiento.EN_CURSO -> Seguimiento.TERMINADA
    else -> actual
}

/** Qué mostrar. Lo que dice el equipo manda; el [seguimiento] solo decide entre inicio y resultado. */
fun etapaCalibracion(seguimiento: Seguimiento, estado: DatosEstado?, enviando: Boolean): EtapaCalibracion {
    val progreso = estado?.estufa?.calibracion
    return when {
        progreso?.paso == PasoCalibracion.MIDIENDO -> EtapaCalibracion.MIDIENDO
        progreso != null -> EtapaCalibracion.REFERENCIA
        estado?.fase == Fase.CALIBRACION_ESTUFA -> EtapaCalibracion.PREPARANDO
        seguimiento == Seguimiento.TERMINADA -> EtapaCalibracion.RESULTADO
        seguimiento == Seguimiento.EN_CURSO || seguimiento == Seguimiento.ACEPTADA -> EtapaCalibracion.PREPARANDO
        enviando -> EtapaCalibracion.ENVIANDO
        else -> EtapaCalibracion.INICIO
    }
}
