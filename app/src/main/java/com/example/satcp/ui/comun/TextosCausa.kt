package com.example.satcp.ui.comun

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.satcp.R
import com.example.satcp.protocolo.Alerta
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Vision
import com.example.satcp.servicio.DisparoAlerta
import com.example.satcp.servicio.disparoDe

// Textos con Context (no @Composable): los usan también las notificaciones y la bitácora compartida.

fun temperaturaTexto(contexto: Context, grados: Double?): String =
    grados?.let { contexto.getString(R.string.temperatura_valor, it) } ?: contexto.getString(R.string.sin_valor)

/** Qué disparó la alerta, con las temperaturas para comparar con el umbral. */
fun textoDisparo(
    contexto: Context,
    disparo: DisparoAlerta,
    temperatura: Double?,
    umbralRevision: Double?,
    referencia: Double?,
): String = when (disparo) {
    DisparoAlerta.SUPERO_REFERENCIA ->
        contexto.getString(R.string.disparo_supero, temperaturaTexto(contexto, temperatura), temperaturaTexto(contexto, umbralRevision))
    DisparoAlerta.SUBIDA_BRUSCA -> if (umbralRevision != null) {
        contexto.getString(R.string.disparo_subida_brusca, temperaturaTexto(contexto, temperatura), temperaturaTexto(contexto, umbralRevision))
    } else {
        contexto.getString(R.string.disparo_subida_brusca_sin_referencia, temperaturaTexto(contexto, temperatura))
    }
    DisparoAlerta.SENSOR_SIN_RESPUESTA -> contexto.getString(R.string.disparo_sensor)
    DisparoAlerta.VERIFICACION_AUTOMATICA -> contexto.getString(
        R.string.disparo_verificacion,
        temperaturaTexto(contexto, temperatura ?: referencia),
    )
    DisparoAlerta.MANUAL -> contexto.getString(R.string.cierre_manual)
    DisparoAlerta.HORARIO -> contexto.getString(R.string.cierre_horario)
    DisparoAlerta.REINICIO -> contexto.getString(R.string.cierre_reinicio)
    DisparoAlerta.DESCONOCIDO -> contexto.getString(R.string.disparo_desconocido)
}

/** Lo que vio la cámara; `null` en los cierres que no la consultan. */
fun textoVisionAlerta(contexto: Context, vision: Vision?, disparo: DisparoAlerta): String? = when {
    disparo == DisparoAlerta.MANUAL || disparo == DisparoAlerta.HORARIO || disparo == DisparoAlerta.REINICIO -> null
    vision == null -> null
    vision == Vision.ERROR_DE_VISION -> contexto.getString(R.string.vision_alerta_error)
    else -> contexto.getString(R.string.vision_alerta, contexto.getString(recursoVision(vision)))
}

/** "Causa: … La cámara vio: …", para la notificación, la pantalla de alerta y el panel. */
fun textoCausaAlerta(contexto: Context, alerta: Alerta, estado: DatosEstado?): String {
    val disparo = disparoDe(alerta, estado)
    val causa = textoDisparo(contexto, disparo, alerta.temperatura, estado?.umbralRevision, estado?.temperatura?.lineaBase)
    val vision = textoVisionAlerta(contexto, alerta.vision, disparo)
    return if (vision == null) causa else "$causa $vision"
}

@Composable
fun textoCausaAlerta(alerta: Alerta, estado: DatosEstado?): String = textoCausaAlerta(LocalContext.current, alerta, estado)
