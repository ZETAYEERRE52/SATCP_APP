package com.example.satcp.protocolo

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** El comando codificado supera [LIMITE_BYTES_COMANDO]; el ESP32 cerraría la conexión. */
class ComandoDemasiadoLargo(bytes: Int) :
    IllegalArgumentException("El comando ocupa $bytes bytes (máximo $LIMITE_BYTES_COMANDO)")

/** El texto recibido no es un mensaje del protocolo. */
class MensajeInvalido(texto: String, causa: Throwable? = null) :
    Exception("Mensaje inválido: $texto", causa)

/**
 * Traduce entre los objetos de la app y el JSON del protocolo. No sabe nada del transporte:
 * el mismo texto viaja por WebSocket o por Bluetooth (allí con un `\n` al final, que agrega el transporte).
 */
object CodificadorProtocolo {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // Un valor de enum que esta versión no conoce cae en el valor por defecto (DESCONOCIDA...).
        coerceInputValues = true
    }

    fun codificar(comando: Comando, id: Long): String {
        val objeto = buildJsonObject {
            put("cmd", comando.nombre)
            put("id", id)
            when (comando) {
                is Comando.Foto -> if (comando.imagen) put("imagen", true)
                is Comando.Wifi -> {
                    put("ssid", comando.ssid)
                    if (!comando.clave.isNullOrEmpty()) put("clave", comando.clave)
                }
                is Comando.HorarioGuardar -> {
                    comando.indice?.let { put("indice", it) }
                    put("hora", comando.hora)
                    put("minuto", comando.minuto)
                    put("dias", comando.dias)
                    put("activo", comando.activo)
                }
                is Comando.HorarioBorrar -> put("indice", comando.indice)
                is Comando.CambiarClave -> put("clave_nueva", comando.claveNueva)
                Comando.Info, Comando.Estado, Comando.Responder, Comando.Rearmar,
                Comando.Cerrar, Comando.Horarios, Comando.Vivo -> Unit
            }
        }
        val texto = objeto.toString()
        val bytes = texto.toByteArray(Charsets.UTF_8).size
        if (bytes > LIMITE_BYTES_COMANDO) throw ComandoDemasiadoLargo(bytes)
        return texto
    }

    /** El único mensaje que la app envía en claro (§1b). */
    fun codificarHola(id: Long): String = buildJsonObject {
        put("cmd", "hola")
        put("id", id)
    }.toString()

    /** Interpreta un mensaje completo (una trama de WebSocket o una línea de TX sin el `\n`). */
    fun decodificar(texto: String): MensajeEntrante {
        val objeto = try {
            json.parseToJsonElement(texto).jsonObject
        } catch (e: SerializationException) {
            throw MensajeInvalido(texto, e)
        } catch (e: IllegalArgumentException) {
            throw MensajeInvalido(texto, e)
        }
        return try {
            when (objeto.texto("tipo")) {
                "respuesta" -> decodificarRespuesta(objeto, texto)
                "evento" -> decodificarEvento(objeto, texto)
                "hola" -> MensajeHola(
                    ok = (objeto["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    protocolo = (objeto["protocolo"] as? JsonPrimitive)?.intOrNull,
                    reto = objeto.texto("reto"),
                    error = objeto.texto("error"),
                )
                else -> MensajeDesconocido(texto)
            }
        } catch (e: SerializationException) {
            throw MensajeInvalido(texto, e)
        }
    }

    /** Interpreta los `datos` de una respuesta según el comando, p. ej. `datosDe(r, DatosFoto.serializer())`. */
    fun <T> datosDe(respuesta: Respuesta, estrategia: DeserializationStrategy<T>): T? =
        respuesta.datos?.let { json.decodeFromJsonElement(estrategia, it) }

    private fun decodificarRespuesta(objeto: JsonObject, texto: String): MensajeEntrante {
        objeto.texto("error")?.takeIf { it in ERRORES_SEGURIDAD }?.let { return ErrorSeguridad(it) }
        val cmd = objeto.texto("cmd") ?: throw MensajeInvalido(texto)
        return Respuesta(
            cmd = cmd,
            id = objeto.numero("id"),
            ok = (objeto["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
            datos = objeto["datos"] as? JsonObject,
            error = objeto.texto("error"),
            mensaje = objeto.texto("mensaje"),
        )
    }

    private fun decodificarEvento(objeto: JsonObject, texto: String): MensajeEntrante =
        when (objeto.texto("evento")) {
            "estado" -> {
                val datos = objeto["datos"] as? JsonObject ?: throw MensajeInvalido(texto)
                EventoEstado(json.decodeFromJsonElement(DatosEstado.serializer(), datos))
            }
            "imagen_inicio" -> ImagenInicio(
                id = objeto.numero("id") ?: throw MensajeInvalido(texto),
                bytes = (objeto["bytes"] as? JsonPrimitive)?.intOrNull ?: throw MensajeInvalido(texto),
                crc32 = objeto.numero("crc32") ?: throw MensajeInvalido(texto),
                fragmento = (objeto["fragmento"] as? JsonPrimitive)?.intOrNull,
            )
            "imagen_fin" -> ImagenFin(objeto.numero("id") ?: throw MensajeInvalido(texto))
            "imagen_error" -> ImagenError(
                id = objeto.numero("id") ?: throw MensajeInvalido(texto),
                error = objeto.texto("error"),
            )
            else -> MensajeDesconocido(texto)
        }

    private val ERRORES_SEGURIDAD = setOf(CodigosError.NO_AUTORIZADO, CodigosError.SIN_SESION, CodigosError.REPETIDO)

    private fun JsonObject.texto(clave: String): String? = primitivo(this[clave])?.contentOrNull

    /** Los `id` pueden ser número o string (§3); la app siempre envía números. */
    private fun JsonObject.numero(clave: String): Long? = primitivo(this[clave])?.longOrNull

    private fun primitivo(elemento: JsonElement?): JsonPrimitive? =
        (elemento as? JsonPrimitive)?.takeUnless { it is JsonNull }
}
