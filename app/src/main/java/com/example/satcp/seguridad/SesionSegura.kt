package com.example.satcp.seguridad

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/** Qué resultó de intentar abrir un mensaje recibido. */
sealed interface MensajeAbierto {
    /** Un sobre válido y nuevo; [texto] es el JSON ya descifrado. */
    data class Valido(val texto: String) : MensajeAbierto

    /** No tiene forma de sobre: es un mensaje en claro, que no está autenticado. */
    data object NoEsSobre : MensajeAbierto

    /** Tiene forma de sobre, pero no descifra con esta clave o está alterado. */
    data object NoDescifra : MensajeAbierto

    /** Descifró, pero su contador `n` no es mayor que el último recibido. */
    data object Repetido : MensajeAbierto
}

/**
 * Una sesión cifrada de §1b: dura desde un `hola` hasta el siguiente. Lleva los dos contadores `n`.
 * No es segura entre hilos: la usa [com.example.satcp.servicio.ClienteSatcp] desde un solo hilo.
 */
class SesionSegura(
    private val clave: ByteArray,
    private val crearNonce: () -> ByteArray = Criptografia::nonceAleatorio,
) {

    private var contadorEnvio = 0L
    private var ultimoRecibido = 0L

    /** `true` en cuanto llegó un sobre válido, lo que confirma que las dos partes tienen la misma clave. */
    val confirmada: Boolean get() = ultimoRecibido > 0

    // ---- Datos para el registro de depuración: nunca contienen texto descifrado ----

    /** El `n` del último mensaje enviado. */
    val ultimoNEnviado: Long get() = contadorEnvio

    /** El mayor `n` aceptado hasta ahora. */
    val ultimoNAceptado: Long get() = ultimoRecibido

    /** El `n` que traía el último sobre que se pudo descifrar, aceptado o no; null si no traía. */
    var ultimoNLeido: Long? = null
        private set

    /** Por qué no descifró el último sobre (nombre de la excepción); null si descifró. */
    var motivoUltimoFallo: String? = null
        private set

    /** Agrega el contador `n` a [json] (un objeto JSON) y lo devuelve dentro de un sobre `{"c":...}`. */
    fun sellar(json: String): String {
        val original = Json.parseToJsonElement(json).jsonObject
        val conContador = buildJsonObject {
            put("n", ++contadorEnvio)
            original.forEach { (nombre, valor) -> put(nombre, valor) }
        }
        val datos = Criptografia.cifrar(clave, crearNonce(), conContador.toString().toByteArray(Charsets.UTF_8), Criptografia.AAD_APP)
        return buildJsonObject { put("c", Base64.getEncoder().encodeToString(datos)) }.toString()
    }

    fun abrir(texto: String): MensajeAbierto {
        val sobre = (objetoDe(texto)?.get("c") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return MensajeAbierto.NoEsSobre
        ultimoNLeido = null
        motivoUltimoFallo = null
        val datos = try {
            Base64.getDecoder().decode(sobre)
        } catch (e: IllegalArgumentException) {
            motivoUltimoFallo = "Base64 inválido (${e.javaClass.simpleName})"
            return MensajeAbierto.NoDescifra
        }
        val claro = Criptografia.descifrarConMotivo(clave, datos, Criptografia.AAD_ESP)
            .getOrElse {
                motivoUltimoFallo = it.javaClass.simpleName
                return MensajeAbierto.NoDescifra
            }
            .toString(Charsets.UTF_8)
        val n = (objetoDe(claro)?.get("n") as? JsonPrimitive)?.longOrNull ?: return MensajeAbierto.Repetido
        ultimoNLeido = n
        if (n <= ultimoRecibido) return MensajeAbierto.Repetido
        ultimoRecibido = n
        return MensajeAbierto.Valido(claro)
    }

    /** Descifra la foto recibida por Bluetooth (§6). Devuelve null si está alterada. */
    fun descifrarImagen(datos: ByteArray): ByteArray? = Criptografia.descifrar(clave, datos, Criptografia.AAD_IMAGEN)

    private fun objetoDe(texto: String): JsonObject? = try {
        Json.parseToJsonElement(texto) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
