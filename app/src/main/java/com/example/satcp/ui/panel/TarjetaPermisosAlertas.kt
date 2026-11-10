package com.example.satcp.ui.panel

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.satcp.R
import com.example.satcp.ui.comun.abrirAjustesApp
import com.example.satcp.ui.comun.tienePermisos

/**
 * Pide lo que hace falta para que las alertas lleguen con la app cerrada: el permiso de
 * notificaciones (Android 13+) y el de pantalla completa (Android 14+). Explica para qué es cada uno.
 */
@Composable
fun TarjetaPermisosAlertas() {
    val contexto = LocalContext.current
    fun notificacionesPermitidas() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        tienePermisos(contexto, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    fun pantallaCompletaPermitida() = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        contexto.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    var notificaciones by remember { mutableStateOf(notificacionesPermitidas()) }
    var pantallaCompleta by remember { mutableStateOf(pantallaCompletaPermitida()) }
    var rechazado by remember { mutableStateOf(false) }

    // Al volver de los ajustes del sistema se revisa otra vez.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificaciones = notificacionesPermitidas()
        pantallaCompleta = pantallaCompletaPermitida()
    }

    val pedirNotificaciones = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        notificaciones = concedido
        rechazado = !concedido
    }

    if (notificaciones && pantallaCompleta) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!notificaciones) {
                Text(stringResource(R.string.permiso_notificaciones_explicacion))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.permiso_notificaciones_permitir)) }
                if (rechazado) {
                    Text(stringResource(R.string.permiso_notificaciones_rechazado), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { abrirAjustesApp(contexto) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.abrir_ajustes_app))
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Text(stringResource(R.string.permiso_pantalla_completa_explicacion))
                Button(
                    onClick = {
                        contexto.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.fromParts("package", contexto.packageName, null),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.permiso_pantalla_completa_permitir)) }
            }
        }
    }
}
