package com.example.satcp.ui.camara

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.protocolo.DatosFoto
import com.example.satcp.servicio.AvisoImagen
import com.example.satcp.ui.comun.textoCausa

@Composable
fun PantallaCamara(modifier: Modifier = Modifier, vm: CamaraViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BotonTomarFoto(ui, vm::tomarFoto)
        ui.error?.let { Text(stringResource(textoError(it)), color = MaterialTheme.colorScheme.error) }

        if (ui.datos != null || ui.imagen != null) {
            ImagenFoto(ui, vm::tomarFoto)
            ui.datos?.let { ResultadoClasificacion(it, ui.clases) }
        }
    }
}

@Composable
fun BotonTomarFoto(ui: UiCamara, tomar: () -> Unit, @StringRes texto: Int = R.string.camara_tomar_foto) {
    Button(onClick = tomar, enabled = !ui.tomando, modifier = Modifier.fillMaxWidth()) {
        if (ui.tomando) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.camara_tomando))
        } else {
            Text(stringResource(texto))
        }
    }
    if (ui.tomando && ui.porBluetooth) {
        Text(stringResource(R.string.camara_bluetooth_lento), style = MaterialTheme.typography.bodySmall)
    }
}

/** La imagen, o el motivo por el que no llegó, con "Reintentar" si es un problema de transferencia. */
@Composable
fun ImagenFoto(ui: UiCamara, reintentar: () -> Unit) {
    val imagen = ui.imagen
    if (imagen != null) {
        Image(
            bitmap = imagen,
            contentDescription = stringResource(R.string.camara_descripcion_imagen),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(imagen.width.toFloat() / imagen.height)
                .clip(RoundedCornerShape(12.dp)),
        )
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val texto = when {
                ui.imagenIlegible -> R.string.aviso_imagen_ilegible
                ui.avisoImagen != null -> textoAviso(ui.avisoImagen)
                else -> R.string.aviso_imagen_no_solicitada
            }
            Text(stringResource(texto), textAlign = TextAlign.Center)
            ui.causaDescarga?.let {
                Text(textoCausa(it), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            if (ui.sePuedeReintentar) {
                OutlinedButton(onClick = reintentar, enabled = !ui.tomando) { Text(stringResource(R.string.camara_reintentar)) }
            }
        }
    }
}

@Composable
private fun ResultadoClasificacion(datos: DatosFoto, clases: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (datos.clasificada && datos.clase != null) {
                Text(stringResource(R.string.camara_clase), style = MaterialTheme.typography.labelLarge)
                Text(datos.clase, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            } else {
                Text(stringResource(R.string.camara_sin_clasificar), color = MaterialTheme.colorScheme.error)
            }

            datos.probabilidades?.let { probabilidades ->
                clases.forEach { clase ->
                    val p = probabilidades[clase] ?: return@forEach
                    BarraProbabilidad(clase, p.toFloat(), destacada = clase == datos.clase)
                }
            }

            datos.tiempoMs?.let { Text(stringResource(R.string.camara_tiempo_inferencia, it)) }
            Text(
                if (datos.guardada) stringResource(R.string.camara_guardada, datos.rutaSd.orEmpty())
                else stringResource(R.string.camara_no_guardada)
            )
        }
    }
}

@Composable
private fun BarraProbabilidad(clase: String, probabilidad: Float, destacada: Boolean) {
    val color = when (clase) {
        "fuego", "humo" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clase, fontWeight = if (destacada) FontWeight.Bold else FontWeight.Normal)
            Text(stringResource(R.string.camara_porcentaje, probabilidad * 100))
        }
        LinearProgressIndicator(
            progress = { probabilidad.coerceIn(0f, 1f) },
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
    }
}

private fun textoError(error: ErrorFoto) = when (error) {
    ErrorFoto.CAMARA_OCUPADA -> R.string.foto_error_camara_ocupada
    ErrorFoto.CAMARA_NO_DISPONIBLE -> R.string.foto_error_camara_no_disponible
    ErrorFoto.RECHAZADA -> R.string.foto_error_rechazada
    ErrorFoto.SIN_CONEXION -> R.string.resultado_sin_conexion
    ErrorFoto.SIN_RESPUESTA -> R.string.resultado_sin_respuesta
    ErrorFoto.ERROR -> R.string.foto_error_rechazada
}

private fun textoAviso(aviso: AvisoImagen) = when (aviso) {
    AvisoImagen.CRC_INVALIDO -> R.string.aviso_imagen_crc
    AvisoImagen.ALTERADA -> R.string.aviso_imagen_alterada
    AvisoImagen.INCOMPLETA -> R.string.aviso_imagen_incompleta
    AvisoImagen.INTERRUMPIDA -> R.string.aviso_imagen_interrumpida
    AvisoImagen.SIN_SUSCRIPCION -> R.string.aviso_imagen_sin_suscripcion
    AvisoImagen.SIN_TERMINAR -> R.string.aviso_imagen_sin_terminar
    AvisoImagen.NO_DISPONIBLE -> R.string.aviso_imagen_no_disponible
    AvisoImagen.NO_ENCONTRADA -> R.string.aviso_imagen_no_encontrada
    AvisoImagen.DESCARGA_FALLIDA -> R.string.aviso_imagen_descarga_fallida
}
