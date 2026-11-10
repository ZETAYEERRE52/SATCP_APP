package com.example.satcp.transporte

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Un SATCP anunciado en la red de la casa. */
data class ServicioSatcp(val nombre: String, val ip: String, val puerto: Int)

class DescubrimientoFallido(codigo: Int) : Exception("No se pudo buscar en la red (código $codigo)")

/** Busca el servicio `_satcp._tcp` por mDNS (§1). */
class DescubridorNsd(contexto: Context) {

    private val nsd = contexto.applicationContext.getSystemService(NsdManager::class.java)

    /** Emite la lista de equipos encontrados cada vez que cambia. Al dejar de recolectar se detiene la búsqueda. */
    fun descubrir(): Flow<List<ServicioSatcp>> = callbackFlow {
        val encontrados = LinkedHashMap<String, ServicioSatcp>()
        val porResolver = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        fun publicar() = trySend(synchronized(encontrados) { encontrados.values.toList() })

        val oyente = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                porResolver.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(encontrados) { encontrados.remove(serviceInfo.serviceName) }
                publicar()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(DescubrimientoFallido(errorCode))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        publicar()
        nsd.discoverServices(TIPO_SERVICIO, NsdManager.PROTOCOL_DNS_SD, oyente)

        // NsdManager resuelve un servicio a la vez: se resuelven en fila.
        launch {
            for (info in porResolver) {
                val servicio = resolver(info) ?: continue
                synchronized(encontrados) { encontrados[servicio.nombre] = servicio }
                publicar()
            }
        }

        awaitClose {
            porResolver.close()
            runCatching { nsd.stopServiceDiscovery(oyente) }
        }
    }

    /**
     * Busca durante [esperaMs] y devuelve el equipo solo si hay exactamente uno: con varios SATCP en
     * la red no se puede saber cuál era el del usuario, así que no se cambia de equipo por él.
     */
    suspend fun buscarUnico(esperaMs: Long = ESPERA_BUSQUEDA_MS): ServicioSatcp? {
        var vistos = emptyList<ServicioSatcp>()
        try {
            withTimeoutOrNull(esperaMs) { descubrir().collect { vistos = it } }
        } catch (_: DescubrimientoFallido) {
            return null
        }
        return vistos.singleOrNull()
    }

    @Suppress("DEPRECATION") // resolveService y host siguen funcionando; la API nueva exige Android 14.
    private suspend fun resolver(info: NsdServiceInfo): ServicioSatcp? = suspendCancellableCoroutine { continuacion ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                continuacion.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val direccion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceInfo.hostAddresses.firstOrNull { it is Inet4Address } ?: serviceInfo.hostAddresses.firstOrNull()
                } else {
                    serviceInfo.host
                }
                continuacion.resume(
                    direccion?.hostAddress?.let { ServicioSatcp(serviceInfo.serviceName, it, serviceInfo.port) }
                )
            }
        })
    }

    private companion object {
        const val TIPO_SERVICIO = "_satcp._tcp"
        const val ESPERA_BUSQUEDA_MS = 4_000L
    }
}
