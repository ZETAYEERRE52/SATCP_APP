package com.example.satcp.transporte

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.satcp.Registro
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

// DIAGNÓSTICO TEMPORAL de la demora al conectar (etiqueta SATCP_CONEXION). Solo se usa en
// compilaciones de depuración; se quita al cerrar la investigación.

/**
 * Crea sockets que registran cuánto tarda la conexión TCP. OkHttp no avisa de los pasos de un
 * WebSocket a su EventListener, así que se mide aquí. Con [red] el socket sale por esa red, igual
 * que con `Network.getSocketFactory()`.
 */
class FabricaSocketsDiagnostico(private val red: Network?) : SocketFactory() {
    override fun createSocket(): Socket = SocketDiagnostico(red)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

private class SocketDiagnostico(private val red: Network?) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        red?.bindSocket(this)
        val inicio = System.nanoTime()
        fun ms() = (System.nanoTime() - inicio) / 1_000_000
        Registro.conexion("TCP: conectando a $endpoint (máximo $timeout ms${if (red != null) ", atado a la red $red" else ", red por defecto"})")
        try {
            super.connect(endpoint, timeout)
            Registro.conexion("TCP: CONECTADO a $endpoint en ${ms()} ms (dirección local $localSocketAddress)")
        } catch (e: IOException) {
            Registro.conexion("TCP: FALLÓ hacia $endpoint en ${ms()} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}

/**
 * Describe las redes del teléfono y si [ipDestino] cae en la subred del Wi-Fi. Si la red por defecto
 * no es ese Wi-Fi (p. ej. datos móviles porque el Wi-Fi "no tiene internet", o una VPN), un socket
 * sin atar sale por ella y nunca llega al ESP32.
 */
@Suppress("DEPRECATION") // allNetworks: solo para el diagnóstico.
fun describirRedes(conectividad: ConnectivityManager, ipDestino: String?): String {
    val porDefecto = conectividad.activeNetwork
    val partes = conectividad.allNetworks.map { red ->
        val capacidades = conectividad.getNetworkCapabilities(red)
        val tipo = when {
            capacidades == null -> "?"
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "DATOS"
            else -> "OTRA"
        }
        val validada = capacidades?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val enlaces = conectividad.getLinkProperties(red)?.linkAddresses.orEmpty()
        val ips = enlaces.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" }
        val contieneDestino = ipDestino != null && enlaces.any { (it.address as? Inet4Address)?.let { local -> mismaSubredIpv4(local, it.prefixLength, ipDestino) } == true }
        buildString {
            append(tipo)
            if (red == porDefecto) append("(POR DEFECTO)")
            append(" validada=").append(validada)
            append(" ips=[").append(ips).append(']')
            if (contieneDestino) append(" <- contiene $ipDestino")
        }
    }
    return if (partes.isEmpty()) "sin redes" else partes.joinToString(" | ")
}
