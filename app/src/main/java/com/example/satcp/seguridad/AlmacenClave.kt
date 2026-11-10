package com.example.satcp.seguridad

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La clave del equipo, cifrada con `EncryptedSharedPreferences`. El archivo queda fuera de las copias
 * de seguridad (`backup_rules.xml` y `data_extraction_rules.xml`): su llave no viaja con ellas.
 */
class AlmacenClave(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val prefs: SharedPreferences? = abrirConRecuperacion()

    private val _clave = MutableStateFlow(leer())
    /** Null hasta que el usuario la escribe. */
    val clave: StateFlow<String?> = _clave.asStateFlow()

    fun guardar(clave: String) {
        require(Criptografia.esClaveValida(clave)) { "clave no válida" }
        _clave.value = clave
        prefs?.edit { putString(CLAVE_EQUIPO, clave) }
    }

    private fun leer(): String? = try {
        prefs?.getString(CLAVE_EQUIPO, null)
    } catch (e: Exception) {
        Log.e(ETIQUETA, "No se pudo leer la clave guardada", e)
        null
    }

    /**
     * Si Android perdió la llave, el archivo ya no se puede leer: se borra y la app vuelve a pedir la
     * clave. Si tampoco se puede crear uno nuevo, la clave solo vive en memoria hasta cerrar la app.
     */
    private fun abrirConRecuperacion(): SharedPreferences? = try {
        abrir()
    } catch (e: Exception) {
        Log.e(ETIQUETA, "No se pudo abrir el almacén de la clave", e)
        try {
            contexto.deleteSharedPreferences(ARCHIVO)
            abrir()
        } catch (e2: Exception) {
            Log.e(ETIQUETA, "No se pudo recrear el almacén de la clave", e2)
            null
        }
    }

    @Suppress("DEPRECATION") // Se usa porque así se pidió; está aislada en esta clase por si se reemplaza.
    private fun abrir(): SharedPreferences = EncryptedSharedPreferences.create(
        contexto,
        ARCHIVO,
        MasterKey.Builder(contexto).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val ETIQUETA = "AlmacenClave"
        const val ARCHIVO = "satcp_clave"
        const val CLAVE_EQUIPO = "clave_equipo"
    }
}
