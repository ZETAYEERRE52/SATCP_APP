package com.example.satcp.protocolo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lugares para horarios en el ESP32 (§4, `horarios`). */
const val MAXIMO_HORARIOS = 8

/** Un cierre programado. `hora` y `minuto` son hora local de Ciudad de México (UTC−6), formato 24 h. */
@Serializable
data class Horario(
    val indice: Int,
    val hora: Int,
    val minuto: Int,
    /** Máscara de [DiasSemana]. */
    val dias: Int,
    val activo: Boolean = true,
)

/** `datos` de la respuesta a `horarios`. */
@Serializable
data class DatosHorarios(
    /** Con `false` el ESP32 no tiene hora y los horarios no se ejecutan. */
    @SerialName("hora_valida") val horaValida: Boolean = false,
    val maximo: Int = MAXIMO_HORARIOS,
    /** Solo los lugares ocupados, ordenados por `indice`. */
    val horarios: List<Horario> = emptyList(),
)

/** `datos` de la respuesta a `horario_guardar`. */
@Serializable
data class DatosHorarioGuardado(
    val indice: Int? = null,
    @SerialName("hora_valida") val horaValida: Boolean? = null,
)

/** Máscara de días de `dias`: bit 0 = lunes, bit 1 = martes … bit 6 = domingo. */
object DiasSemana {
    const val CANTIDAD = 7
    const val TODOS = 127
    const val LUNES_A_VIERNES = 31
    const val FIN_DE_SEMANA = 96

    /** [dia] va de 0 (lunes) a 6 (domingo). */
    fun contiene(mascara: Int, dia: Int): Boolean = mascara and (1 shl dia) != 0

    fun alternar(mascara: Int, dia: Int): Int = mascara xor (1 shl dia)

    /** Días incluidos, de 0 (lunes) a 6 (domingo). */
    fun lista(mascara: Int): List<Int> = (0 until CANTIDAD).filter { contiene(mascara, it) }
}

/** Cómo mostrar una máscara de días. */
sealed interface ResumenDias {
    data object TodosLosDias : ResumenDias
    data object LunesAViernes : ResumenDias
    data object FinDeSemana : ResumenDias

    /** Cualquier otra combinación: los días, de 0 (lunes) a 6 (domingo). */
    data class Lista(val dias: List<Int>) : ResumenDias
}

fun resumirDias(mascara: Int): ResumenDias = when (mascara and DiasSemana.TODOS) {
    DiasSemana.TODOS -> ResumenDias.TodosLosDias
    DiasSemana.LUNES_A_VIERNES -> ResumenDias.LunesAViernes
    DiasSemana.FIN_DE_SEMANA -> ResumenDias.FinDeSemana
    else -> ResumenDias.Lista(DiasSemana.lista(mascara))
}
