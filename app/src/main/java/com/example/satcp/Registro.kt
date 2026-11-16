package com.example.satcp

import android.util.Log
import java.util.Locale

/**
 * Registro de depuración del protocolo en Logcat, con la etiqueta `SATCP`. Nunca recibe contenidos
 * descifrados, claves ni tokens: solo largos, contadores, nombres de comando, ids y causas.
 *
 * Para leerlo: `adb logcat -s SATCP`.
 */
object Registro {
    const val ETIQUETA = "SATCP"

    /** Medición de la vista en vivo: `adb logcat -s SATCP_VIVO`. */
    const val ETIQUETA_VIVO = "SATCP_VIVO"

    fun info(mensaje: String) {
        Log.i(ETIQUETA, mensaje)
    }

    fun vivo(mensaje: String) {
        Log.i(ETIQUETA_VIVO, mensaje)
    }

    fun aviso(mensaje: String) {
        Log.w(ETIQUETA, mensaje)
    }

    /**
     * Diagnóstico TEMPORAL de cuánto tarda la conexión: `adb logcat -s SATCP_CONEXION`. Solo en
     * compilaciones de depuración. Cada línea lleva los segundos desde que arrancó el proceso.
     */
    const val ETIQUETA_CONEXION = "SATCP_CONEXION"
    private val inicioProcesoNs = System.nanoTime()

    fun conexion(mensaje: String) {
        if (!BuildConfig.DEBUG) return
        val segundos = (System.nanoTime() - inicioProcesoNs) / 1e9
        Log.i(ETIQUETA_CONEXION, String.format(Locale.ROOT, "[+%.3f s] %s", segundos, mensaje))
    }
}
