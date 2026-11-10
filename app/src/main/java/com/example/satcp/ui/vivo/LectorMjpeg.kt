package com.example.satcp.ui.vivo

import com.example.satcp.transporte.AccesoHttp
import com.example.satcp.transporte.ErrorHttp
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.ByteString.Companion.encodeUtf8
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** El ESP32 respondió `503`: ya hay otra persona mirando (§6b). */
class VivoOcupado : ErrorHttp(HTTP_NO_DISPONIBLE)

/**
 * Separa los JPEG de una respuesta `multipart/x-mixed-replace` (§6b). Cada parte empieza con
 * `--<boundary>`, sigue con sus encabezados y una línea vacía, y luego trae `Content-Length` bytes.
 */
class LectorMjpeg(private val fuente: BufferedSource, boundary: String) {

    private val inicioParte = "--$boundary"
    private val finFlujo = "--$boundary--"
    private val separador = "\r\n--$boundary".encodeUtf8()

    /** El siguiente JPEG, o null si la transmisión terminó (el ESP32 cerró la conexión). */
    fun siguienteCuadro(): ByteArray? {
        try {
            // Hasta la línea del boundary (puede haber líneas vacías antes).
            while (true) {
                val linea = fuente.readUtf8LineStrict().trim()
                if (linea == finFlujo) return null
                if (linea == inicioParte) break
            }
            var largo: Long? = null
            while (true) {
                val encabezado = fuente.readUtf8LineStrict()
                if (encabezado.isBlank()) break
                val (nombre, valor) = encabezado.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
                if (nombre.trim().equals("Content-Length", ignoreCase = true)) largo = valor?.trim()?.toLongOrNull()
            }
            return if (largo != null) {
                fuente.readByteArray(largo)
            } else {
                // Sin Content-Length: se lee hasta el próximo boundary.
                val hasta = fuente.indexOf(separador)
                if (hasta < 0) null else fuente.readByteArray(hasta)
            }
        } catch (e: EOFException) {
            return null
        }
    }

    companion object {
        const val BOUNDARY_POR_DEFECTO = "satcpcuadro"

        /** Lee `boundary=` del encabezado Content-Type. */
        fun boundaryDe(contentType: String?): String? = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"')
            ?.removePrefix("--")
            ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Cliente propio del video: no comparte `Dispatcher` ni pool de conexiones con el WebSocket ni con
 * las fotos, y no guarda conexiones inactivas hacia el ESP32. Conserva la red y el DNS de [acceso]
 * (en la red `SATCP` sale por ese `Network`).
 */
fun clienteVideo(acceso: AccesoHttp): OkHttpClient = acceso.cliente.newBuilder()
    .dispatcher(Dispatcher())
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .readTimeout(LECTURA_VIDEO_S, TimeUnit.SECONDS)
    .pingInterval(0, TimeUnit.SECONDS)
    .build()

/**
 * Lee el video de [llamada] sin parar y entrega cada JPEG a [alCuadro]. Es bloqueante: se llama desde
 * `Dispatchers.IO`, y para cortarlo se cancela la llamada. [alCuadro] no debe esperar: la lectura del
 * socket nunca se detiene por la pantalla.
 *
 * Termina normalmente cuando el ESP32 cierra la transmisión (a los 10 minutos). Lanza [VivoOcupado]
 * ante un `503`, [ErrorHttp] ante otro código de error, o la `IOException` de la red.
 */
fun leerVideo(llamada: Call, alLeerBytes: (Long) -> Unit, alCuadro: (ByteArray) -> Unit) {
    llamada.execute().use { respuesta ->
        if (respuesta.code == HTTP_NO_DISPONIBLE) throw VivoOcupado()
        if (!respuesta.isSuccessful) throw ErrorHttp(respuesta.code)
        val cuerpo = respuesta.body ?: throw IOException("Respuesta sin contenido")
        val boundary = LectorMjpeg.boundaryDe(respuesta.header("Content-Type")) ?: LectorMjpeg.BOUNDARY_POR_DEFECTO
        // Cuenta lo que sale del socket, antes de separar cuadros, para medir si la lectura avanza.
        val contada = object : ForwardingSource(cuerpo.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it > 0) alLeerBytes(it) }
        }
        val lector = LectorMjpeg(contada.buffer(), boundary)
        while (true) {
            val cuadro = lector.siguienteCuadro() ?: break
            alCuadro(cuadro)
        }
    }
}

private const val HTTP_NO_DISPONIBLE = 503
private const val LECTURA_VIDEO_S = 15L
