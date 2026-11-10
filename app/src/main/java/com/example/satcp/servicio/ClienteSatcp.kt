package com.example.satcp.servicio

import android.util.Log
import com.example.satcp.protocolo.CodificadorProtocolo
import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.ConexionCerrada
import com.example.satcp.protocolo.DatosFoto
import com.example.satcp.protocolo.EntregaImagen
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.DatosInfo
import com.example.satcp.protocolo.DatosVivo
import com.example.satcp.protocolo.EmparejadorRespuestas
import com.example.satcp.protocolo.ErrorSeguridad
import com.example.satcp.protocolo.EventoEstado
import com.example.satcp.protocolo.ImagenError
import com.example.satcp.protocolo.ImagenFin
import com.example.satcp.protocolo.ImagenInicio
import com.example.satcp.protocolo.MensajeDesconocido
import com.example.satcp.protocolo.MensajeEntrante
import com.example.satcp.protocolo.MensajeHola
import com.example.satcp.protocolo.MensajeInvalido
import com.example.satcp.protocolo.Respuesta
import com.example.satcp.protocolo.VERSION_PROTOCOLO
import com.example.satcp.seguridad.Criptografia
import com.example.satcp.seguridad.MensajeAbierto
import com.example.satcp.seguridad.SesionSegura
import com.example.satcp.transporte.EquipoNoEncontrado
import com.example.satcp.transporte.EventoTransporte
import com.example.satcp.transporte.RedPropiaNoDisponible
import com.example.satcp.transporte.WebSocketFallido
import com.example.satcp.transporte.ReceptorImagenBle
import com.example.satcp.transporte.ResultadoImagen
import com.example.satcp.transporte.TipoTransporte
import com.example.satcp.transporte.SinConexion
import com.example.satcp.transporte.Transporte
import com.example.satcp.transporte.causaDe
import com.example.satcp.transporte.esperaReconexionMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.example.satcp.Registro
import com.example.satcp.protocolo.TiempoEsperaAgotado

/** Estado del enlace con el ESP32, tal como lo ve el usuario. */
sealed interface EstadoConexion {
    data object Desconectado : EstadoConexion
    data class Conectando(val intento: Int) : EstadoConexion
    data object Conectado : EstadoConexion
    data class Perdida(val motivo: MotivoPerdida, val detalle: String?, val reintentoEnMs: Long) : EstadoConexion

    /** El equipo no aceptó la clave. No se reintenta: hay que pedírsela de nuevo al usuario. */
    data object ClaveRechazada : EstadoConexion
}

enum class MotivoPerdida {
    /** Pasaron [ClienteSatcp.SILENCIO_MAXIMO_MS] sin eventos `estado`. */
    SIN_EVENTOS,

    /** El ESP32 cerró la conexión. */
    CERRADA,

    /** No se pudo llegar al equipo: está apagado, en otra red o con otra IP. */
    NO_ENCONTRADO,

    /** Se cortó una conexión que estaba abierta o el equipo rechazó abrirla. */
    INTERRUMPIDA,

    /** Android no pudo unirse a la red `SATCP`: fuera de alcance, diálogo rechazado o clave incorrecta. */
    RED_NO_DISPONIBLE,

    /** El equipo tiene demasiadas conexiones abiertas (`sin_espacio` en `hola`). */
    SIN_ESPACIO,

    /** El equipo no habla el protocolo de esta app: hay que actualizar el firmware o la app. */
    FIRMWARE_INCOMPATIBLE,

    /** El equipo cerró la sesión cifrada o rechazó su contador (`sin_sesion`, `repetido`). */
    SESION_PERDIDA,

    /** Falló la conexión o no se pudo abrir. */
    ERROR,
}

/** Pasaron 15 s sin un evento `estado`: el latido llega cada 5 s, así que la conexión se perdió. */
class ConexionSinEventos : Exception("Sin eventos de estado")

/** No hay clave guardada, el equipo respondió `no_autorizado` o sus mensajes no descifran con esta clave. */
class ClaveIncorrecta : Exception("El equipo no aceptó la clave")

/** `sin_sesion` o `repetido`: la sesión ya no sirve y hay que abrir otra con `hola`. */
class SesionPerdida(codigo: String) : Exception("Sesión perdida: $codigo")

/** `sin_espacio` en la respuesta a `hola`. */
class EquipoSinEspacio : Exception("El equipo tiene demasiadas conexiones")

/** El equipo rechazó `hola` por no conocerlo, o usa otra versión del protocolo. */
class FirmwareIncompatible : Exception("Protocolo incompatible")

/**
 * Capa de protocolo única sobre cualquier [Transporte]: abre la conexión y la sesión cifrada, las
 * reabre con espera creciente si se pierden, interpreta los mensajes y empareja las respuestas.
 *
 * Seguridad (§1b): en claro solo se aceptan `hola` y los errores de la capa de seguridad, y `hola`
 * solo mientras se espera. Un `estado` o una respuesta en claro se descartan: no están autenticados,
 * y aceptarlos permitiría falsificar una alerta o su ausencia. La `sal` y las `iteraciones` que
 * lleguen en `hola` se ignoran, para que nadie pueda debilitar la derivación de la clave.
 *
 * [alcance] debe ejecutar de a una corrutina a la vez: en la app es un despachador en serie propio,
 * fuera del hilo principal, para que el WebSocket y su vigilante no dependan de la pantalla ni del
 * video. [conectar], [desconectar], [enviar] y [tomarFoto] pasan a ese hilo solos, así que se pueden
 * llamar desde cualquiera; una sesión cancelada no puede pisar el estado de la siguiente.
 *
 * @param leerClave la clave del equipo guardada, o null si todavía no se escribió.
 * @param despachadorDerivacion dónde se calcula la clave maestra, que es lenta a propósito.
 */
class ClienteSatcp(
    private val alcance: CoroutineScope,
    private val leerClave: () -> String? = { null },
    private val despachadorDerivacion: CoroutineDispatcher = Dispatchers.Default,
) {

    private val emparejador = EmparejadorRespuestas()

    /** El hilo de la sesión: el despachador de [alcance]. Todo el estado interno se toca solo aquí. */
    private val hilo: CoroutineContext = alcance.coroutineContext.minusKey(Job)

    // ---- Sesión cifrada (§1b) ----
    private var sesionSegura: SesionSegura? = null
    private var esperaHola: CompletableDeferred<MensajeHola>? = null
    /** La clave maestra se calcula una vez por clave: es lo único lento de abrir la sesión. */
    private var maestra: Pair<String, ByteArray>? = null

    private val _estadoConexion = MutableStateFlow<EstadoConexion>(EstadoConexion.Desconectado)
    val estadoConexion: StateFlow<EstadoConexion> = _estadoConexion.asStateFlow()

    private val _estado = MutableStateFlow<DatosEstado?>(null)
    /** Último evento `estado` recibido; null antes del primero o sin conexión. */
    val estado: StateFlow<DatosEstado?> = _estado.asStateFlow()

    private val _info = MutableStateFlow<DatosInfo?>(null)
    /** Respuesta de `info`, pedida en cada conexión. */
    val info: StateFlow<DatosInfo?> = _info.asStateFlow()

    private val _transporte = MutableStateFlow<Transporte?>(null)
    val transporte: StateFlow<Transporte?> = _transporte.asStateFlow()

    /** Cuenta los eventos `estado` de la sesión actual; la vigilancia de silencio lo observa. */
    private val contadorMensajes = MutableStateFlow(0L)

    /** Cuándo llegó el último mensaje válido ([System.nanoTime]), solo para el registro. */
    @Volatile private var ultimoMensajeNs = 0L

    /** Cuándo llegó el último evento `estado` ([System.nanoTime]); 0 si aún no llega. Para la medición. */
    @Volatile var nanosUltimoEstado = 0L
        private set

    // ---- Foto por Bluetooth (§6) ----
    private val receptorImagen = ReceptorImagenBle()
    private var esperaImagen: CompletableDeferred<ResultadoImagen>? = null
    private val turnoFoto = Mutex()

    /**
     * `true` desde la respuesta de `foto` con `entrega: ble` hasta `imagen_fin`/`imagen_error`.
     * Mientras tanto el ESP32 no atiende otros comandos, así que [enviar] los retiene.
     */
    private val ocupadoPorImagen = MutableStateFlow(false)

    private var trabajo: Job? = null

    /**
     * Empieza a usar [transporte] y lo mantiene conectado hasta [desconectar]. Cierra antes el
     * transporte anterior: un solo transporte activo a la vez. Se puede llamar desde cualquier hilo;
     * el trabajo pasa al hilo de [alcance].
     */
    fun conectar(transporte: Transporte) {
        // De inmediato y sin pasar por Desconectado: el servicio se detendría al verlo.
        _estadoConexion.value = EstadoConexion.Conectando(0)
        alcance.launch {
            liberarActual()
            _transporte.value = transporte
            _estadoConexion.value = EstadoConexion.Conectando(0)
            trabajo = alcance.launch { mantenerConexion(transporte) }
        }
    }

    /** Cierra la conexión. Se puede llamar desde cualquier hilo. */
    fun desconectar() {
        alcance.launch {
            liberarActual()
            _estadoConexion.value = EstadoConexion.Desconectado
        }
    }

    /** Cierra el transporte actual (el WebSocket o el GATT) y olvida su sesión. Solo en el hilo de [alcance]. */
    private fun liberarActual() {
        trabajo?.cancel()
        trabajo = null
        terminarSesion()
        _transporte.value?.cerrar()
        _transporte.value = null
        _estado.value = null
        _info.value = null
        maestra = null
    }

    /**
     * Envía [comando] y espera su respuesta, que puede venir con `ok: false`.
     *
     * @throws SinConexion si no hay conexión abierta.
     * @throws com.example.satcp.protocolo.TiempoEsperaAgotado si no llega a tiempo.
     */
    suspend fun enviar(comando: Comando, tiempoEsperaMs: Long = TIEMPO_ESPERA_MS): Respuesta = withContext(hilo) {
        enviarEnHilo(comando, tiempoEsperaMs)
    }

    private suspend fun enviarEnHilo(comando: Comando, tiempoEsperaMs: Long): Respuesta {
        // Durante una foto por Bluetooth el comando espera a que termine la transferencia.
        if (_transporte.value?.tipo == TipoTransporte.BLE) ocupadoPorImagen.first { !it }
        val actual = _transporte.value
        val sesion = sesionSegura
        if (actual == null || sesion == null || _estadoConexion.value != EstadoConexion.Conectado) throw SinConexion()
        return emparejador.enviarYEsperar(comando, tiempoEsperaMs) { texto ->
            val sobre = sesion.sellar(texto)
            Registro.info(
                "Enviado: cmd=${comando.nombre} id=${idDe(texto)} n=${sesion.ultimoNEnviado} " +
                    "(${sobre.length} caracteres, espera ${tiempoEsperaMs} ms)"
            )
            actual.enviar(sobre)
        }
    }

    /** El `id` de un comando ya codificado, solo para el registro. */
    private fun idDe(texto: String): String = try {
        (Json.parseToJsonElement(texto) as? JsonObject)?.get("id")?.toString() ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /** Pide permiso para abrir el video: cada token sirve una sola vez. Null si el ESP32 lo rechaza. */
    suspend fun pedirVivo(): DatosVivo? {
        val respuesta = enviar(Comando.Vivo)
        if (!respuesta.ok) return null
        return CodificadorProtocolo.datosDe(respuesta, DatosVivo.serializer())
    }

    /**
     * Envía `foto` y obtiene la imagen: por Wi-Fi la descarga de `datos.imagen.url`; por Bluetooth la
     * pide con `"imagen": true` y la arma entre `imagen_inicio` e `imagen_fin`, verificando el CRC-32.
     * Ante `camara_ocupada` reintenta una vez a los 3 s.
     *
     * @throws SinConexion o [com.example.satcp.protocolo.TiempoEsperaAgotado] como [enviar].
     */
    suspend fun tomarFoto(): ResultadoFoto = withContext(hilo) { tomarFotoEnHilo() }

    private suspend fun tomarFotoEnHilo(): ResultadoFoto = turnoFoto.withLock {
        val actual = _transporte.value ?: throw SinConexion()
        val porBle = actual.tipo == TipoTransporte.BLE
        val espera = CompletableDeferred<ResultadoImagen>()
        if (porBle) esperaImagen = espera
        try {
            val comando = Comando.Foto(imagen = porBle)
            var respuesta = enviarFoto(comando)
            if (!respuesta.ok && respuesta.error == CodigosError.CAMARA_OCUPADA) {
                Registro.info("foto: cámara ocupada, se reintenta en $REINTENTO_CAMARA_OCUPADA_MS ms")
                delay(REINTENTO_CAMARA_OCUPADA_MS)
                respuesta = enviarFoto(comando)
            }
            if (!respuesta.ok) return@withLock ResultadoFoto.Rechazada(respuesta.error, respuesta.mensaje)

            val datos = CodificadorProtocolo.datosDe(respuesta, DatosFoto.serializer()) ?: DatosFoto()
            when (datos.imagen?.entrega) {
                EntregaImagen.BLE -> recibirImagenBle(datos, espera)
                EntregaImagen.HTTP -> descargarImagen(actual, datos)
                EntregaImagen.NO_DISPONIBLE -> ResultadoFoto.Tomada(datos, null, AvisoImagen.NO_DISPONIBLE)
                else -> ResultadoFoto.Tomada(datos, null, null)
            }
        } finally {
            if (esperaImagen === espera) esperaImagen = null
            if (porBle) ocupadoPorImagen.value = false
        }
    }

    /** Envía `foto` con su propio temporizador, de [TIEMPO_ESPERA_FOTO_MS] por cualquier transporte. */
    private suspend fun enviarFoto(comando: Comando.Foto): Respuesta = try {
        enviar(comando, TIEMPO_ESPERA_FOTO_MS)
    } catch (e: TiempoEsperaAgotado) {
        Registro.aviso("Venció el temporizador de RESPUESTA del comando foto (${e.milisegundos} ms)")
        throw e
    }

    private suspend fun recibirImagenBle(datos: DatosFoto, espera: CompletableDeferred<ResultadoImagen>): ResultadoFoto {
        val resultado = try {
            withTimeout(TIEMPO_ESPERA_FOTO_MS) { espera.await() }
        } catch (e: TimeoutCancellationException) {
            Registro.aviso("Venció el temporizador de la IMAGEN por Bluetooth ($TIEMPO_ESPERA_FOTO_MS ms)")
            return ResultadoFoto.Tomada(datos, null, AvisoImagen.SIN_TERMINAR)
        }
        val aviso = when (resultado) {
            is ResultadoImagen.Completa -> {
                // El CRC ya se verificó sobre los bytes cifrados; ahora se descifra (§6).
                val jpeg = sesionSegura?.descifrarImagen(resultado.jpeg)
                    ?: return ResultadoFoto.Tomada(datos, null, AvisoImagen.ALTERADA)
                return ResultadoFoto.Tomada(datos, jpeg, null)
            }
            is ResultadoImagen.CrcInvalido -> AvisoImagen.CRC_INVALIDO
            is ResultadoImagen.Incompleta -> AvisoImagen.INCOMPLETA
            is ResultadoImagen.Interrumpida ->
                if (resultado.error == CodigosError.SIN_SUSCRIPCION) AvisoImagen.SIN_SUSCRIPCION else AvisoImagen.INTERRUMPIDA
        }
        return ResultadoFoto.Tomada(datos, null, aviso)
    }

    private suspend fun descargarImagen(transporte: Transporte, datos: DatosFoto): ResultadoFoto {
        // La URL ya trae el token: se usa tal cual y no se registra en los logs.
        val ruta = datos.imagen?.url ?: return ResultadoFoto.Tomada(datos, null, AvisoImagen.DESCARGA_FALLIDA)
        val jpeg = try {
            transporte.descargar(ruta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // El transporte ya registró la URL (sin el token); aquí solo la causa.
            val causa = causaDe(e)
            Log.w(ETIQUETA, "No se pudo descargar la foto: $causa")
            return ResultadoFoto.Tomada(datos, null, AvisoImagen.DESCARGA_FALLIDA, causa)
        }
        return ResultadoFoto.Tomada(datos, jpeg, if (jpeg == null) AvisoImagen.NO_ENCONTRADA else null)
    }

    /** Lo que queda pendiente de una sesión que terminó. */
    private fun terminarSesion() {
        emparejador.cancelarTodos()
        receptorImagen.limpiar()
        esperaImagen?.completeExceptionally(ConexionCerrada())
        esperaImagen = null
        ocupadoPorImagen.value = false
        esperaHola?.completeExceptionally(ConexionCerrada())
        esperaHola = null
        sesionSegura = null
    }

    private suspend fun mantenerConexion(transporte: Transporte) {
        var intento = 0
        while (true) {
            _estadoConexion.value = EstadoConexion.Conectando(intento)
            var funciono = false
            val (motivo, detalle) = try {
                sesion(transporte) { funciono = true }
                MotivoPerdida.CERRADA to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConexionSinEventos) {
                MotivoPerdida.SIN_EVENTOS to null
            } catch (e: ClaveIncorrecta) {
                terminarSesion()
                _estadoConexion.value = EstadoConexion.ClaveRechazada
                return
            } catch (e: RedPropiaNoDisponible) {
                MotivoPerdida.RED_NO_DISPONIBLE to null
            } catch (e: EquipoSinEspacio) {
                MotivoPerdida.SIN_ESPACIO to null
            } catch (e: FirmwareIncompatible) {
                MotivoPerdida.FIRMWARE_INCOMPATIBLE to null
            } catch (e: SesionPerdida) {
                Log.w(ETIQUETA, "Se perdió la sesión cifrada: ${e.message}")
                MotivoPerdida.SESION_PERDIDA to null
            } catch (e: EquipoNoEncontrado) {
                Log.w(ETIQUETA, "No se pudo llegar al equipo", e)
                MotivoPerdida.NO_ENCONTRADO to null
            } catch (e: WebSocketFallido) {
                Log.w(ETIQUETA, "Se interrumpió el WebSocket", e)
                MotivoPerdida.INTERRUMPIDA to null
            } catch (e: Exception) {
                Log.w(ETIQUETA, "Falló la conexión", e)
                MotivoPerdida.ERROR to (e.message ?: e.javaClass.simpleName)
            }
            terminarSesion()
            // Una sesión que llegó a recibir estado vuelve a empezar la espera desde 1 s.
            if (funciono) intento = 0
            val espera = esperaReconexionMs(intento++)
            _estadoConexion.value = EstadoConexion.Perdida(motivo, detalle, espera)
            delay(espera)
        }
    }

    /** Una conexión abierta, de principio a fin. Termina normalmente si el ESP32 la cierra. */
    private suspend fun sesion(transporte: Transporte, alRecibirEstado: () -> Unit) = coroutineScope {
        contadorMensajes.value = 0
        ultimoMensajeNs = System.nanoTime()
        transporte.conectar().collect { evento ->
            when (evento) {
                EventoTransporte.Abierto -> launch {
                    abrirSesion(transporte)
                    _estadoConexion.value = EstadoConexion.Conectado
                    launch { vigilarSilencio() }
                    launch { pedirInfo() }
                }
                is EventoTransporte.Texto -> procesarTexto(evento.texto, alRecibirEstado)
                is EventoTransporte.BytesImagen -> receptorImagen.agregarFragmento(evento.bytes)
            }
        }
        coroutineContext.cancelChildren()
    }

    /**
     * Envía `hola` en claro y espera el reto. La clave maestra se calcula antes de enviarlo: la sesión
     * tiene que existir en cuanto llega la respuesta, porque el primer `estado` cifrado viene justo detrás.
     */
    private suspend fun abrirSesion(transporte: Transporte) {
        val clave = leerClave() ?: throw ClaveIncorrecta()
        if (maestra?.first != clave) {
            maestra = clave to withContext(despachadorDerivacion) { Criptografia.claveMaestra(clave) }
        }

        val espera = CompletableDeferred<MensajeHola>()
        esperaHola = espera
        transporte.enviar(CodificadorProtocolo.codificarHola(ID_HOLA))
        val hola = withTimeoutOrNull(TIEMPO_ESPERA_MS) { espera.await() } ?: throw ConexionSinEventos()
        when {
            hola.error == CodigosError.SIN_ESPACIO -> throw EquipoSinEspacio()
            !hola.ok || hola.protocolo != VERSION_PROTOCOLO -> throw FirmwareIncompatible()
            // recibirHola no creó la sesión: el reto no es válido.
            sesionSegura == null -> throw FirmwareIncompatible()
        }
    }

    /** Crea la sesión aquí, sin suspender, para que ningún sobre llegue antes de que exista. */
    private fun recibirHola(hola: MensajeHola) {
        val espera = esperaHola ?: return
        esperaHola = null
        val reto = hola.reto?.let(Criptografia::bytesDeHex)?.takeIf { it.size == Criptografia.BYTES_RETO }
        val claveMaestra = maestra?.second
        if (hola.ok && hola.protocolo == VERSION_PROTOCOLO && reto != null && claveMaestra != null) {
            sesionSegura = SesionSegura(Criptografia.claveSesion(claveMaestra, reto))
        }
        espera.complete(hola)
    }

    private fun procesarErrorSeguridad(error: ErrorSeguridad) {
        if (error.error == CodigosError.NO_AUTORIZADO) throw ClaveIncorrecta()
        throw SesionPerdida(error.error)
    }

    private suspend fun vigilarSilencio() {
        while (true) {
            val vistos = contadorMensajes.value
            withTimeoutOrNull(SILENCIO_MAXIMO_MS) { contadorMensajes.first { it != vistos } }
                ?: run {
                    val silencioMs = (System.nanoTime() - ultimoMensajeNs) / 1_000_000
                    Registro.aviso(
                        "Venció el VIGILANTE de silencio ($SILENCIO_MAXIMO_MS ms): no llegó ningún mensaje " +
                            "válido en $silencioMs ms; se cierra la conexión y se reconecta"
                    )
                    throw ConexionSinEventos()
                }
        }
    }

    /**
     * Pide `info` y actualiza [info]. Devuelve null si el ESP32 respondió con error.
     *
     * @throws SinConexion o [com.example.satcp.protocolo.TiempoEsperaAgotado] como [enviar].
     */
    suspend fun actualizarInfo(): DatosInfo? {
        val respuesta = enviar(Comando.Info)
        if (!respuesta.ok) return null
        return CodificadorProtocolo.datosDe(respuesta, DatosInfo.serializer()).also { _info.value = it }
    }

    private suspend fun pedirInfo() {
        try {
            actualizarInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ETIQUETA, "No se pudo pedir info", e)
        }
    }

    private fun procesarTexto(texto: String, alRecibirEstado: () -> Unit) {
        val sesion = sesionSegura
        val aceptadoAntes = sesion?.ultimoNAceptado
        val abierto = sesion?.abrir(texto) ?: MensajeAbierto.NoEsSobre
        val detalle = when (abierto) {
            is MensajeAbierto.Valido -> "descifrado ok, n=${sesion?.ultimoNLeido} (último aceptado antes: $aceptadoAntes)"
            MensajeAbierto.NoEsSobre -> "en claro (no es sobre)"
            MensajeAbierto.Repetido ->
                "DESCARTADO por n repetido: n=${sesion?.ultimoNLeido} (último aceptado: $aceptadoAntes)"
            MensajeAbierto.NoDescifra -> "NO DESCIFRA: ${sesion?.motivoUltimoFallo}"
        }
        Registro.info("Recibido: ${texto.length} caracteres, $detalle")
        when (abierto) {
            is MensajeAbierto.Valido -> {
                // Cualquier mensaje autenticado prueba que la conexión vive: reinicia el vigilante.
                ultimoMensajeNs = System.nanoTime()
                contadorMensajes.update { it + 1 }
                decodificarSinLog(abierto.texto)?.let { procesarMensaje(it, alRecibirEstado) }
            }
            MensajeAbierto.NoEsSobre -> decodificarSinLog(texto)?.let(::procesarMensajeEnClaro)
            MensajeAbierto.Repetido -> Log.w(ETIQUETA, "Mensaje repetido descartado")
            MensajeAbierto.NoDescifra -> {
                // Si nunca descifró nada, las dos partes tienen claves distintas; si ya lo hizo, está alterado.
                if (sesion?.confirmada == false) throw ClaveIncorrecta()
                Log.w(ETIQUETA, "Mensaje alterado descartado")
            }
        }
    }

    /** Los mensajes pueden traer datos del equipo, así que su contenido no se escribe en los logs. */
    private fun decodificarSinLog(texto: String): MensajeEntrante? = try {
        CodificadorProtocolo.decodificar(texto)
    } catch (e: MensajeInvalido) {
        Log.w(ETIQUETA, "Mensaje no válido ignorado")
        null
    }

    /** Lo único que se acepta sin autenticar. Todo lo demás se descarta. */
    private fun procesarMensajeEnClaro(mensaje: MensajeEntrante) {
        when {
            mensaje is MensajeHola -> recibirHola(mensaje)
            mensaje is ErrorSeguridad -> procesarErrorSeguridad(mensaje)
            // Un firmware anterior no conoce `hola` y lo rechaza en claro.
            mensaje is Respuesta && mensaje.cmd == CMD_HOLA && !mensaje.ok ->
                esperaHola?.completeExceptionally(FirmwareIncompatible())
            else -> Log.w(ETIQUETA, "Mensaje en claro descartado")
        }
    }

    private fun procesarMensaje(mensaje: MensajeEntrante, alRecibirEstado: () -> Unit) {
        when (mensaje) {
            is ErrorSeguridad -> procesarErrorSeguridad(mensaje)
            is MensajeHola -> Unit
            is Respuesta -> {
                // Se marca aquí, antes de despertar a quien espera, para que ningún comando se cuele.
                // Solo si alguien espera la imagen: una respuesta tardía no debe dejar los comandos retenidos.
                if (esperaImagen != null && mensaje.ok && mensaje.cmd == Comando.Foto().nombre && entregaPorBle(mensaje)) {
                    ocupadoPorImagen.value = true
                }
                val emparejada = emparejador.entregar(mensaje)
                Registro.info(
                    "Mensaje: tipo=respuesta cmd=${mensaje.cmd} id=${mensaje.id} ok=${mensaje.ok} " +
                        "error=${mensaje.error} " +
                        if (emparejada) "-> emparejada con el comando pendiente" else "-> NADIE la esperaba (llegó tarde o id desconocido)"
                )
            }
            is EventoEstado -> {
                _estado.value = mensaje.datos
                nanosUltimoEstado = System.nanoTime()
                Registro.info("Evento estado: fase=${mensaje.datos.fase}")
                alRecibirEstado()
            }
            is ImagenInicio -> receptorImagen.iniciar(mensaje)
            is ImagenFin -> {
                receptorImagen.finalizar(mensaje.id)?.let { esperaImagen?.complete(it) }
                ocupadoPorImagen.value = false
            }
            is ImagenError -> {
                esperaImagen?.complete(receptorImagen.interrumpir(mensaje.id, mensaje.error))
                ocupadoPorImagen.value = false
            }
            is MensajeDesconocido -> Unit
        }
    }

    private fun entregaPorBle(respuesta: Respuesta): Boolean = try {
        CodificadorProtocolo.datosDe(respuesta, DatosFoto.serializer())?.imagen?.entrega == EntregaImagen.BLE
    } catch (e: SerializationException) {
        false
    }

    companion object {
        const val TIEMPO_ESPERA_MS = 10_000L
        /** Respuesta de `foto` por cualquier transporte, y llegada de la imagen por Bluetooth. */
        const val TIEMPO_ESPERA_FOTO_MS = 30_000L
        const val REINTENTO_CAMARA_OCUPADA_MS = 3_000L
        const val SILENCIO_MAXIMO_MS = 15_000L
        private const val ID_HOLA = 1L
        private const val CMD_HOLA = "hola"
        private const val ETIQUETA = "ClienteSatcp"
    }
}
