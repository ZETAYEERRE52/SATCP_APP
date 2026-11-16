package com.example.satcp.ui.bitacora

import android.content.Context
import com.example.satcp.R
import com.example.satcp.protocolo.EstadoValvula
import com.example.satcp.protocolo.ResultadoCalibracion
import com.example.satcp.servicio.DisparoAlerta
import com.example.satcp.servicio.EventoBitacora
import com.example.satcp.servicio.MotivoPerdida
import com.example.satcp.servicio.TipoEventoBitacora
import com.example.satcp.ui.comun.recursoFase
import com.example.satcp.ui.comun.recursoPresencia
import com.example.satcp.ui.comun.temperaturaTexto
import com.example.satcp.ui.comun.textoDisparo
import com.example.satcp.ui.comun.textoVisionAlerta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

fun fechaEvento(evento: EventoBitacora): String = FORMATO_FECHA.format(Instant.ofEpochMilli(evento.ms))

/** El título de una línea de la bitácora. */
fun tituloEvento(contexto: Context, evento: EventoBitacora): String {
    fun fase(f: com.example.satcp.protocolo.Fase?) = f?.let { contexto.getString(recursoFase(it)) } ?: contexto.getString(R.string.sin_valor)
    return when (evento.tipo) {
        TipoEventoBitacora.ESTADO_AL_CONECTAR -> contexto.getString(R.string.bitacora_al_conectar, fase(evento.fase))
        TipoEventoBitacora.CAMBIO_FASE -> contexto.getString(R.string.bitacora_cambio_fase, fase(evento.faseAnterior), fase(evento.fase))
        TipoEventoBitacora.TRANSICION_INESPERADA -> contexto.getString(
            R.string.bitacora_transicion_inesperada,
            evento.faseAnterior?.name ?: "?",
            evento.fase?.name ?: "?",
        )
        TipoEventoBitacora.ALERTA -> when (evento.disparo) {
            DisparoAlerta.MANUAL, DisparoAlerta.HORARIO, DisparoAlerta.REINICIO -> contexto.getString(R.string.bitacora_cierre_directo)
            else -> contexto.getString(R.string.bitacora_alerta)
        }
        TipoEventoBitacora.SUMINISTRO_CORTADO -> contexto.getString(R.string.bitacora_suministro_cortado)
        TipoEventoBitacora.ALERTA_TERMINADA -> contexto.getString(R.string.bitacora_alerta_terminada)
        TipoEventoBitacora.REARMADO -> contexto.getString(R.string.bitacora_rearmado)
        TipoEventoBitacora.VALVULA -> contexto.getString(
            if (evento.valvula == EstadoValvula.CERRADA) R.string.bitacora_valvula_cerrada else R.string.bitacora_valvula_abierta
        )
        TipoEventoBitacora.SENSOR_FALLA -> contexto.getString(R.string.bitacora_sensor_falla)
        TipoEventoBitacora.SENSOR_RECUPERADO -> contexto.getString(R.string.bitacora_sensor_recuperado)
        TipoEventoBitacora.REFERENCIA_NUEVA -> contexto.getString(R.string.bitacora_referencia, temperaturaTexto(contexto, evento.referencia))
        TipoEventoBitacora.REFERENCIA_DESCARTADA -> contexto.getString(R.string.bitacora_referencia_descartada)
        TipoEventoBitacora.UMBRAL_NUEVO -> contexto.getString(R.string.bitacora_umbral, temperaturaTexto(contexto, evento.umbralIncremento))
        TipoEventoBitacora.CALIBRACION_TERMINADA -> contexto.getString(
            R.string.bitacora_calibracion,
            contexto.getString(
                when (evento.resultadoCalibracion) {
                    ResultadoCalibracion.OK -> R.string.bitacora_calibracion_ok
                    ResultadoCalibracion.SIN_CALOR -> R.string.bitacora_calibracion_sin_calor
                    ResultadoCalibracion.CANCELADA -> R.string.bitacora_calibracion_cancelada
                    ResultadoCalibracion.ERROR_SENSOR -> R.string.bitacora_calibracion_error_sensor
                    null -> R.string.sin_valor
                }
            ),
        )
        TipoEventoBitacora.CONEXION_PERDIDA -> contexto.getString(R.string.bitacora_conexion_perdida) +
            (evento.motivoPerdida?.let { " " + contexto.getString(R.string.bitacora_motivo, textoMotivoPerdida(contexto, it)) } ?: "")
        TipoEventoBitacora.CONEXION_RECUPERADA -> contexto.getString(R.string.bitacora_conexion_recuperada)
        TipoEventoBitacora.DESCONECTADO -> contexto.getString(R.string.bitacora_desconectado)
    }
}

private fun textoMotivoPerdida(contexto: Context, motivo: String): String = when (motivo) {
    MotivoPerdida.SIN_EVENTOS.name -> contexto.getString(R.string.bitacora_motivo_sin_eventos)
    MotivoPerdida.CERRADA.name -> contexto.getString(R.string.bitacora_motivo_cerrada)
    MotivoPerdida.INTERRUMPIDA.name -> contexto.getString(R.string.bitacora_motivo_interrumpida)
    MotivoPerdida.NO_ENCONTRADO.name -> contexto.getString(R.string.bitacora_motivo_no_encontrado)
    MotivoPerdida.SESION_PERDIDA.name -> contexto.getString(R.string.bitacora_motivo_sesion)
    else -> motivo
}

/** El detalle: causa de la alerta y lecturas del momento. `null` en los eventos de conexión. */
fun detalleEvento(contexto: Context, evento: EventoBitacora): String? {
    if (evento.fase == null) return null
    val partes = mutableListOf<String>()
    val disparo = evento.disparo
    if (disparo != null && (evento.tipo == TipoEventoBitacora.ALERTA || evento.tipo == TipoEventoBitacora.SUMINISTRO_CORTADO)) {
        partes += textoDisparo(contexto, disparo, evento.temperaturaAlerta, evento.umbralRevision, evento.referencia)
        textoVisionAlerta(contexto, evento.vision, disparo)?.let { partes += it }
    }
    if (evento.tipo == TipoEventoBitacora.TRANSICION_INESPERADA) {
        partes += contexto.getString(R.string.bitacora_transicion_explicacion)
    }
    partes += contexto.getString(
        R.string.bitacora_lecturas,
        temperaturaTexto(contexto, evento.medida),
        temperaturaTexto(contexto, evento.estimada),
        temperaturaTexto(contexto, evento.referencia),
        temperaturaTexto(contexto, evento.umbralRevision),
        evento.presencia?.let { contexto.getString(recursoPresencia(it)) } ?: contexto.getString(R.string.sin_valor),
    )
    if (evento.sensorOk == false) partes += contexto.getString(R.string.bitacora_sin_sensor)
    return partes.joinToString(" ")
}

/** La bitácora como texto, para compartirla (de más nuevo a más viejo). */
fun bitacoraComoTexto(contexto: Context, eventos: List<EventoBitacora>): String = buildString {
    appendLine(contexto.getString(R.string.bitacora_encabezado_compartir))
    appendLine()
    eventos.forEach { evento ->
        append(fechaEvento(evento)).append("  ")
        if (evento.destacado) append("[!] ")
        appendLine(tituloEvento(contexto, evento))
        detalleEvento(contexto, evento)?.let { append("    ").appendLine(it) }
    }
}
