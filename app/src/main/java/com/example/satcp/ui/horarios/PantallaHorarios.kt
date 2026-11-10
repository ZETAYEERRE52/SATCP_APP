package com.example.satcp.ui.horarios

import androidx.annotation.StringRes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.protocolo.Horario
import com.example.satcp.protocolo.ResumenDias
import com.example.satcp.protocolo.resumirDias

/** Cierres programados. Funciona igual por Wi-Fi y por Bluetooth: solo usa comandos. */
@Composable
fun PantallaHorarios(irAConfiguracion: () -> Unit, modifier: Modifier = Modifier, vm: HorariosViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.cargar() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val datos = ui.datos
        if (datos != null && !datos.horaValida) AvisoSinHora(irAConfiguracion)

        Text(stringResource(R.string.horarios_explicacion), style = MaterialTheme.typography.bodyMedium)

        // Los errores del formulario se muestran dentro de él.
        if (ui.edicion == null) ui.error?.let { error ->
            Text(stringResource(textoErrorHorarios(error)), color = MaterialTheme.colorScheme.error)
            if (error == ErrorHorarios.SIN_CONEXION || error == ErrorHorarios.SIN_RESPUESTA) {
                OutlinedButton(onClick = vm::cargar) { Text(stringResource(R.string.camara_reintentar)) }
            }
        }

        when {
            datos == null && ui.cargando -> CircularProgressIndicator()
            datos != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.horarios_cuenta, datos.horarios.size, datos.maximo),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.cargando || ui.enviando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (datos.horarios.isEmpty()) Text(stringResource(R.string.horarios_vacio))
                datos.horarios.forEach { horario ->
                    FilaHorario(
                        horario = horario,
                        habilitado = !ui.enviando,
                        editar = { vm.editar(horario) },
                        borrar = { vm.pedirBorrar(horario) },
                        cambiarActivo = { vm.cambiarActivo(horario, it) },
                    )
                }
                if (datos.horarios.isNotEmpty()) {
                    Text(stringResource(R.string.horarios_ayuda_borrar), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = vm::nuevo, enabled = !ui.enviando, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.horario_agregar))
                }
            }
        }
    }

    ui.edicion?.let { edicion ->
        DialogoHorario(
            edicion = edicion,
            enviando = ui.enviando,
            error = ui.error,
            guardar = vm::guardarEdicion,
            cancelar = vm::cancelarEdicion,
        )
    }

    ui.porBorrar?.let { horario ->
        AlertDialog(
            onDismissRequest = vm::cancelarBorrar,
            title = { Text(stringResource(R.string.horario_borrar_titulo)) },
            text = {
                Text(stringResource(R.string.horario_borrar_texto, textoHora(horario), textoDias(horario.dias)))
            },
            confirmButton = {
                TextButton(onClick = vm::confirmarBorrar) {
                    Text(stringResource(R.string.horario_borrar), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelarBorrar) { Text(stringResource(R.string.cancelar)) } },
        )
    }
}

/** Aviso fijo mientras `hora_valida` sea false. */
@Composable
private fun AvisoSinHora(irAConfiguracion: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.horarios_sin_hora_titulo), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.horarios_sin_hora_texto))
            TextButton(onClick = irAConfiguracion) { Text(stringResource(R.string.horarios_ir_wifi)) }
        }
    }
}

/** Toque: editar. Presión larga: borrar (con confirmación). */
@Composable
private fun FilaHorario(
    horario: Horario,
    habilitado: Boolean,
    editar: () -> Unit,
    borrar: () -> Unit,
    cambiarActivo: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = habilitado, onClick = editar, onLongClick = borrar),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    textoHora(horario),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (horario.activo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(textoDias(horario.dias), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(8.dp))
            Switch(checked = horario.activo, onCheckedChange = cambiarActivo, enabled = habilitado)
        }
    }
}

@Composable
private fun textoHora(horario: Horario): String = stringResource(R.string.horario_hora, horario.hora, horario.minuto)

@Composable
fun textoDias(mascara: Int): String = when (val resumen = resumirDias(mascara)) {
    ResumenDias.TodosLosDias -> stringResource(R.string.dias_todos)
    ResumenDias.LunesAViernes -> stringResource(R.string.dias_lunes_a_viernes)
    ResumenDias.FinDeSemana -> stringResource(R.string.dias_fin_de_semana)
    is ResumenDias.Lista -> resumen.dias.map { stringResource(DIAS_CORTOS[it]) }.joinToString(", ")
}

/** Nombres cortos de lunes (0) a domingo (6). */
val DIAS_CORTOS = listOf(
    R.string.dia_lunes,
    R.string.dia_martes,
    R.string.dia_miercoles,
    R.string.dia_jueves,
    R.string.dia_viernes,
    R.string.dia_sabado,
    R.string.dia_domingo,
)

@StringRes
fun textoErrorHorarios(error: ErrorHorarios): Int = when (error) {
    ErrorHorarios.SIN_ESPACIO -> R.string.horarios_error_sin_espacio
    ErrorHorarios.PARAMETROS_INVALIDOS -> R.string.horarios_error_parametros
    ErrorHorarios.FIRMWARE_ANTIGUO -> R.string.resultado_firmware_antiguo
    ErrorHorarios.SIN_CONEXION -> R.string.resultado_sin_conexion
    ErrorHorarios.SIN_RESPUESTA -> R.string.resultado_sin_respuesta
    ErrorHorarios.RECHAZADO -> R.string.resultado_error
}
