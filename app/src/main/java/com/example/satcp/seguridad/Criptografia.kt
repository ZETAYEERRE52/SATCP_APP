package com.example.satcp.seguridad

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Operaciones de §1b. No dependen de Android, así que se prueban con los vectores del protocolo. */
object Criptografia {

    /** Datos asociados (AAD): impiden reenviar un mensaje en el sentido contrario o como imagen. */
    const val AAD_APP = "app"
    const val AAD_ESP = "esp"
    const val AAD_IMAGEN = "img"

    const val BYTES_NONCE = 12
    const val BYTES_ETIQUETA = 16
    const val BYTES_RETO = 16

    private const val SAL = "SATCP-clave-v1"
    private const val ITERACIONES = 10_000
    private const val BITS_CLAVE = 256
    private const val PREFIJO_SESION = "SATCP-sesion"

    private val azar = SecureRandom()

    /** De 8 a 63 caracteres ASCII imprimibles: también es la contraseña WPA2 de la red `SATCP`. */
    fun esClaveValida(clave: String): Boolean = clave.length in 8..63 && clave.all { it in ' '..'~' }

    fun claveMaestra(claveEquipo: String): ByteArray {
        val especificacion = PBEKeySpec(claveEquipo.toCharArray(), SAL.toByteArray(Charsets.UTF_8), ITERACIONES, BITS_CLAVE)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(especificacion).encoded
        } finally {
            especificacion.clearPassword()
        }
    }

    fun claveSesion(claveMaestra: ByteArray, reto: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(claveMaestra, "HmacSHA256"))
        mac.update(PREFIJO_SESION.toByteArray(Charsets.UTF_8))
        return mac.doFinal(reto)
    }

    fun nonceAleatorio(): ByteArray = ByteArray(BYTES_NONCE).also(azar::nextBytes)

    /** Devuelve `nonce || cifrado || etiqueta`. */
    fun cifrar(clave: ByteArray, nonce: ByteArray, claro: ByteArray, aad: String): ByteArray {
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(BYTES_ETIQUETA * 8, nonce))
        cifrador.updateAAD(aad.toByteArray(Charsets.US_ASCII))
        return nonce + cifrador.doFinal(claro)
    }

    /** Descifra `nonce || cifrado || etiqueta`. Devuelve null si los datos están alterados o la clave no es la correcta. */
    fun descifrar(clave: ByteArray, datos: ByteArray, aad: String): ByteArray? =
        descifrarConMotivo(clave, datos, aad).getOrNull()

    /**
     * Como [descifrar], pero si falla conserva la excepción (por ejemplo `AEADBadTagException`) para
     * poder registrar por qué. Solo falla con [GeneralSecurityException] o datos demasiado cortos.
     */
    fun descifrarConMotivo(clave: ByteArray, datos: ByteArray, aad: String): Result<ByteArray> {
        if (datos.size < BYTES_NONCE + BYTES_ETIQUETA) {
            return Result.failure(GeneralSecurityException("Datos demasiado cortos: ${datos.size} bytes"))
        }
        return try {
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
            cifrador.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(clave, "AES"),
                GCMParameterSpec(BYTES_ETIQUETA * 8, datos, 0, BYTES_NONCE),
            )
            cifrador.updateAAD(aad.toByteArray(Charsets.US_ASCII))
            Result.success(cifrador.doFinal(datos, BYTES_NONCE, datos.size - BYTES_NONCE))
        } catch (e: GeneralSecurityException) {
            Result.failure(e)
        }
    }

    /** Convierte un texto hexadecimal en bytes; null si no es hexadecimal válido. */
    fun bytesDeHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val alto = Character.digit(hex[i * 2], 16)
            val bajo = Character.digit(hex[i * 2 + 1], 16)
            if (alto < 0 || bajo < 0) return null
            bytes[i] = (alto * 16 + bajo).toByte()
        }
        return bytes
    }
}
