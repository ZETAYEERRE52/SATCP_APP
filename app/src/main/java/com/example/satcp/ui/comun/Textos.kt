package com.example.satcp.ui.comun

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.satcp.R
import com.example.satcp.protocolo.Alerta
import com.example.satcp.protocolo.EstadoPresencia
import com.example.satcp.protocolo.EstadoValvula
import com.example.satcp.protocolo.OrigenAlerta
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.Vision
import com.example.satcp.servicio.EstadoConexion
import com.example.satcp.servicio.MotivoPerdida
import com.example.satcp.transporte.CausaFallo
import com.example.satcp.transporte.TipoFallo
import com.example.satcp.ui.panel.DescripcionTransporte

// Los textos devuelven ids de recursos para que también los use el servicio en sus notificaciones.

@StringRes
fun recursoFase(fase: Fase): Int = when (fase) {
    Fase.MONITOREO -> R.string.fase_monitoreo
    Fase.VALIDAR_PRESENCIA -> R.string.fase_validar_presencia
    Fase.DETECCION_VISUAL -> R.string.fase_deteccion_visual
    Fase.ALERTA_USUARIO -> R.string.fase_alerta_usuario
    Fase.CIERRE_AUTOMATICO -> R.string.fase_cierre_automatico
    Fase.BLOQUEADO -> R.string.fase_bloqueado
    Fase.AUTOCALIBRACION -> R.string.fase_autocalibracion
    Fase.DESCONOCIDA -> R.string.fase_desconocida
}

@StringRes
fun recursoPresencia(estado: EstadoPresencia): Int = when (estado) {
    EstadoPresencia.NADIE -> R.string.presencia_nadie
    EstadoPresencia.EN_MOVIMIENTO -> R.string.presencia_en_movimiento
    EstadoPresencia.QUIETA -> R.string.presencia_quieta
    EstadoPresencia.EN_MOVIMIENTO_Y_QUIETA -> R.string.presencia_en_movimiento_y_quieta
    EstadoPresencia.SIN_DATOS -> R.string.presencia_sin_datos
    EstadoPresencia.DESCONOCIDO -> R.string.presencia_desconocida
}

@StringRes
fun recursoVision(vision: Vision): Int = when (vision) {
    Vision.FUEGO -> R.string.vision_fuego
    Vision.HUMO -> R.string.vision_humo
    Vision.ERROR_DE_VISION -> R.string.vision_error
    Vision.SIN_RIESGO -> R.string.vision_sin_riesgo
    Vision.DESCONOCIDA -> R.string.vision_desconocida
}

/** Versión corta para la notificación permanente. */
@StringRes
fun recursoConexionCorto(estado: EstadoConexion): Int = when (estado) {
    EstadoConexion.Desconectado -> R.string.estado_desconectado
    is EstadoConexion.Conectando -> R.string.estado_conectando
    EstadoConexion.Conectado -> R.string.estado_conectado
    is EstadoConexion.Perdida -> R.string.estado_perdida_corto
    EstadoConexion.ClaveRechazada -> R.string.estado_clave_rechazada
}

@StringRes
fun recursoValvula(estado: EstadoValvula): Int = when (estado) {
    EstadoValvula.ABIERTA -> R.string.valvula_abierta
    EstadoValvula.CERRADA -> R.string.valvula_cerrada
    EstadoValvula.DESCONOCIDO -> R.string.valvula_desconocida
}

/** Por qué hay alerta o bloqueo. En los cierres manual y por horario no se menciona la visión. */
@Composable
fun textoMotivo(alerta: Alerta): String = when (alerta.origen) {
    OrigenAlerta.MANUAL -> stringResource(R.string.cierre_manual)
    OrigenAlerta.HORARIO -> stringResource(R.string.cierre_horario)
    OrigenAlerta.REINICIO -> stringResource(R.string.cierre_reinicio)
    else -> stringResource(R.string.panel_alerta, textoVision(alerta.vision))
}

@Composable
fun textoFase(fase: Fase): String = stringResource(recursoFase(fase))

@Composable
fun textoPresencia(estado: EstadoPresencia): String = stringResource(recursoPresencia(estado))

@Composable
fun textoVision(vision: Vision): String = stringResource(recursoVision(vision))

@Composable
fun textoTemperatura(grados: Double?): String =
    grados?.let { stringResource(R.string.temperatura_valor, it) } ?: stringResource(R.string.sin_valor)

/** La causa real de un fallo de foto o video, para no quedarse en "no se pudo". */
@Composable
fun textoCausa(causa: CausaFallo): String = when (causa.tipo) {
    TipoFallo.TOKEN_RECHAZADO -> stringResource(R.string.causa_token_rechazado)
    TipoFallo.OCUPADO -> stringResource(R.string.causa_ocupado)
    TipoFallo.NO_ENCONTRADO -> stringResource(R.string.causa_no_encontrado)
    TipoFallo.HTTP -> stringResource(R.string.causa_http, causa.codigoHttp ?: 0)
    TipoFallo.TIEMPO_AGOTADO -> stringResource(R.string.causa_tiempo_agotado)
    TipoFallo.SIN_RUTA -> stringResource(R.string.causa_sin_ruta)
    TipoFallo.CLEARTEXT -> stringResource(R.string.causa_cleartext)
    TipoFallo.OTRO -> stringResource(R.string.causa_otra)
}

@Composable
fun textoConexion(estado: EstadoConexion): String = when (estado) {
    EstadoConexion.Desconectado -> stringResource(R.string.estado_desconectado)
    is EstadoConexion.Conectando ->
        if (estado.intento == 0) stringResource(R.string.estado_conectando)
        else stringResource(R.string.estado_conectando_intento, estado.intento + 1)
    EstadoConexion.Conectado -> stringResource(R.string.estado_conectado)
    EstadoConexion.ClaveRechazada -> stringResource(R.string.estado_clave_rechazada)
    is EstadoConexion.Perdida -> when (estado.motivo) {
        MotivoPerdida.RED_NO_DISPONIBLE -> stringResource(R.string.estado_perdida_red_no_disponible)
        MotivoPerdida.SIN_ESPACIO -> stringResource(R.string.estado_perdida_sin_espacio)
        MotivoPerdida.FIRMWARE_INCOMPATIBLE -> stringResource(R.string.estado_perdida_firmware)
        MotivoPerdida.SESION_PERDIDA -> stringResource(R.string.estado_perdida_sesion)
        MotivoPerdida.SIN_EVENTOS -> stringResource(R.string.estado_perdida_sin_eventos)
        MotivoPerdida.CERRADA -> stringResource(R.string.estado_perdida_cerrada)
        MotivoPerdida.NO_ENCONTRADO -> stringResource(R.string.estado_perdida_no_encontrado)
        MotivoPerdida.INTERRUMPIDA -> stringResource(R.string.estado_perdida_interrumpida)
        MotivoPerdida.ERROR -> stringResource(R.string.estado_perdida_error, estado.detalle.orEmpty())
    }
}

@Composable
fun textoTransporte(transporte: DescripcionTransporte): String = when (transporte) {
    DescripcionTransporte.RedPropia -> stringResource(R.string.transporte_red_propia)
    is DescripcionTransporte.RedCasa -> stringResource(R.string.transporte_red_casa, transporte.ip)
    is DescripcionTransporte.Bluetooth -> stringResource(R.string.transporte_bluetooth, transporte.nombre)
}
