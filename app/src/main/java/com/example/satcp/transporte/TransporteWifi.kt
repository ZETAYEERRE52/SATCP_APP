package com.example.satcp.transporte

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.example.satcp.Registro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** A qué red y dirección se conecta el transporte Wi-Fi. */
sealed interface DestinoWifi {
    /** La red `SATCP` del propio ESP32; él queda en `192.168.4.1`. */
    data object RedPropia : DestinoWifi

    /** La red de la casa, con la IP hallada por mDNS o escrita a mano. */
    data class RedCasa(val ip: String, val puerto: Int = 80) : DestinoWifi
}

/** Android no consiguió unirse a la red `SATCP` (el usuario rechazó el diálogo o no está al alcance). */
class RedPropiaNoDisponible : IOException("No se pudo conectar a la red SATCP")

/** Se perdió la red Wi-Fi por la que se hablaba con el ESP32. */
class RedPerdida : IOException("Se perdió la red Wi-Fi")

/** No se pudo llegar al ESP32 en la dirección conocida: está apagado, en otra red o con otra IP. */
class EquipoNoEncontrado(causa: Throwable?) : IOException("No se encuentra el equipo", causa)

/** Falló el WebSocket. */
class WebSocketFallido(causa: Throwable?, codigoHttp: Int?) :
    IOException("WebSocket fallido" + (codigoHttp?.let { " (HTTP $it)" } ?: ""), causa)

/**
 * Cliente HTTP y URL base por los que se llega al ESP32 mientras la conexión está abierta.
 * Lo usan también la descarga de fotos y la vista en vivo, para salir por la misma red.
 */
class AccesoHttp(val cliente: OkHttpClient, val urlBase: String, val host: String) {
    fun url(ruta: String, puerto: Int? = null): String {
        val base = if (puerto == null) urlBase else "http://$host:$puerto"
        return base + if (ruta.startsWith("/")) ruta else "/$ruta"
    }
}

/**
 * WebSocket `ws://<ip>/ws` con OkHttp.
 *
 * - Red propia: pide la red `SATCP` con `WifiNetworkSpecifier` y hace que OkHttp salga por ese
 *   `Network`; si no, Android mandaría el tráfico por datos móviles porque la red no tiene internet.
 * - Red de la casa: OkHttp pide `satcp.local` y [DnsSatcp] lo traduce a la IP conocida. Así el
 *   tráfico sin cifrar se permite solo para `192.168.4.1` y `satcp.local` (network_security_config).
 *   El router puede cambiarle la IP al ESP32: si [buscarEquipo] está definido y no se pudo llegar a
 *   él, se busca de nuevo antes del siguiente intento.
 */
class TransporteWifi(
    contexto: Context,
    destino: DestinoWifi,
    private val clienteBase: OkHttpClient = clientePorDefecto,
    private val buscarEquipo: (suspend () -> DestinoWifi.RedCasa?)? = null,
    /** La clave del equipo, que también es la contraseña de la red `SATCP` (§1b). */
    private val claveRed: String? = null,
) : Transporte {

    override val tipo = TipoTransporte.WIFI

    /** A dónde se conecta ahora; en la red de la casa cambia si el equipo aparece con otra IP. */
    @Volatile var destino: DestinoWifi = destino
        private set

    @Volatile private var falloAlConectar = false

    private val conectividad = contexto.applicationContext.getSystemService(ConnectivityManager::class.java)

    // Pide que el Wi-Fi no entre en ahorro de energía mientras hay conexión con el ESP32. HIGH_PERF es
    // el único modo que puede actuar con la pantalla apagada, pero está obsoleto: según la
    // documentación, Android puede tratarlo como LOW_LATENCY, que solo actúa con la pantalla encendida
    // y la app visible. Por eso este candado ayuda, pero no garantiza la conexión en todos los teléfonos.
    @Suppress("DEPRECATION")
    private val candadoWifi = contexto.applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "satcp:conexion")
        .apply { setReferenceCounted(false) }

    @Volatile private var webSocket: WebSocket? = null

    /** Disponible mientras la conexión está abierta. */
    @Volatile var accesoHttp: AccesoHttp? = null
        private set

    // La red SATCP se pide una sola vez y se mantiene entre reconexiones: liberarla haría que
    // Android se desconecte de ella y vuelva a mostrar el diálogo en cada intento.
    private val bloqueoRed = Any()
    private var callbackRed: ConnectivityManager.NetworkCallback? = null
    private var redPropia = CompletableDeferred<Network>()
    @Volatile private var sesion: ProducerScope<EventoTransporte>? = null

    override fun conectar(): Flow<EventoTransporte> = callbackFlow {
        sesion = this
        // Se mantiene entre reconexiones: es justo cuando la red se cae que no debe dormirse.
        candadoWifi.acquire()
        try {
            if (falloAlConectar) buscarEquipo?.invoke()?.let { destino = it }
            var abierto = false
            val acceso = when (val objetivo = destino) {
                DestinoWifi.RedPropia -> {
                    val red = obtenerRedPropia()
                    AccesoHttp(
                        cliente = clienteBase.newBuilder()
                            .socketFactory(red.socketFactory)
                            .dns(object : Dns {
                                override fun lookup(hostname: String) = red.getAllByName(hostname).toList()
                            })
                            .build(),
                        urlBase = "http://$IP_RED_PROPIA",
                        host = IP_RED_PROPIA,
                    )
                }
                is DestinoWifi.RedCasa -> AccesoHttp(
                    cliente = clienteBase.newBuilder().dns(DnsSatcp(objetivo.ip)).build(),
                    urlBase = "http://$HOST_MDNS:${objetivo.puerto}",
                    host = HOST_MDNS,
                )
            }

            val solicitud = Request.Builder().url(acceso.url(RUTA_WS).replaceFirst("http", "ws")).build()
            webSocket = acceso.cliente.newWebSocket(solicitud, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    abierto = true
                    falloAlConectar = false
                    accesoHttp = acceso
                    trySend(EventoTransporte.Abierto)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    trySend(EventoTransporte.Texto(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(CIERRE_NORMAL, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    falloAlConectar = !abierto
                    close(if (!abierto && response == null) EquipoNoEncontrado(t) else WebSocketFallido(t, response?.code))
                }
            })

            awaitClose()
        } finally {
            // También si se cancela mientras Android todavía se une a la red.
            sesion = null
            webSocket?.cancel()
            webSocket = null
            accesoHttp = null
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun descargar(ruta: String): ByteArray? {
        val acceso = accesoHttp ?: throw SinConexion()
        val url = acceso.url(ruta)
        // Sin conexiones inactivas hacia el ESP32: su servidor tiene pocos sockets, y una conexión
        // olvidada en el pool le quitaría uno al WebSocket o al video.
        val solicitud = Request.Builder().url(url).header("Connection", "close").build()
        return withContext(Dispatchers.IO) {
            val inicio = System.nanoTime()
            fun transcurridoMs() = (System.nanoTime() - inicio) / 1_000_000
            // satcp.local no se resuelve por DNS: DnsSatcp lo traduce a esta IP sin consultar la red.
            val ipReal = when (val objetivo = destino) {
                is DestinoWifi.RedCasa -> objetivo.ip
                DestinoWifi.RedPropia -> IP_RED_PROPIA
            }
            Registro.info(
                "Descarga: GET ${urlSinToken(url)} -> IP $ipReal " +
                    "(conexión ${acceso.cliente.connectTimeoutMillis} ms, lectura ${acceso.cliente.readTimeoutMillis} ms)"
            )
            try {
                acceso.cliente.newCall(solicitud).execute().use { respuesta ->
                    Registro.info("Descarga: HTTP ${respuesta.code} en ${transcurridoMs()} ms (encabezados)")
                    when {
                        respuesta.code == HTTP_NO_ENCONTRADO -> null
                        !respuesta.isSuccessful -> throw ErrorHttp(respuesta.code)
                        else -> (respuesta.body?.bytes() ?: throw IOException("Respuesta sin contenido")).also {
                            Registro.info("Descarga: ${it.size} bytes completos en ${transcurridoMs()} ms")
                        }
                    }
                }
            } catch (e: IOException) {
                val causa = causaDe(e)
                val temporizador = if (causa.tipo == TipoFallo.TIEMPO_AGOTADO) {
                    " -> venció el temporizador de DESCARGA (${e.message})"
                } else {
                    ""
                }
                Registro.aviso("Descarga falló en ${transcurridoMs()} ms: $causa (${e.javaClass.simpleName})$temporizador")
                Log.w(ETIQUETA, "Falló la descarga de ${urlSinToken(url)}: $causa (${e.javaClass.simpleName})")
                throw e
            }
        }
    }

    /** Suelta el candado de Wi-Fi y libera la red SATCP pedida. Se llama cuando el usuario desconecta. */
    override fun cerrar() {
        if (candadoWifi.isHeld) candadoWifi.release()
        synchronized(bloqueoRed) {
            callbackRed?.let { runCatching { conectividad.unregisterNetworkCallback(it) } }
            callbackRed = null
            redPropia.cancel()
        }
    }

    private suspend fun obtenerRedPropia(): Network {
        val espera = synchronized(bloqueoRed) {
            if (callbackRed == null) {
                redPropia = CompletableDeferred()
                val callback = crearCallbackRed()
                callbackRed = callback
                pedirRedPropia(callback)
            }
            redPropia
        }
        return espera.await()
    }

    private fun crearCallbackRed() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(bloqueoRed) {
                if (!redPropia.complete(network)) redPropia = CompletableDeferred(network)
            }
        }

        override fun onLost(network: Network) {
            synchronized(bloqueoRed) {
                if (redPropia.isCompleted) redPropia = CompletableDeferred()
            }
            sesion?.close(RedPerdida())
        }

        override fun onUnavailable() {
            // Android ya liberó la solicitud: el próximo intento la vuelve a hacer.
            synchronized(bloqueoRed) {
                callbackRed = null
                redPropia.completeExceptionally(RedPropiaNoDisponible())
            }
        }
    }

    override suspend fun enviar(texto: String) {
        val ws = webSocket ?: throw SinConexion()
        if (!ws.send(texto)) throw SinConexion()
    }

    private fun pedirRedPropia(callback: ConnectivityManager.NetworkCallback) {
        val solicitud = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contrasenia = claveRed ?: throw RedPropiaNoDisponible()
            solicitud.setNetworkSpecifier(
                WifiNetworkSpecifier.Builder()
                    .setSsid(SSID_RED_PROPIA)
                    .setWpa2Passphrase(contrasenia)
                    .build()
            )
            // Android muestra su diálogo para unirse; onUnavailable llega si el usuario lo rechaza.
            conectividad.requestNetwork(solicitud.build(), callback)
        } else {
            // Antes de Android 10 no hay WifiNetworkSpecifier: el usuario se une a SATCP desde los
            // ajustes y aquí solo se obtiene ese Network para salir por él.
            conectividad.requestNetwork(solicitud.build(), callback, ESPERA_RED_ANTIGUA_MS)
        }
    }

    companion object {
        const val SSID_RED_PROPIA = "SATCP"
        const val IP_RED_PROPIA = "192.168.4.1"
        const val HOST_MDNS = "satcp.local"
        const val RUTA_WS = "/ws"
        private const val CIERRE_NORMAL = 1000
        private const val HTTP_NO_ENCONTRADO = 404
        private const val ESPERA_RED_ANTIGUA_MS = 15_000
        private const val ETIQUETA = "TransporteWifi"

        /** Latido del WebSocket: si el ESP32 no contesta el ping, OkHttp cierra la conexión y se reconecta. */
        const val INTERVALO_PING_S = 15L

        val clientePorDefecto: OkHttpClient = OkHttpClient.Builder()
            // El WebSocket no vuelve al pool; así ninguna descarga deja una conexión inactiva abierta.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(INTERVALO_PING_S, TimeUnit.SECONDS)
            .build()
    }
}

/** Resuelve `satcp.local` a la IP conocida del ESP32 sin consultar ningún DNS. */
class DnsSatcp(private val ip: String) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (hostname == TransporteWifi.HOST_MDNS) listOf(InetAddress.getByName(ip)) else Dns.SYSTEM.lookup(hostname)
}

/** Acepta solo IPv4 escritas en forma numérica, para no lanzar una consulta DNS con texto libre. */
fun esIpv4Valida(texto: String): Boolean {
    val partes = texto.trim().split('.')
    return partes.size == 4 && partes.all { parte ->
        parte.isNotEmpty() && parte.length <= 3 && parte.all(Char::isDigit) && parte.toInt() <= 255
    }
}
