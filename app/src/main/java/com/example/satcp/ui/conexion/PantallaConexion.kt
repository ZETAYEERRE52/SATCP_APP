package com.example.satcp.ui.conexion

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.satcp.R
import com.example.satcp.servicio.UltimaConexion
import com.example.satcp.ui.comun.abrirAjustesApp
import com.example.satcp.ui.comun.permisosBluetooth
import com.example.satcp.ui.comun.tienePermisos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaConexion(cambiarClave: () -> Unit, modifier: Modifier = Modifier, vm: ConexionViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.conexion_titulo), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.conexion_explicacion), style = MaterialTheme.typography.bodyMedium)

        val contexto = LocalContext.current
        ui.ultima?.let { ultima ->
            Button(
                onClick = {
                    // Sin permisos o con el Bluetooth apagado, la opción Bluetooth explica qué falta.
                    val faltaBluetooth = ultima is UltimaConexion.Bluetooth &&
                        (!tienePermisos(contexto, permisosBluetooth) || !vm.bluetoothEncendido)
                    if (faltaBluetooth) vm.elegirModo(ModoConexion.BLUETOOTH) else vm.reconectarUltima()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reconectar_ultima, descripcionUltima(ultima)))
            }
        }

        val modos = ModoConexion.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modos.forEachIndexed { indice, modo ->
                SegmentedButton(
                    selected = ui.modo == modo,
                    onClick = { vm.elegirModo(modo) },
                    shape = SegmentedButtonDefaults.itemShape(indice, modos.size),
                ) {
                    Text(
                        stringResource(
                            when (modo) {
                                ModoConexion.RED_PROPIA -> R.string.modo_red_propia
                                ModoConexion.RED_CASA -> R.string.modo_red_casa
                                ModoConexion.BLUETOOTH -> R.string.modo_bluetooth
                            }
                        )
                    )
                }
            }
        }

        when (ui.modo) {
            ModoConexion.RED_PROPIA -> OpcionRedPropia(vm::conectarRedPropia)
            ModoConexion.RED_CASA -> OpcionRedCasa(ui, vm)
            ModoConexion.BLUETOOTH -> OpcionBluetooth(ui, vm)
        }

        // Sin esto no habría salida si Android no logra unirse a SATCP porque la clave cambió.
        TextButton(onClick = cambiarClave) { Text(stringResource(R.string.clave_escribir_otra)) }
    }
}

@Composable
private fun OpcionRedPropia(conectar: () -> Unit) {
    val contexto = LocalContext.current
    val sinEspecificador = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    Text(
        stringResource(
            if (sinEspecificador) R.string.red_propia_explicacion_antigua else R.string.red_propia_explicacion
        )
    )
    if (sinEspecificador) {
        OutlinedButton(
            onClick = { contexto.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.abrir_ajustes_wifi)) }
    }
    Button(onClick = conectar, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.red_propia_conectar))
    }
}

@Composable
private fun OpcionRedCasa(ui: UiConexion, vm: ConexionViewModel) {
    DisposableEffect(Unit) {
        vm.iniciarBusqueda()
        onDispose { vm.detenerBusqueda() }
    }

    Text(stringResource(R.string.red_casa_explicacion))

    when {
        ui.errorBusqueda -> Text(stringResource(R.string.red_casa_error_busqueda), color = MaterialTheme.colorScheme.error)
        ui.equipos.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(stringResource(R.string.red_casa_buscando))
                Text(stringResource(R.string.red_casa_ninguno), style = MaterialTheme.typography.bodySmall)
            }
        }
        else -> ui.equipos.forEach { equipo ->
            OutlinedCard(onClick = { vm.conectarEquipo(equipo) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.red_casa_equipo, equipo.nombre, equipo.ip),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ui.ipManual,
        onValueChange = vm::cambiarIp,
        label = { Text(stringResource(R.string.red_casa_ip_manual)) },
        placeholder = { Text(stringResource(R.string.red_casa_ip_ejemplo)) },
        isError = ui.ipInvalida,
        supportingText = if (ui.ipInvalida) {
            { Text(stringResource(R.string.red_casa_ip_invalida)) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { vm.conectarIpManual() }),
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = vm::conectarIpManual, enabled = ui.ipManual.isNotEmpty()) {
        Text(stringResource(R.string.red_casa_conectar_ip))
    }
}

@Composable
private fun OpcionBluetooth(ui: UiConexion, vm: ConexionViewModel) {
    val contexto = LocalContext.current
    var concedidos by remember { mutableStateOf(tienePermisos(contexto, permisosBluetooth)) }
    var rechazados by remember { mutableStateOf(false) }
    var encendido by remember { mutableStateOf(vm.bluetoothEncendido) }

    val pedirPermisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        concedidos = r.values.all { it }
        rechazados = !concedidos
    }
    val pedirEncender = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        encendido = vm.bluetoothEncendido
    }

    Text(stringResource(R.string.bluetooth_explicacion))

    when {
        !vm.hayBluetooth -> Text(stringResource(R.string.bluetooth_no_disponible), color = MaterialTheme.colorScheme.error)

        !concedidos -> {
            Text(
                stringResource(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.bluetooth_permiso_explicacion
                    else R.string.bluetooth_permiso_explicacion_antigua
                )
            )
            Button(onClick = { pedirPermisos.launch(permisosBluetooth) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bluetooth_permitir))
            }
            if (rechazados) {
                Text(stringResource(R.string.bluetooth_permiso_denegado), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { abrirAjustesApp(contexto) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.abrir_ajustes_app))
                }
            }
        }

        !encendido || ui.errorEscaneo == ErrorEscaneo.APAGADO -> {
            Text(stringResource(R.string.bluetooth_apagado))
            Button(
                onClick = { pedirEncender.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.bluetooth_encender)) }
        }

        else -> {
            DisposableEffect(Unit) {
                vm.iniciarEscaneo()
                onDispose { vm.detenerEscaneo() }
            }
            ListaBluetooth(ui, vm)
        }
    }
}

@Composable
private fun ListaBluetooth(ui: UiConexion, vm: ConexionViewModel) {
    if (ui.errorEscaneo == ErrorEscaneo.FALLO) {
        Text(stringResource(R.string.bluetooth_error_escaneo), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = vm::iniciarEscaneo) { Text(stringResource(R.string.bluetooth_reintentar)) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.bluetooth_buscando))
    }
    ui.dispositivosBle.forEach { dispositivo ->
        OutlinedCard(onClick = { vm.conectarBluetooth(dispositivo) }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    R.string.bluetooth_equipo,
                    dispositivo.nombre ?: stringResource(R.string.bluetooth_sin_nombre),
                    dispositivo.direccion,
                    dispositivo.rssi,
                ),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun descripcionUltima(ultima: UltimaConexion): String = when (ultima) {
    UltimaConexion.RedPropia -> stringResource(R.string.ultima_red_propia)
    is UltimaConexion.RedCasa -> stringResource(R.string.ultima_red_casa, ultima.ip)
    is UltimaConexion.Bluetooth -> stringResource(R.string.ultima_bluetooth, ultima.nombre ?: ultima.direccion)
}
