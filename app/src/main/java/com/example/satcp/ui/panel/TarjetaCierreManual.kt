package com.example.satcp.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.satcp.R
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Fase
import com.example.satcp.servicio.ResultadoAccion
import com.example.satcp.ui.alerta.AlertaViewModel
import com.example.satcp.ui.alerta.BotonRearmar
import com.example.satcp.ui.alerta.TextoResultado
import com.example.satcp.ui.alerta.UiAlerta

/**
 * Botón rojo "Cerrar suministro" (`cerrar`). El `ok` solo dice que se aceptó: la pantalla cambia
 * con los eventos `estado` (CIERRE_AUTOMATICO y luego BLOQUEADO), no con la respuesta.
 */
@Composable
fun TarjetaCierreManual(estado: DatosEstado, uiAlerta: UiAlerta, vm: AlertaViewModel, conectado: Boolean) {
    val bloqueado = estado.fase == Fase.BLOQUEADO
    val rearmeEnAlerta = estado.alerta?.requiereRearme == true
    // Bloqueado y con su alerta: el botón de rearmar ya está en la tarjeta de alerta.
    if (bloqueado && rearmeEnAlerta) return

    var confirmar by remember { mutableStateOf(false) }
    val cerrando = estado.fase == Fase.CIERRE_AUTOMATICO

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!bloqueado) {
                Text(stringResource(R.string.cierre_manual_explicacion), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { confirmar = true },
                    enabled = conectado && !uiAlerta.enviando && !cerrando,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.cierre_manual_boton)) }
                if (cerrando) Text(stringResource(R.string.notificacion_cierre_en_curso))
            }
            TextoResultado(uiAlerta.resultadoCierre)
            // Ante ya_bloqueado, el rearme se ofrece aquí aunque el estado todavía no lo refleje.
            if (uiAlerta.resultadoCierre == ResultadoAccion.YA_BLOQUEADO && !rearmeEnAlerta) {
                BotonRearmar(enviando = uiAlerta.enviando, rearmar = vm::rearmar)
                TextoResultado(uiAlerta.resultado)
            }
        }
    }

    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            title = { Text(stringResource(R.string.cierre_manual_confirmar_titulo)) },
            text = { Text(stringResource(R.string.cierre_manual_confirmar_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmar = false
                    vm.cerrar()
                }) { Text(stringResource(R.string.cierre_manual_confirmar_si), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmar = false }) { Text(stringResource(R.string.cancelar)) }
            },
        )
    }
}
