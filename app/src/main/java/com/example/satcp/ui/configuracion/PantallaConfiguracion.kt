package com.example.satcp.ui.configuracion

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.DatosInfo
import com.example.satcp.protocolo.VERSION_PROTOCOLO
import com.example.satcp.servicio.RedGuardada
import com.example.satcp.ui.comun.estaExcluidaDeBateria
import com.example.satcp.ui.comun.pedirExclusionBateria

@Composable
fun PantallaConfiguracion(modifier: Modifier = Modifier, vm: ConfiguracionViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.incompatible) AvisoIncompatible(ui.info?.protocolo)
        TarjetaBateria()
        TarjetaRedesGuardadas(ui, vm)
        TarjetaWifiCasa(ui, vm)
        TarjetaCambioClave(ui.cambioClave, vm)
        TarjetaInfo(ui, vm::actualizarInfo)
    }
}

/** La app y el firmware hablan versiones distintas del protocolo. También se muestra en el panel. */
@Composable
fun AvisoIncompatible(protocoloEquipo: Int?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.incompatible_titulo), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.incompatible_texto, protocoloEquipo ?: 0, VERSION_PROTOCOLO))
        }
    }
}

/** El estado se vuelve a leer al regresar a la pantalla, porque el usuario lo cambia fuera de la app. */
@Composable
private fun TarjetaBateria() {
    val contexto = LocalContext.current
    var excluida by remember { mutableStateOf(estaExcluidaDeBateria(contexto)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { excluida = estaExcluidaDeBateria(contexto) }

    Tarjeta {
        Text(stringResource(R.string.config_bateria_titulo), style = MaterialTheme.typography.titleMedium)
        if (excluida) {
            Text(stringResource(R.string.config_bateria_excluida), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.config_bateria_explicacion), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { pedirExclusionBateria(contexto) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.config_bateria_excluir))
            }
        }
    }
}

/** Redes ya configuradas desde la app: se reenvían al equipo sin escribir ni mostrar la contraseña. */
@Composable
private fun TarjetaRedesGuardadas(ui: UiConfiguracion, vm: ConfiguracionViewModel) {
    var porBorrar by remember { mutableStateOf<RedGuardada?>(null) }

    Tarjeta {
        Text(stringResource(R.string.config_redes_titulo), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.config_redes_explicacion), style = MaterialTheme.typography.bodySmall)
        ui.redesGuardadas.forEach { red ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(red.ssid, style = MaterialTheme.typography.bodyLarge)
                    if (red.clave == null) {
                        Text(stringResource(R.string.config_redes_abierta), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { porBorrar = red }) { Text(stringResource(R.string.config_redes_borrar)) }
                OutlinedButton(onClick = { vm.usarRedGuardada(red) }, enabled = !ui.wifi.enviando) {
                    Text(stringResource(R.string.config_redes_usar))
                }
            }
        }
    }

    porBorrar?.let { red ->
        AlertDialog(
            onDismissRequest = { porBorrar = null },
            title = { Text(stringResource(R.string.config_redes_borrar_titulo, red.ssid)) },
            text = { Text(stringResource(R.string.config_redes_borrar_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.borrarRedGuardada(red)
                    porBorrar = null
                }) { Text(stringResource(R.string.config_redes_borrar)) }
            },
            dismissButton = {
                TextButton(onClick = { porBorrar = null }) { Text(stringResource(R.string.config_redes_cancelar)) }
            },
        )
    }
}

@Composable
private fun TarjetaWifiCasa(ui: UiConfiguracion, vm: ConfiguracionViewModel) {
    val wifi = ui.wifi
    var mostrarClave by rememberSaveable { mutableStateOf(false) }

    Tarjeta {
        Text(stringResource(R.string.config_wifi_titulo), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.config_wifi_explicacion), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = wifi.ssid,
            onValueChange = vm::cambiarSsid,
            label = { Text(stringResource(R.string.config_wifi_ssid)) },
            supportingText = { Text(stringResource(R.string.config_contador, wifi.ssid.length, Comando.Wifi.MAX_SSID)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = wifi.redAbierta, onCheckedChange = vm::cambiarRedAbierta)
            Text(stringResource(R.string.config_wifi_abierta))
        }

        if (!wifi.redAbierta) {
            OutlinedTextField(
                value = wifi.clave,
                onValueChange = vm::cambiarClave,
                label = { Text(stringResource(R.string.config_wifi_clave)) },
                supportingText = { Text(stringResource(R.string.config_contador, wifi.clave.length, Comando.Wifi.MAX_CLAVE)) },
                singleLine = true,
                visualTransformation = if (mostrarClave) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { mostrarClave = !mostrarClave }) {
                        Text(stringResource(if (mostrarClave) R.string.config_ocultar else R.string.config_mostrar))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = vm::enviarWifi,
            enabled = !wifi.enviando && wifi.ssidValido && wifi.claveValida,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (wifi.enviando) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.config_wifi_guardar))
            }
        }

        wifi.resultado?.let { resultado ->
            val esError = resultado != ResultadoComando.LISTO
            val texto = when (resultado) {
                ResultadoComando.LISTO -> wifi.mensaje ?: stringResource(R.string.config_wifi_listo)
                ResultadoComando.WIFI_ERROR -> stringResource(R.string.config_wifi_error)
                ResultadoComando.SIN_IP -> stringResource(R.string.config_wifi_sin_ip_tras_espera, wifi.ssid)
                else -> stringResource(textoFalla(resultado))
            }
            Text(texto, color = if (esError) MaterialTheme.colorScheme.error else Color.Unspecified)
        }

        Text(
            ui.ipCasa?.let { stringResource(R.string.config_wifi_ip, it) } ?: stringResource(R.string.config_wifi_sin_ip),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** `cambiar_clave`: pide la clave nueva dos veces para evitar errores de escritura (§4). */
@Composable
private fun TarjetaCambioClave(formulario: FormularioClave, vm: ConfiguracionViewModel) {
    var mostrar by remember { mutableStateOf(false) }
    val transformacion = if (mostrar) VisualTransformation.None else PasswordVisualTransformation()
    val teclado = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

    Tarjeta {
        Text(stringResource(R.string.config_clave_titulo), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.config_clave_explicacion), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = formulario.nueva,
            onValueChange = vm::cambiarClaveNueva,
            label = { Text(stringResource(R.string.config_clave_nueva)) },
            isError = formulario.nueva.isNotEmpty() && !formulario.valida,
            supportingText = {
                Text(
                    if (formulario.nueva.isNotEmpty() && !formulario.valida) stringResource(R.string.clave_invalida)
                    else stringResource(R.string.config_contador, formulario.nueva.length, Comando.Wifi.MAX_CLAVE)
                )
            },
            singleLine = true,
            visualTransformation = transformacion,
            keyboardOptions = teclado,
            trailingIcon = {
                TextButton(onClick = { mostrar = !mostrar }) {
                    Text(stringResource(if (mostrar) R.string.config_ocultar else R.string.config_mostrar))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val noCoinciden = formulario.repetida.isNotEmpty() && !formulario.coinciden
        OutlinedTextField(
            value = formulario.repetida,
            onValueChange = vm::cambiarClaveRepetida,
            label = { Text(stringResource(R.string.config_clave_repetir)) },
            isError = noCoinciden,
            supportingText = if (noCoinciden) {
                { Text(stringResource(R.string.config_clave_no_coinciden)) }
            } else null,
            singleLine = true,
            visualTransformation = transformacion,
            keyboardOptions = teclado,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = vm::enviarCambioClave,
            enabled = !formulario.enviando && formulario.valida && formulario.coinciden,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (formulario.enviando) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.config_clave_cambiar))
            }
        }

        formulario.resultado?.let { resultado ->
            Text(
                stringResource(
                    when (resultado) {
                        ResultadoCambioClave.LISTA -> R.string.config_clave_lista
                        ResultadoCambioClave.RECHAZADA -> R.string.config_clave_rechazada
                        ResultadoCambioClave.SIN_CONEXION -> R.string.resultado_sin_conexion
                        ResultadoCambioClave.SIN_RESPUESTA -> R.string.config_clave_sin_respuesta
                    }
                ),
                color = if (resultado == ResultadoCambioClave.LISTA) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TarjetaInfo(ui: UiConfiguracion, actualizar: () -> Unit) {
    Tarjeta {
        Text(stringResource(R.string.config_info_titulo), style = MaterialTheme.typography.titleMedium)
        val info = ui.info
        if (info == null) {
            Text(stringResource(R.string.config_info_sin_datos))
        } else {
            DatosDispositivo(info)
        }
        ui.errorInfo?.let { Text(stringResource(textoFalla(it)), color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = actualizar, enabled = !ui.actualizandoInfo) {
            Text(stringResource(R.string.config_info_actualizar))
        }
    }
}

@Composable
private fun DatosDispositivo(info: DatosInfo) {
    val sinValor = stringResource(R.string.sin_valor)
    Fila(R.string.config_info_dispositivo, info.dispositivo ?: sinValor)
    Fila(R.string.config_info_firmware, info.firmware ?: sinValor)
    Fila(R.string.config_info_protocolo, info.protocolo?.toString() ?: sinValor)
    Fila(R.string.config_info_mac, info.mac ?: sinValor)
    Fila(R.string.config_info_clases, info.clases.joinToString(", ").ifEmpty { sinValor })
    Fila(R.string.config_info_ble, info.bleNombre ?: sinValor)
    Fila(R.string.config_info_red_propia, listOfNotNull(info.wifi.apSsid, info.wifi.apIp).joinToString(" · ").ifEmpty { sinValor })
    Fila(R.string.config_info_red_casa, info.wifi.casaIp ?: sinValor)
}

@Composable
private fun Fila(@StringRes etiqueta: Int, valor: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(etiqueta), style = MaterialTheme.typography.bodyMedium)
        Text(valor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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

private fun textoFalla(resultado: ResultadoComando) = when (resultado) {
    ResultadoComando.SIN_CONEXION -> R.string.resultado_sin_conexion
    ResultadoComando.SIN_RESPUESTA -> R.string.resultado_sin_respuesta
    else -> R.string.resultado_error
}
