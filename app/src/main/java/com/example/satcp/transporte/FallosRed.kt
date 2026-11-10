package com.example.satcp.transporte

import com.example.satcp.protocolo.TiempoEsperaAgotado
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.net.UnknownServiceException

/** Un `GET` al ESP32 respondió con un código distinto de 2xx. */
open class ErrorHttp(val codigo: Int) : IOException("HTTP $codigo")

enum class TipoFallo {
    /** `403`: el token venció o ya se usó. */
    TOKEN_RECHAZADO,

    /** `503`: el recurso está ocupado (en el video, otra persona mirando). */
    OCUPADO,

    /** `404`. */
    NO_ENCONTRADO,

    /** Otro código HTTP de error. */
    HTTP,

    /** El equipo no respondió a tiempo. */
    TIEMPO_AGOTADO,

    /** No hay camino hasta el equipo: apagado, en otra red o con otra IP. */
    SIN_RUTA,

    /** Android bloqueó el HTTP sin cifrar por `network_security_config`. */
    CLEARTEXT,

    /** Cualquier otra cosa. */
    OTRO,
}

/** La causa real de un fallo de red, para mostrarla al usuario en vez de un mensaje genérico. */
data class CausaFallo(val tipo: TipoFallo, val codigoHttp: Int? = null)

/** Recorre la cadena de causas de [error] hasta reconocer una. */
fun causaDe(error: Throwable): CausaFallo {
    var actual: Throwable? = error
    var vueltas = 0
    while (actual != null && vueltas++ < MAXIMO_CAUSAS) {
        causaDirecta(actual)?.let { return it }
        actual = actual.cause
    }
    return CausaFallo(TipoFallo.OTRO)
}

private fun causaDirecta(error: Throwable): CausaFallo? = when (error) {
    is ErrorHttp -> when (error.codigo) {
        HTTP_PROHIBIDO -> CausaFallo(TipoFallo.TOKEN_RECHAZADO, error.codigo)
        HTTP_NO_DISPONIBLE -> CausaFallo(TipoFallo.OCUPADO, error.codigo)
        HTTP_NO_ENCONTRADO -> CausaFallo(TipoFallo.NO_ENCONTRADO, error.codigo)
        else -> CausaFallo(TipoFallo.HTTP, error.codigo)
    }
    // OkHttp avisa así cuando network_security_config prohíbe el HTTP sin cifrar.
    is UnknownServiceException ->
        if (error.message?.contains("CLEARTEXT", ignoreCase = true) == true) CausaFallo(TipoFallo.CLEARTEXT) else null
    is TiempoEsperaAgotado -> CausaFallo(TipoFallo.TIEMPO_AGOTADO)
    // SocketTimeoutException y el "timeout" de OkHttp heredan de InterruptedIOException.
    is InterruptedIOException -> CausaFallo(TipoFallo.TIEMPO_AGOTADO)
    is NoRouteToHostException, is ConnectException, is UnknownHostException -> CausaFallo(TipoFallo.SIN_RUTA)
    else -> null
}

/**
 * La URL lista para los logs: el token `t` se reemplaza por `…`. El token da acceso a la imagen y
 * no debe quedar en Logcat, que otras herramientas pueden leer.
 */
fun urlSinToken(url: String): String = url.replace(PARAMETRO_TOKEN, "$1…")

private val PARAMETRO_TOKEN = Regex("([?&]t=)[^&#]*")
private const val MAXIMO_CAUSAS = 8
private const val HTTP_PROHIBIDO = 403
private const val HTTP_NO_ENCONTRADO = 404
private const val HTTP_NO_DISPONIBLE = 503
