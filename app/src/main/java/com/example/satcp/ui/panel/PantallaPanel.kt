package com.example.satcp.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.EstadoValvula
import com.example.satcp.protocolo.Fase
import com.example.satcp.servicio.EstadoConexion
import com.example.satcp.ui.alerta.AccionesAlerta
import com.example.satcp.ui.alerta.AlertaViewModel
import com.example.satcp.ui.alerta.UiAlerta
import com.example.satcp.ui.configuracion.AvisoIncompatible
import com.example.satcp.ui.comun.recursoValvula
import com.example.satcp.ui.comun.textoConexion
import com.example.satcp.ui.comun.textoFase
import com.example.satcp.ui.comun.textoPresencia
import com.example.satcp.ui.comun.textoTemperatura
import com.example.satcp.ui.comun.textoTransporte
import com.example.satcp.ui.comun.textoMotivo
import kotlinx.coroutines.delay

@Composable
fun PantallaPanel(
    modifier: Modifier = Modifier,
    vm: PanelViewModel = viewModel(),
    alertaVm: AlertaViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val uiAlerta by alertaVm.ui.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.incompatible) AvisoIncompatible(ui.protocoloEquipo)
        TarjetaConexion(ui, vm::desconectar)
        TarjetaPermisosAlertas()

        val estado = ui.estado
        if (estado == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.panel_esperando))
            }
        } else {
            if (ui.datosViejos) {
                Text(
                    stringResource(R.string.panel_datos_viejos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ContenidoEstado(estado, uiAlerta, alertaVm, Modifier.fillMaxWidth())
            TarjetaCierreManual(estado, uiAlerta, alertaVm, conectado = ui.conexion == EstadoConexion.Conectado)
        }
    }
}

@Composable
private fun TarjetaConexion(ui: UiPanel, desconectar: () -> Unit) {
    val conexion = ui.conexion
    val color = when (conexion) {
        EstadoConexion.Conectado -> MaterialTheme.colorScheme.secondaryContainer
        is EstadoConexion.Perdida -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ui.transporte?.let { Text(textoTransporte(it), style = MaterialTheme.typography.labelLarge) }
            Text(textoConexion(conexion), style = MaterialTheme.typography.titleMedium)
            if (conexion is EstadoConexion.Perdida) {
                val segundos by cuentaRegresiva(conexion)
                Text(stringResource(R.string.estado_reintento, segundos))
            }
            OutlinedButton(onClick = desconectar, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.desconectar))
            }
        }
    }
}

/** Segundos que faltan para el próximo intento de reconexión. */
@Composable
private fun cuentaRegresiva(perdida: EstadoConexion.Perdida) =
    produceState(initialValue = ((perdida.reintentoEnMs + 999) / 1000).toInt(), perdida) {
        while (value > 0) {
            delay(1_000)
            value -= 1
        }
    }

@Composable
private fun ContenidoEstado(estado: DatosEstado, uiAlerta: UiAlerta, alertaVm: AlertaViewModel, modifier: Modifier) {
    estado.alerta?.let { alerta ->
        Tarjeta(modifier, MaterialTheme.colorScheme.errorContainer) {
            Text(textoMotivo(alerta), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            AccionesAlerta(uiAlerta, alertaVm, grande = false)
        }
    }

    // §5: aviso visible y permanente mientras dure la falla.
    if (!estado.temperatura.sensorOk) {
        Tarjeta(modifier, MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.panel_sensor_sin_respuesta), style = MaterialTheme.typography.titleMedium)
        }
    }

    Tarjeta(modifier, colorFase(estado.fase)) {
        Text(stringResource(R.string.panel_fase), style = MaterialTheme.typography.labelLarge)
        Text(textoFase(estado.fase), style = MaterialTheme.typography.headlineSmall)
        Text(estado.fase.name, style = MaterialTheme.typography.bodySmall)
    }

    val valvulaCerrada = estado.valvula.estado == EstadoValvula.CERRADA
    Tarjeta(modifier, if (valvulaCerrada) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text(stringResource(R.string.panel_valvula), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(recursoValvula(estado.valvula.estado)), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (estado.valvula.sensor) R.string.valvula_medida else R.string.valvula_supuesta),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Tarjeta(modifier) {
        Text(stringResource(R.string.panel_temperatura), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Valor(stringResource(R.string.panel_temperatura_medida), textoTemperatura(estado.temperatura.medida))
            Valor(stringResource(R.string.panel_temperatura_estimada), textoTemperatura(estado.temperatura.estimada))
        }
        Text(
            stringResource(
                R.string.panel_temperatura_detalle,
                textoTemperatura(estado.temperatura.sensor),
                textoTemperatura(estado.temperatura.lineaBase),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Tarjeta(modifier) {
        Text(stringResource(R.string.panel_presencia), style = MaterialTheme.typography.labelLarge)
        Text(textoPresencia(estado.presencia.estado), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                R.string.panel_distancias,
                estado.presencia.distanciaMovimientoCm,
                estado.presencia.distanciaQuietaCm,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun colorFase(fase: Fase): Color = when (fase) {
    Fase.ALERTA_USUARIO, Fase.CIERRE_AUTOMATICO, Fase.BLOQUEADO -> MaterialTheme.colorScheme.errorContainer
    Fase.VALIDAR_PRESENCIA, Fase.DETECCION_VISUAL -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
private fun Tarjeta(
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = contenido)
    }
}

@Composable
private fun Valor(etiqueta: String, valor: String) {
    Column {
        Text(etiqueta, style = MaterialTheme.typography.bodySmall)
        Text(valor, style = MaterialTheme.typography.headlineMedium)
    }
}
