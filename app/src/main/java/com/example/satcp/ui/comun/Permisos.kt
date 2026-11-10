package com.example.satcp.ui.comun

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Permisos de Bluetooth: dispositivos cercanos desde Android 12, ubicación en las versiones anteriores. */
val permisosBluetooth: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun tienePermisos(contexto: Context, permisos: Array<String>): Boolean =
    permisos.all { ContextCompat.checkSelfPermission(contexto, it) == PackageManager.PERMISSION_GRANTED }

fun estaExcluidaDeBateria(contexto: Context): Boolean =
    contexto.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(contexto.packageName)

/**
 * Pide excluir la app de la optimización de batería. Algunos fabricantes quitan este diálogo; en ese
 * caso se abre la lista de apps de los ajustes del sistema.
 */
@SuppressLint("BatteryLife") // Las alertas de seguridad dependen de mantener abierta la conexión con el ESP32.
fun pedirExclusionBateria(contexto: Context) {
    val dialogo = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", contexto.packageName, null),
    )
    try {
        contexto.startActivity(dialogo)
    } catch (_: ActivityNotFoundException) {
        contexto.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/** Ajustes de la app, para conceder un permiso que el usuario rechazó definitivamente. */
fun abrirAjustesApp(contexto: Context) {
    contexto.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", contexto.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
