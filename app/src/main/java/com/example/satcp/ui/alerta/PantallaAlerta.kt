package com.example.satcp.ui.alerta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.servicio.ResultadoAccion
import com.example.satcp.ui.comun.textoFase
import com.example.satcp.ui.comun.textoMotivo

/** Pantalla completa que abre la notificación de alerta, también sobre la pantalla de bloqueo. */
@Composable
fun PantallaAlerta(cerrar: () -> Unit, vm: AlertaViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val alerta = ui.estado?.alerta

    Column(
        Modifier
            .fillMaxSize()
            .background(if (alerta != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (alerta == null) {
            Text(
                stringResource(if (ui.estado == null) R.string.alerta_sin_datos else R.string.alerta_terminada),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            TextoResultado(ui.resultado)
            Button(onClick = cerrar) { Text(stringResource(R.string.cerrar)) }
        } else {
            Text(
                stringResource(
                    if (alerta.origen.esCierreSinVision) R.string.cierre_titulo else R.string.notificacion_alerta_titulo
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(textoMotivo(alerta), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(textoFase(ui.estado!!.fase), textAlign = TextAlign.Center)
            AccionesAlerta(ui, vm, grande = true)
            OutlinedButton(onClick = cerrar) { Text(stringResource(R.string.cerrar)) }
        }
    }
}

/** Cuenta regresiva y botones de la alerta. Se usa en la pantalla de alerta y en el panel. */
@Composable
fun AccionesAlerta(ui: UiAlerta, vm: AlertaViewModel, grande: Boolean) {
    val alerta = ui.estado?.alerta ?: return

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ui.segundosParaCierre?.let { segundos ->
            Text(stringResource(R.string.alerta_cierre_en), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.alerta_segundos, segundos),
                fontSize = if (grande) 72.sp else 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (alerta.admiteRespuesta) {
            Button(
                onClick = vm::responder,
                enabled = !ui.enviando,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (grande) 96.dp else 56.dp),
            ) {
                Text(
                    stringResource(R.string.alerta_estoy_en_la_cocina),
                    style = if (grande) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (alerta.requiereRearme) {
            Text(
                stringResource(R.string.notificacion_rearme_texto),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            BotonRearmar(enviando = ui.enviando, rearmar = vm::rearmar)
        }

        if (ui.enviando) CircularProgressIndicator()
        TextoResultado(ui.resultado)
    }
}

/** "Ya revisé: rearmar", con confirmación antes de enviar `rearmar`. */
@Composable
fun BotonRearmar(enviando: Boolean, rearmar: () -> Unit) {
    var confirmarRearme by remember { mutableStateOf(false) }
    Button(
        onClick = { confirmarRearme = true },
        enabled = !enviando,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) { Text(stringResource(R.string.alerta_rearmar)) }

    if (confirmarRearme) {
        AlertDialog(
            onDismissRequest = { confirmarRearme = false },
            title = { Text(stringResource(R.string.rearme_confirmar_titulo)) },
            text = { Text(stringResource(R.string.rearme_confirmar_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmarRearme = false
                    rearmar()
                }) { Text(stringResource(R.string.rearme_confirmar_si)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmarRearme = false }) { Text(stringResource(R.string.cancelar)) }
            },
        )
    }
}

@Composable
fun TextoResultado(resultado: ResultadoAccion?) {
    resultado ?: return
    val (texto, esError) = when (resultado) {
        ResultadoAccion.RESPONDIDO -> R.string.resultado_respondido to false
        ResultadoAccion.REARMADO -> R.string.resultado_rearmado to false
        ResultadoAccion.SIN_ALERTA -> R.string.resultado_sin_alerta to false
        ResultadoAccion.NO_BLOQUEADO -> R.string.resultado_no_bloqueado to false
        ResultadoAccion.CIERRE_ACEPTADO -> R.string.resultado_cierre_aceptado to false
        ResultadoAccion.YA_BLOQUEADO -> R.string.resultado_ya_bloqueado to false
        ResultadoAccion.VALVULA_CERRADA -> R.string.resultado_valvula_cerrada to true
        ResultadoAccion.FIRMWARE_ANTIGUO -> R.string.resultado_firmware_antiguo to true
        ResultadoAccion.SIN_CONEXION -> R.string.resultado_sin_conexion to true
        ResultadoAccion.SIN_RESPUESTA -> R.string.resultado_sin_respuesta to true
        ResultadoAccion.ERROR -> R.string.resultado_error to true
    }
    Text(
        stringResource(texto),
        color = if (esError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}
