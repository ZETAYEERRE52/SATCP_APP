package com.example.satcp.servicio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Una red que el usuario ya configuró desde la app. [clave] es null si la red es abierta. */
@Serializable
data class RedGuardada(val ssid: String, val clave: String?) {
    // Evita que la contraseña termine en un log por imprimir el objeto.
    override fun toString() = "RedGuardada($ssid)"
}

/** Agrega [red] al principio; si ya había una con ese nombre, la reemplaza. */
fun List<RedGuardada>.conRed(red: RedGuardada): List<RedGuardada> = listOf(red) + filterNot { it.ssid == red.ssid }

fun List<RedGuardada>.sinRed(ssid: String): List<RedGuardada> = filterNot { it.ssid == ssid }

private val serializadorRedes = ListSerializer(RedGuardada.serializer())
private val jsonRedes = Json { ignoreUnknownKeys = true }

fun codificarRedes(redes: List<RedGuardada>): String = jsonRedes.encodeToString(serializadorRedes, redes)

/** Un contenido que no se puede interpretar equivale a no tener redes guardadas. */
fun decodificarRedes(texto: String): List<RedGuardada> = try {
    jsonRedes.decodeFromString(serializadorRedes, texto)
} catch (_: IllegalArgumentException) {
    emptyList()
}

/**
 * Redes que el usuario configuró desde la app, para volver a enviarlas al ESP32 con un toque.
 * Android no deja leer las redes guardadas del teléfono, así que esta lista es propia de la app.
 *
 * Se guardan cifradas con una llave de Android Keystore, y el archivo queda fuera de las copias de
 * seguridad (`backup_rules.xml` y `data_extraction_rules.xml`).
 */
class AlmacenRedes(contexto: Context) {

    private val prefs = contexto.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    private val _redes = MutableStateFlow(leer())
    val redes: StateFlow<List<RedGuardada>> = _redes.asStateFlow()

    fun guardar(red: RedGuardada) = actualizar(_redes.value.conRed(red))

    fun borrar(ssid: String) = actualizar(_redes.value.sinRed(ssid))

    private fun actualizar(redes: List<RedGuardada>) {
        _redes.value = redes
        try {
            val cifrado = cifrar(codificarRedes(redes))
            prefs.edit { putString(CLAVE_REDES, cifrado) }
        } catch (e: Exception) {
            // La red ya se envió al equipo; solo no quedará recordada tras cerrar la app.
            Log.e(ETIQUETA, "No se pudieron guardar las redes", e)
        }
    }

    private fun leer(): List<RedGuardada> {
        val cifrado = prefs.getString(CLAVE_REDES, null) ?: return emptyList()
        return try {
            decodificarRedes(descifrar(cifrado))
        } catch (e: Exception) {
            // Por ejemplo, si Android perdió la llave: sin ella estos datos ya no se pueden leer.
            Log.e(ETIQUETA, "No se pudieron leer las redes guardadas", e)
            emptyList()
        }
    }

    private fun cifrar(texto: String): String {
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.ENCRYPT_MODE, llave())
        return Base64.encodeToString(cifrador.iv + cifrador.doFinal(texto.toByteArray()), Base64.NO_WRAP)
    }

    private fun descifrar(texto: String): String {
        val datos = Base64.decode(texto, Base64.NO_WRAP)
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(BITS_ETIQUETA, datos, 0, BYTES_IV))
        return String(cifrador.doFinal(datos, BYTES_IV, datos.size - BYTES_IV))
    }

    private fun llave(): SecretKey {
        val almacen = KeyStore.getInstance(PROVEEDOR).apply { load(null) }
        (almacen.getKey(ALIAS_LLAVE, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS_LLAVE, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(BITS_LLAVE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ETIQUETA = "AlmacenRedes"
        const val ARCHIVO = "satcp_redes"
        const val CLAVE_REDES = "redes"
        const val PROVEEDOR = "AndroidKeyStore"
        const val ALIAS_LLAVE = "satcp_redes"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val BITS_LLAVE = 256
        const val BITS_ETIQUETA = 128
        const val BYTES_IV = 12
    }
}
