package com.example.satcp.servicio

import android.content.Context
import androidx.core.content.edit

/** Cómo se conectó el usuario la última vez, para ofrecer reconectar igual. */
sealed interface UltimaConexion {
    data object RedPropia : UltimaConexion
    data class RedCasa(val ip: String, val puerto: Int) : UltimaConexion
    data class Bluetooth(val direccion: String, val nombre: String?) : UltimaConexion
}

class Preferencias(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences("satcp", Context.MODE_PRIVATE)

    var ultimaConexion: UltimaConexion?
        get() = when (prefs.getString(CLAVE_TIPO, null)) {
            TIPO_RED_PROPIA -> UltimaConexion.RedPropia
            TIPO_RED_CASA -> prefs.getString(CLAVE_IP, null)?.let {
                UltimaConexion.RedCasa(it, prefs.getInt(CLAVE_PUERTO, 80))
            }
            TIPO_BLUETOOTH -> prefs.getString(CLAVE_DIRECCION, null)?.let {
                UltimaConexion.Bluetooth(it, prefs.getString(CLAVE_NOMBRE, null))
            }
            else -> null
        }
        set(valor) = prefs.edit {
            when (valor) {
                null -> remove(CLAVE_TIPO)
                UltimaConexion.RedPropia -> putString(CLAVE_TIPO, TIPO_RED_PROPIA)
                is UltimaConexion.RedCasa -> {
                    putString(CLAVE_TIPO, TIPO_RED_CASA)
                    putString(CLAVE_IP, valor.ip)
                    putInt(CLAVE_PUERTO, valor.puerto)
                }
                is UltimaConexion.Bluetooth -> {
                    putString(CLAVE_TIPO, TIPO_BLUETOOTH)
                    putString(CLAVE_DIRECCION, valor.direccion)
                    putString(CLAVE_NOMBRE, valor.nombre)
                }
            }
        }

    /**
     * La IP en la red de la casa que el propio equipo informa en `conexion.wifi_casa_ip`. Sirve de
     * respaldo si mDNS no lo encuentra, por ejemplo cuando el router le cambió la IP.
     */
    var ipCasaInformada: String?
        get() = prefs.getString(CLAVE_IP_INFORMADA, null)
        set(valor) = prefs.edit { if (valor == null) remove(CLAVE_IP_INFORMADA) else putString(CLAVE_IP_INFORMADA, valor) }

    private companion object {
        const val CLAVE_IP_INFORMADA = "ip_casa_informada"
        const val CLAVE_TIPO = "ultima_conexion"
        const val CLAVE_IP = "ip_casa"
        const val CLAVE_PUERTO = "puerto_casa"
        const val CLAVE_DIRECCION = "ble_direccion"
        const val CLAVE_NOMBRE = "ble_nombre"
        const val TIPO_RED_PROPIA = "red_propia"
        const val TIPO_RED_CASA = "red_casa"
        const val TIPO_BLUETOOTH = "bluetooth"
    }
}
