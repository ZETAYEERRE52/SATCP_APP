package com.example.satcp

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.satcp.seguridad.AlmacenClave
import com.example.satcp.servicio.AlmacenBitacora
import com.example.satcp.servicio.AlmacenRedes
import com.example.satcp.servicio.AnalizadorBitacora
import com.example.satcp.servicio.EventoBitacora
import com.example.satcp.servicio.TipoEventoBitacora
import com.example.satcp.servicio.ClienteSatcp
import com.example.satcp.servicio.EstadoConexion
import com.example.satcp.servicio.Preferencias
import com.example.satcp.servicio.ServicioVigilancia
import com.example.satcp.servicio.UltimaConexion
import com.example.satcp.transporte.DescubridorNsd
import com.example.satcp.transporte.DestinoWifi
import com.example.satcp.transporte.EscanerBle
import com.example.satcp.transporte.TransporteBle
import com.example.satcp.transporte.TransporteWifi
import com.example.satcp.transporte.esIpv4Valida
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Objetos que comparten las pantallas y el servicio durante toda la vida del proceso. */
class SatcpApp : Application() {

    val alcance = MainScope()
    val almacenClave by lazy { AlmacenClave(this) }
    /**
     * El cliente del protocolo corre en su propio hilo en serie, fuera del hilo principal: así el
     * WebSocket, el descifrado y el vigilante de 15 s no dependen de la pantalla ni del video.
     */
    val cliente by lazy {
        val hiloCliente = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1, "ClienteSatcp"))
        ClienteSatcp(hiloCliente, leerClave = { almacenClave.clave.value })
    }
    val preferencias by lazy { Preferencias(this) }
    val redes by lazy { AlmacenRedes(this) }
    val descubridor by lazy { DescubridorNsd(this) }
    val escaner by lazy { EscanerBle(this) }
    val bitacora by lazy { AlmacenBitacora(java.io.File(filesDir, AlmacenBitacora.NOMBRE_ARCHIVO), alcance) }

    /** La conexión automática al abrir la app se hace una sola vez por proceso. */
    private var yaSeConectoSola = false

    fun crearTransporteWifi(destino: DestinoWifi) = TransporteWifi(
        this,
        destino,
        candidatos = if (destino is DestinoWifi.RedCasa) ::candidatosEnCasa else null,
        alCambiarDestino = { nuevo -> preferencias.ultimaConexion = UltimaConexion.RedCasa(nuevo.ip, nuevo.puerto) },
        claveRed = almacenClave.clave.value,
    )

    /**
     * Conecta con el modo elegido y lo recuerda como el modo configurado. Un solo transporte a la vez:
     * [ClienteSatcp.conectar] cierra el anterior (Wi-Fi y Bluetooth comparten la antena del ESP32).
     */
    fun conectarCon(modo: UltimaConexion) {
        preferencias.ultimaConexion = modo
        if (modo is UltimaConexion.Bluetooth) preferencias.equipoBle = modo
        val transporte = when (modo) {
            UltimaConexion.RedPropia -> crearTransporteWifi(DestinoWifi.RedPropia)
            is UltimaConexion.RedCasa -> crearTransporteWifi(DestinoWifi.RedCasa(modo.ip, modo.puerto))
            is UltimaConexion.Bluetooth -> TransporteBle(this, modo.direccion, modo.nombre)
        }
        Registro.conexion("conectarCon(): modo $modo")
        cliente.conectar(transporte)
        ServicioVigilancia.iniciar(this)
    }

    /** Rehace la conexión actual con la clave guardada: suelta la red `SATCP` y vuelve a unirse. */
    fun reconectar() {
        val nuevo = when (val actual = cliente.transporte.value) {
            is TransporteWifi -> crearTransporteWifi(actual.destino)
            is TransporteBle -> TransporteBle(this, actual.direccion, actual.nombre)
            else -> return
        }
        cliente.conectar(nuevo)
    }

    /**
     * La app pasó a primer plano. Si se está reintentando, intenta ya. Si no hay conexión, la primera
     * vez por proceso se conecta sola con el modo configurado ([puedeUsarBluetooth]: hay permisos y el
     * Bluetooth está encendido; si no, el modo Bluetooth espera a que el usuario lo resuelva).
     */
    fun alAbrirApp(puedeUsarBluetooth: Boolean) {
        when (cliente.estadoConexion.value) {
            is EstadoConexion.Perdida, is EstadoConexion.Conectando -> cliente.reintentarAhora()
            EstadoConexion.Desconectado -> {
                if (yaSeConectoSola || almacenClave.clave.value == null) return
                val modo = preferencias.ultimaConexion ?: return
                if (modo is UltimaConexion.Bluetooth && !puedeUsarBluetooth) return
                yaSeConectoSola = true
                Registro.conexion("Al abrir la app: conexión automática con $modo")
                conectarCon(modo)
            }
            else -> Unit
        }
    }

    /**
     * Aplica la clave nueva tras `cambiar_clave` (§4). Un segundo después el equipo cierra todas las
     * sesiones y cambia la contraseña de la red `SATCP`, así que se espera a que lo haga antes de reconectar.
     */
    fun aplicarClaveNueva(clave: String) {
        almacenClave.guardar(clave)
        alcance.launch {
            delay(ESPERA_CAMBIO_CLAVE_MS)
            reconectar()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Guarda la IP de la casa que informa el equipo, aunque la conexión sea por SATCP o Bluetooth.
        alcance.launch {
            cliente.estado
                .map { it?.conexion?.wifiCasaIp?.trim() }
                .filterNotNull()
                .filter(::esIpv4Valida)
                .distinctUntilChanged()
                .collect { preferencias.ipCasaInformada = it }
        }
        avisarCambiosDeRed()
        llevarBitacora()
    }

    /**
     * La bitácora se arma con cada `estado` recibido y con los cortes de conexión. Todo corre en el
     * hilo principal ([alcance]), así el analizador no se toca desde dos hilos.
     */
    private fun llevarBitacora() {
        val analizador = AnalizadorBitacora()
        alcance.launch {
            cliente.eventosEstado.collect { estado ->
                bitacora.agregar(analizador.analizar(estado, System.currentTimeMillis()))
            }
        }
        alcance.launch {
            var anterior: EstadoConexion = EstadoConexion.Desconectado
            cliente.estadoConexion.collect { conexion ->
                val ahora = System.currentTimeMillis()
                val tipo = when {
                    conexion == EstadoConexion.Conectado && anterior != EstadoConexion.Conectado ->
                        TipoEventoBitacora.CONEXION_RECUPERADA
                    anterior == EstadoConexion.Conectado && conexion == EstadoConexion.Desconectado ->
                        TipoEventoBitacora.DESCONECTADO
                    anterior == EstadoConexion.Conectado -> TipoEventoBitacora.CONEXION_PERDIDA
                    else -> null
                }
                if (conexion != EstadoConexion.Conectado) analizador.reiniciar()
                anterior = conexion
                val motivo = (conexion as? EstadoConexion.Perdida)?.motivo?.name
                tipo?.let { bitacora.agregar(listOf(EventoBitacora(ahora, it, motivoPerdida = motivo))) }
            }
        }
    }

    /**
     * Cuando aparece un Wi-Fi o cambia su dirección, no tiene sentido esperar la pausa entre intentos:
     * se reintenta ya. Con conexión abierta no hace nada.
     */
    private fun avisarCambiosDeRed() {
        val conectividad = getSystemService(ConnectivityManager::class.java) ?: return
        val solicitud = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        conectividad.registerNetworkCallback(solicitud, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Registro.conexion("Android: Wi-Fi disponible ($network)")
                cliente.reintentarAhora()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Registro.conexion("Android: cambió la dirección del Wi-Fi ($network)")
                cliente.reintentarAhora()
            }
        })
    }

    /**
     * Dónde más puede estar el equipo en la red de la casa: la última IP que él mismo informó y lo
     * que anuncie por mDNS. De mDNS solo se toma un equipo mientras se vea uno solo: con varios SATCP
     * en la red no se sabe cuál es el del usuario (uno ajeno no abriría la sesión sin la clave).
     */
    private fun candidatosEnCasa(): Flow<DestinoWifi.RedCasa> = flow {
        preferencias.ipCasaInformada?.let { emit(DestinoWifi.RedCasa(it)) }
        descubridor.descubrir().collect { vistos ->
            Registro.conexion("mDNS: ${vistos.size} equipo(s): ${vistos.joinToString { "${it.nombre}=${it.ip}:${it.puerto}" }}")
            vistos.singleOrNull()?.let { emit(DestinoWifi.RedCasa(it.ip, it.puerto)) }
        }
    }.catch { error -> Registro.conexion("mDNS: la búsqueda falló: ${error.message}") }

    private companion object {
        const val ESPERA_CAMBIO_CLAVE_MS = 2_000L
    }
}
