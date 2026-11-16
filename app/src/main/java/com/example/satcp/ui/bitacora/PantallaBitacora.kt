package com.example.satcp.ui.bitacora

import android.app.Application
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.SatcpApp
import com.example.satcp.servicio.EventoBitacora
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BitacoraViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {
    private val almacen = (aplicacion as SatcpApp).bitacora

    /** De más nuevo a más viejo. */
    val eventos: StateFlow<List<EventoBitacora>> = almacen.eventos
        .map { it.asReversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun borrar() {
        viewModelScope.launch { almacen.borrar() }
    }
}

/** Bitácora de eventos: lo que la app registró del equipo mientras estuvo conectada. */
@Composable
fun PantallaBitacora(volver: () -> Unit, modifier: Modifier = Modifier, vm: BitacoraViewModel = viewModel()) {
    BackHandler(onBack = volver)
    val contexto = LocalContext.current
    val todos by vm.eventos.collectAsStateWithLifecycle()
    var soloImportantes by rememberSaveable { mutableStateOf(true) }
    var confirmarBorrado by rememberSaveable { mutableStateOf(false) }
    val visibles = if (soloImportantes) todos.filter { it.importante } else todos
    val tituloCompartir = stringResource(R.string.bitacora_compartir)

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bitacora_titulo), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(R.string.bitacora_explicacion), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = soloImportantes, onClick = { soloImportantes = true }, label = { Text(stringResource(R.string.bitacora_filtro_importantes)) })
            FilterChip(selected = !soloImportantes, onClick = { soloImportantes = false }, label = { Text(stringResource(R.string.bitacora_filtro_todo)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = volver) { Text(stringResource(R.string.calibracion_volver)) }
            OutlinedButton(
                onClick = {
                    val texto = bitacoraComoTexto(contexto, visibles.take(MAXIMO_COMPARTIR))
                    val envio = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
                    contexto.startActivity(Intent.createChooser(envio, tituloCompartir))
                },
                enabled = visibles.isNotEmpty(),
            ) { Text(stringResource(R.string.bitacora_compartir)) }
            TextButton(onClick = { confirmarBorrado = true }, enabled = todos.isNotEmpty()) {
                Text(stringResource(R.string.bitacora_borrar))
            }
        }

        if (visibles.isEmpty()) {
            Text(stringResource(R.string.bitacora_vacia), modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(visibles) { evento -> FilaEvento(evento) }
        }
    }

    if (confirmarBorrado) {
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            title = { Text(stringResource(R.string.bitacora_borrar_titulo)) },
            text = { Text(stringResource(R.string.bitacora_borrar_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmarBorrado = false
                    vm.borrar()
                }) { Text(stringResource(R.string.bitacora_borrar)) }
            },
            dismissButton = { TextButton(onClick = { confirmarBorrado = false }) { Text(stringResource(R.string.config_redes_cancelar)) } },
        )
    }
}

@Composable
private fun FilaEvento(evento: EventoBitacora) {
    val contexto = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (evento.destacado) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fechaEvento(evento), style = MaterialTheme.typography.labelSmall)
            Text(tituloEvento(contexto, evento), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            detalleEvento(contexto, evento)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Para no pasar del límite de tamaño de un Intent. */
private const val MAXIMO_COMPARTIR = 1_000
