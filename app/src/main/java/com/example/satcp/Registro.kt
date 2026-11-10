package com.example.satcp

import android.util.Log

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
}
