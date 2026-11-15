package com.example.satcp.ui.calibracion

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.protocolo.Estufa
import com.example.satcp.protocolo.ProgresoCalibracion
import com.example.satcp.protocolo.ResultadoCalibracion
import com.example.satcp.ui.comun.textoFase
import com.example.satcp.ui.comun.textoTemperatura

/** "Calibrar con la estufa": el usuario enciende una parrilla al mínimo y el equipo mide la subida. */
@Composable
fun PantallaCalibracion(volver: () -> Unit, modifier: Modifier = Modifier, vm: CalibracionViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val salir = {
        vm.alSalir()
        volver()
    }
    BackHandler(onBack = salir)
    LifecycleStartEffect(Unit) {
        vm.alMostrarse()
        onStopOrDispose { vm.alOcultarse() }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.calibracion_titulo), style = MaterialTheme.typography.headlineSmall)
        val progreso = ui.estufa?.calibracion
        when (ui.etapa) {
            EtapaCalibracion.INICIO -> Inicio(ui, vm::comenzar)
            EtapaCalibracion.ENVIANDO -> Esperando(R.string.calibracion_enviando)
            EtapaCalibracion.PREPARANDO -> Esperando(R.string.calibracion_preparando)
            EtapaCalibracion.REFERENCIA -> PasoReferencia(progreso)
            EtapaCalibracion.MIDIENDO -> PasoMidiendo(progreso)
            EtapaCalibracion.RESULTADO -> Resultado(ui, reintentar = vm::comenzar, terminar = {
                vm.volverAlInicio()
                volver()
            })
        }
        OutlinedButton(onClick = salir, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.calibracion_volver))
        }
        if (ui.etapa == EtapaCalibracion.REFERENCIA || ui.etapa == EtapaCalibracion.MIDIENDO) {
            Text(stringResource(R.string.calibracion_salir_aviso), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Inicio(ui: UiCalibracion, comenzar: () -> Unit) {
    Text(stringResource(R.string.calibracion_explicacion))
    Tarjeta {
        Text(stringResource(R.string.calibracion_umbral_actual), style = MaterialTheme.typography.labelLarge)
        Text(textoUmbralEstufa(ui.estufa), style = MaterialTheme.typography.titleMedium)
    }
    if (!ui.puedeComenzar && ui.estufa != null) {
        Text(stringResource(R.string.calibracion_solo_monitoreo, textoFase(ui.fase)), color = MaterialTheme.colorScheme.error)
    }
    ui.fallo?.let { fallo ->
        Text(stringResource(textoFallo(fallo)), color = MaterialTheme.colorScheme.error)
        ui.mensajeFallo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    Button(onClick = comenzar, enabled = ui.puedeComenzar, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.calibracion_comenzar))
    }
}

@Composable
private fun Esperando(@StringRes texto: Int) {
    Tarjeta {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(texto), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PasoReferencia(progreso: ProgresoCalibracion?) {
    val segundos = progreso?.segundosRestantes
    Tarjeta {
        Text(
            if (segundos != null) stringResource(R.string.calibracion_referencia, segundos)
            else stringResource(R.string.calibracion_referencia_sin_tiempo),
            style = MaterialTheme.typography.titleMedium,
        )
        Barra(segundos, CalibracionViewModel.DURACION_REFERENCIA_S)
        Text(stringResource(R.string.calibracion_referencia_aviso))
    }
}

@Composable
private fun PasoMidiendo(progreso: ProgresoCalibracion?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.calibracion_enciende),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        )
    }
    Tarjeta {
        val incremento = progreso?.incremento
        val referencia = progreso?.referencia
        if (incremento != null) {
            Text(
                stringResource(R.string.calibracion_subida, incremento, textoTemperatura(referencia)),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        val segundos = progreso?.segundosRestantes
        if (segundos != null) {
            Text(stringResource(R.string.calibracion_tiempo_restante, segundos / 60, segundos % 60))
        }
        Barra(segundos, CalibracionViewModel.DURACION_MAXIMA_MEDICION_S)
        Text(stringResource(R.string.calibracion_puede_terminar_antes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Resultado(ui: UiCalibracion, reintentar: () -> Unit, terminar: () -> Unit) {
    val resultado = ui.estufa?.ultimoResultado
    val exito = resultado == ResultadoCalibracion.OK
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (exito) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (exito) {
                Text(
                    stringResource(R.string.calibracion_ok, textoTemperatura(ui.estufa?.umbralIncremento)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.calibracion_ok_apagar))
            } else {
                Text(stringResource(textoResultadoFallido(resultado)), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (exito) {
        Button(onClick = terminar, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.calibracion_listo)) }
    } else {
        // Al reintentar se vuelve al inicio, que muestra el rechazo si lo hay.
        Button(onClick = reintentar, enabled = ui.puedeComenzar, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.calibracion_reintentar))
        }
    }
}

/** Avance del paso según los segundos que faltan; indeterminada si el equipo no los informa. */
@Composable
private fun Barra(segundosRestantes: Int?, duracionS: Int) {
    if (segundosRestantes == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        val avance = (1f - segundosRestantes.toFloat() / duracionS).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { avance }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Tarjeta(contenido: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = contenido)
    }
}

/** "4.0 °C (de fábrica)" o "6.2 °C (calibrado)". También lo usan el panel y Configuración. */
@Composable
fun textoUmbralEstufa(estufa: Estufa?): String {
    if (estufa == null) return stringResource(R.string.sin_valor)
    val origen = stringResource(if (estufa.calibrado) R.string.estufa_calibrado else R.string.estufa_de_fabrica)
    return stringResource(R.string.estufa_umbral_valor, textoTemperatura(estufa.umbralIncremento), origen)
}

@StringRes
private fun textoFallo(fallo: FalloInicio) = when (fallo) {
    FalloInicio.NO_DISPONIBLE -> R.string.calibracion_no_disponible
    FalloInicio.RECHAZADO -> R.string.calibracion_rechazada
    FalloInicio.SIN_CONEXION -> R.string.resultado_sin_conexion
    FalloInicio.SIN_RESPUESTA -> R.string.resultado_sin_respuesta
}

@StringRes
private fun textoResultadoFallido(resultado: ResultadoCalibracion?) = when (resultado) {
    ResultadoCalibracion.SIN_CALOR -> R.string.calibracion_sin_calor
    ResultadoCalibracion.CANCELADA -> R.string.calibracion_cancelada
    ResultadoCalibracion.ERROR_SENSOR -> R.string.calibracion_error_sensor
    ResultadoCalibracion.OK, null -> R.string.calibracion_sin_resultado
}
