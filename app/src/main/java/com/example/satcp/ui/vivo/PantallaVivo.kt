package com.example.satcp.ui.vivo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.ui.camara.BotonTomarFoto
import com.example.satcp.ui.camara.CamaraViewModel
import com.example.satcp.ui.camara.ImagenFoto
import com.example.satcp.ui.comun.textoCausa

/** Vista en vivo para encuadrar las hornillas. Por Bluetooth no hay video: se ofrece una vista previa con fotos. */
@Composable
fun PantallaVivo(porBluetooth: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.vivo_explicacion))
        if (porBluetooth) VistaPreviaBluetooth() else VideoWifi()
    }
}

@Composable
private fun VideoWifi(vm: VivoViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cuadro by vm.cuadro.collectAsStateWithLifecycle()

    // Se abre al mostrarse y se cierra al salir de la pantalla o pasar a segundo plano.
    LifecycleStartEffect(Unit) {
        vm.alMostrarse()
        onStopOrDispose { vm.detener() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        cuadro?.let { ImagenConCuadricula(it) }
        val medida = ui.medida
        if (ui.depuracion && medida != null) {
            Text(
                textoMedida(medida),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        when (ui.estado) {
            EstadoVivo.CONECTANDO, EstadoVivo.DETENIDO -> CircularProgressIndicator(color = Color.White)
            EstadoVivo.MOSTRANDO -> Unit
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                val causa = ui.causa
                Text(
                    stringResource(textoEstado(ui.estado)) + (causa?.let { "\n" + textoCausa(it) } ?: ""),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    ui.medida?.let {
        Text(
            stringResource(R.string.vivo_fps, it.leidosPorSegundo.toFloat(), it.mostradosPorSegundo.toFloat()),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    when (ui.estado) {
        EstadoVivo.CONECTANDO, EstadoVivo.MOSTRANDO -> OutlinedButton(onClick = vm::parar, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vivo_parar))
        }
        EstadoVivo.PARADO -> Button(onClick = vm::iniciar, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vivo_iniciar))
        }
        EstadoVivo.TERMINADO -> Button(onClick = vm::iniciar, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vivo_reanudar))
        }
        EstadoVivo.OCUPADO, EstadoVivo.ERROR, EstadoVivo.SIN_WIFI -> Button(onClick = vm::iniciar, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vivo_reintentar))
        }
        else -> Unit
    }
}

@Composable
private fun VistaPreviaBluetooth(vm: CamaraViewModel = viewModel(key = "vista_previa_ble")) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Text(stringResource(R.string.vivo_solo_wifi), color = MaterialTheme.colorScheme.error)
    BotonTomarFoto(ui, vm::tomarFoto, texto = R.string.vivo_actualizar)
    val imagen = ui.imagen
    if (imagen != null) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(imagen.width.toFloat() / imagen.height)
                .clip(RoundedCornerShape(12.dp))
        ) { ImagenConCuadricula(imagen) }
    } else if (ui.datos != null) {
        ImagenFoto(ui, vm::tomarFoto)
    }
}

/** La imagen con una cuadrícula de tercios encima, para ayudar a encuadrar las hornillas. */
@Composable
private fun ImagenConCuadricula(imagen: ImageBitmap) {
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = imagen,
            contentDescription = stringResource(R.string.vivo_descripcion),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val grosor = 1.dp.toPx()
            for (i in 1..2) {
                val x = size.width * i / 3
                val y = size.height * i / 3
                // Sombra oscura bajo la línea clara: se ve sobre fondos claros y oscuros.
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(x + grosor, 0f), Offset(x + grosor, size.height), grosor)
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(0f, y + grosor), Offset(size.width, y + grosor), grosor)
                drawLine(Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), grosor)
                drawLine(Color.White.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y), grosor)
            }
        }
    }
}

/** Recuadro de depuración: lo mismo que se registra en `SATCP_VIVO`. */
@Composable
private fun textoMedida(medida: MedidaVivo): String {
    val desdeEstado = medida.segundosDesdeEstado?.let { stringResource(R.string.vivo_medida_segundos, it) }
        ?: stringResource(R.string.sin_valor)
    return stringResource(
        R.string.vivo_medida,
        medida.kilobytesPorSegundo,
        medida.leidosPorSegundo,
        medida.mostradosPorSegundo,
        medida.descartados,
        desdeEstado,
    )
}

private fun textoEstado(estado: EstadoVivo) = when (estado) {
    EstadoVivo.PARADO -> R.string.vivo_parado
    EstadoVivo.OCUPADO -> R.string.vivo_ocupado
    EstadoVivo.TERMINADO -> R.string.vivo_terminado
    EstadoVivo.ERROR -> R.string.vivo_error
    EstadoVivo.SIN_WIFI -> R.string.vivo_sin_wifi
    else -> R.string.vivo_conectando
}
