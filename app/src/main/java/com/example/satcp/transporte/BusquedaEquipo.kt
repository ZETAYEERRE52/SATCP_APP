package com.example.satcp.transporte

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.satcp.BuildConfig
import com.example.satcp.Registro
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Prueba a la vez cada candidato que llega por [candidatos] (sin repetir [clave]) y devuelve el
 * primero para el que [probar] da `true`; los demás se cancelan. `null` si ninguno responde en
 * [limiteMs] o si se acaban los candidatos sin éxito.
 */
suspend fun <T> primeroQueResponda(
    candidatos: Flow<T>,
    limiteMs: Long,
    clave: (T) -> Any = { it as Any },
    probar: suspend (T) -> Boolean,
): T? = withTimeoutOrNull(limiteMs) {
    channelFlow {
        val vistos = HashSet<Any>()
        candidatos.collect { candidato ->
            if (vistos.add(clave(candidato))) launch { if (probar(candidato)) send(candidato) }
        }
    }.firstOrNull()
}

/**
 * Pregunta `GET /info` (en claro, §1) a [ip]: responde `dispositivo: "SATCP"` solo si de verdad es un
 * SATCP. Se pide `satcp.local` y [DnsSatcp] lo traduce a [ip], porque el tráfico sin cifrar solo se
 * permite hacia ese nombre. Con [red], la consulta sale por esa red.
 */
suspend fun esSatcp(base: OkHttpClient, ip: String, puerto: Int, red: Network?, limiteMs: Long): Boolean {
    val cliente = base.newBuilder()
        .dns(DnsSatcp(ip))
        .apply {
            when {
                BuildConfig.DEBUG -> socketFactory(FabricaSocketsDiagnostico(red))
                red != null -> socketFactory(red.socketFactory)
            }
        }
        .connectTimeout(limiteMs, TimeUnit.MILLISECONDS)
        .readTimeout(limiteMs, TimeUnit.MILLISECONDS)
        .callTimeout(limiteMs, TimeUnit.MILLISECONDS)
        .build()
    val solicitud = Request.Builder()
        .url("http://${TransporteWifi.HOST_MDNS}:$puerto$RUTA_INFO")
        .header("Connection", "close")
        .build()
    val inicio = System.nanoTime()
    fun ms() = (System.nanoTime() - inicio) / 1_000_000
    return try {
        val texto = cliente.newCall(solicitud).esperar().use { respuesta ->
            if (!respuesta.isSuccessful) throw ErrorHttp(respuesta.code)
            respuesta.body?.string().orEmpty()
        }
        val dispositivo = (Json.parseToJsonElement(texto) as? JsonObject)?.get("dispositivo") as? JsonPrimitive
        val ok = dispositivo?.content == NOMBRE_DISPOSITIVO
        Registro.conexion("Candidato $ip:$puerto: /info respondió en ${ms()} ms -> ${if (ok) "es un SATCP" else "NO es un SATCP"}")
        ok
    } catch (e: IOException) {
        Registro.conexion("Candidato $ip:$puerto: sin respuesta en ${ms()} ms (${causaDe(e)}: ${e.message})")
        false
    } catch (e: IllegalArgumentException) { // JSON inválido
        Registro.conexion("Candidato $ip:$puerto: /info no es JSON válido")
        false
    }
}

/** La llamada de OkHttp como función suspendida que se cancela con la corrutina. */
private suspend fun Call.esperar(): Response = suspendCancellableCoroutine { continuacion ->
    continuacion.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuacion.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            continuacion.resumeWith(Result.failure(e))
        }
    })
}

/**
 * La red Wi-Fi por la que se llega a [ip]: la que tiene esa IP en su subred o, si ninguna, cualquier
 * Wi-Fi. Atar el socket a ella evita que Android lo mande por datos móviles cuando el Wi-Fi de la casa
 * "no tiene internet" y deja de ser la red por defecto.
 */
@Suppress("DEPRECATION") // allNetworks: no hay otra forma sin registrar un callback por cada red.
fun redWifiPara(conectividad: ConnectivityManager, ip: String): Network? {
    val wifis = conectividad.allNetworks.filter {
        conectividad.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    return wifis.firstOrNull { red ->
        conectividad.getLinkProperties(red)?.linkAddresses.orEmpty().any { enlace ->
            val local = enlace.address
            local is Inet4Address && mismaSubredIpv4(local, enlace.prefixLength, ip)
        }
    } ?: wifis.firstOrNull()
}

/** `true` si [ip] está en la subred `local/prefijo`. */
fun mismaSubredIpv4(local: Inet4Address, prefijo: Int, ip: String): Boolean {
    if (!esIpv4Valida(ip) || prefijo !in 0..32) return false
    val destino = ip.trim().split('.').fold(0L) { acc, parte -> (acc shl 8) or parte.toLong() }
    val origen = local.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
    val mascara = if (prefijo == 0) 0L else (0xFFFFFFFFL shl (32 - prefijo)) and 0xFFFFFFFFL
    return (destino and mascara) == (origen and mascara)
}

private const val RUTA_INFO = "/info"
private const val NOMBRE_DISPOSITIVO = "SATCP"
