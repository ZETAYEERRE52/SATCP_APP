package com.example.satcp.ui.horarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.satcp.R
import com.example.satcp.protocolo.DiasSemana

/** Formulario de un horario: hora de 24 h y siete casillas de día. Exige al menos un día. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogoHorario(
    edicion: EdicionHorario,
    enviando: Boolean,
    error: ErrorHorarios?,
    guardar: (hora: Int, minuto: Int, dias: Int) -> Unit,
    cancelar: () -> Unit,
) {
    val reloj = rememberTimePickerState(initialHour = edicion.hora, initialMinute = edicion.minuto, is24Hour = true)
    var dias by rememberSaveable { mutableIntStateOf(edicion.dias) }

    AlertDialog(
        onDismissRequest = cancelar,
        title = {
            Text(stringResource(if (edicion.indice == null) R.string.horario_nuevo else R.string.horario_editar))
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimePicker(state = reloj)
                Text(stringResource(R.string.horario_zona), style = MaterialTheme.typography.bodySmall)

                Text(stringResource(R.string.horario_dias), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (dia in 0 until DiasSemana.CANTIDAD) {
                        FilterChip(
                            selected = DiasSemana.contiene(dias, dia),
                            onClick = { dias = DiasSemana.alternar(dias, dia) },
                            label = { Text(stringResource(DIAS_CORTOS[dia])) },
                        )
                    }
                }
                if (dias == 0) {
                    Text(stringResource(R.string.horario_sin_dias), color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(stringResource(textoErrorHorarios(it)), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { guardar(reloj.hour, reloj.minute, dias) },
                enabled = dias != 0 && !enviando,
            ) { Text(stringResource(R.string.horario_guardar)) }
        },
        dismissButton = {
            TextButton(onClick = cancelar) { Text(stringResource(R.string.cancelar)) }
        },
    )
}
