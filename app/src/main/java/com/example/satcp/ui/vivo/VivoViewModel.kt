package com.example.satcp.ui.vivo

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.Registro
import com.example.satcp.SatcpApp
import com.example.satcp.transporte.AccesoHttp
import com.example.satcp.transporte.CausaFallo
import com.example.satcp.transporte.ErrorHttp
import com.example.satcp.transporte.SinConexion
import com.example.satcp.transporte.TipoFallo
import com.example.satcp.transporte.TransporteWifi
import com.example.satcp.transporte.causaDe
import com.example.satcp.transporte.urlSinToken
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

enum class EstadoVivo {
    DETENIDO,

    /** El usuario la paró: no vuelve a arrancar sola hasta que él la inicie. */
    PARADO,
    CONECTANDO,
    MOSTRANDO,

    /** `503`: otra persona está mirando. */
    OCUPADO,

    /** El ESP32 cerró la transmisión (a los 10 minutos) o se cortó: se ofrece reanudar. */
    TERMINADO,
    ERROR,

    /** No hay conexión Wi-Fi abierta con el equipo. */
    SIN_WIFI,
}

/** Lo medido en el último segundo de video. */
data class MedidaVivo(
    val kilobytesPorSegundo: Float,
    val leidosPorSegundo: Int,
    val mostradosPorSegundo: Int,
    /** Descartados desde que se abrió el video, porque llegó otro antes de decodificarlos. */
    val descartados: Long,
    /** Segundos desde el último evento `estado`; null si aún no llega ninguno. */
    val segundosDesdeEstado: Float?,
)

/** Todo lo de la pantalla salvo el cuadro, que va aparte en [VivoViewModel.cuadro]. */
data class UiVivo(
    val estado: EstadoVivo = EstadoVivo.DETENIDO,
    val medida: MedidaVivo? = null,
    /** Por qué no se pudo abrir o se cortó, en los estados ERROR y TERMINADO. */
    val causa: CausaFallo? = null,
    /** Mostrar la medición sobre el video: solo en compilaciones de depuración. */
    val depuracion: Boolean = false,
)

/** El equipo no dio permiso para abrir el video (respuesta de `vivo` con `ok: false`). */
private class SinPermisoVivo : IOException("El equipo no dio permiso para abrir el video")

/**
 * Vista en vivo. Tres corrutinas por transmisión, sin nada compartido con el WebSocket:
 * - Lectura en `Dispatchers.IO`, con su propio cliente OkHttp ([clienteVideo]). Nunca espera a la pantalla.
 * - Decodificación en `Dispatchers.Default`, desde un canal de capacidad 1 que descarta el cuadro
 *   viejo si llega otro. Publica en [cuadro].
 * - Medición cada segundo, en el hilo principal a propósito: si este se atrasa, lo delata.
 */
class VivoViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val cliente = (aplicacion as SatcpApp).cliente
    private val esDepuracion = aplicacion.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val _ui = MutableStateFlow(UiVivo(depuracion = esDepuracion))
    val ui: StateFlow<UiVivo> = _ui.asStateFlow()

    private val _cuadro = MutableStateFlow<ImageBitmap?>(null)
    /** El último cuadro decodificado. La pantalla solo observa esto. */
    val cuadro: StateFlow<ImageBitmap?> = _cuadro.asStateFlow()

    private var transmision: Job? = null

    // Quita el ahorro de energía del Wi-Fi mientras se ve el video, que retrasa la llegada de los
    // cuadros. Este modo solo actúa con la pantalla encendida y la app visible, justo como aquí. Cuenta
    // referencias: al parar y reiniciar rápido, soltar el de la transmisión vieja no afecta a la nueva.
    private val candadoVideo: WifiManager.WifiLock? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            aplicacion.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "satcp:video")
        } else {
            null
        }

    /** La pantalla lo llama al mostrarse y al volver del segundo plano. */
    fun alMostrarse() {
        if (_ui.value.estado != EstadoVivo.PARADO) iniciar()
    }

    fun iniciar() {
        if (transmision?.isActive == true) return
        val acceso = (cliente.transporte.value as? TransporteWifi)?.accesoHttp
        if (acceso == null) {
            _ui.update { it.copy(estado = EstadoVivo.SIN_WIFI, medida = null, causa = null) }
            return
        }
        _ui.update { it.copy(estado = EstadoVivo.CONECTANDO, medida = null, causa = null) }

        transmision = viewModelScope.launch {
            candadoVideo?.acquire()
            var url: String? = null
            val (fin, causa) = try {
                // Un 403 al abrir suele ser un token que venció mientras se pedía: se pide otro, una vez.
                var intentos = 0
                while (true) {
                    url = urlConTokenNuevo(acceso)
                    if (transmitir(acceso, url)) break
                    if (++intentos > REINTENTOS_TOKEN) throw ErrorHttp(HTTP_PROHIBIDO)
                }
                EstadoVivo.TERMINADO to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: VivoOcupado) {
                EstadoVivo.OCUPADO to null
            } catch (e: SinConexion) {
                EstadoVivo.SIN_WIFI to null
            } catch (e: Exception) {
                // Al parar, la llamada cancelada lanza IOException: no es un fallo.
                ensureActive()
                val causa = causaDe(e)
                Log.w(ETIQUETA, "Se cortó la vista en vivo: $causa (${e.javaClass.simpleName}) en ${url?.let(::urlSinToken)}")
                Registro.vivo("Se cortó la vista en vivo: $causa (${e.javaClass.simpleName})")
                // Si ya se estaba mostrando, es un corte: se ofrece reanudar igual que a los 10 minutos.
                val estado = if (_ui.value.estado == EstadoVivo.MOSTRANDO) EstadoVivo.TERMINADO else EstadoVivo.ERROR
                estado to causa.takeIf { e !is SinPermisoVivo }
            } finally {
                candadoVideo?.release()
            }
            _ui.update { it.copy(estado = fin, medida = null, causa = causa) }
        }
    }

    /** El token sirve una sola vez y vale 30 s: se pide uno cada vez que se abre o se reanuda. */
    private suspend fun urlConTokenNuevo(acceso: AccesoHttp): String {
        val permiso = cliente.pedirVivo()
        val ruta = permiso?.url ?: throw SinPermisoVivo()
        val puerto = permiso.puerto ?: cliente.info.value?.wifi?.puertoVivo ?: PUERTO_VIVO_POR_DEFECTO
        return acceso.url(ruta, puerto)
    }

    /**
     * Una transmisión, hasta que el ESP32 la cierre. Devuelve `false` si el equipo rechazó el token con
     * `403` antes del primer cuadro, para que se pida otro.
     */
    private suspend fun transmitir(acceso: AccesoHttp, url: String): Boolean = coroutineScope {
        val llamada = clienteVideo(acceso).newCall(Request.Builder().url(url).build())
        val cuadros = Channel<ByteArray>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val medicion = Medicion()
        val primerCuadro = AtomicBoolean(false)

        // La lectura bloquea un hilo de IO y no ve la cancelación: cancelar la llamada la corta.
        val cierre = launch {
            try {
                awaitCancellation()
            } finally {
                llamada.cancel()
            }
        }
        val medidor = launch { medirCadaSegundo(medicion) }
        val decodificador = launch(Dispatchers.Default) {
            for (jpeg in cuadros) {
                medicion.entregados.incrementAndGet()
                val imagen = decodificar(jpeg) ?: continue
                medicion.mostrados.incrementAndGet()
                _cuadro.value = imagen
                if (primerCuadro.compareAndSet(false, true)) {
                    _ui.update { it.copy(estado = EstadoVivo.MOSTRANDO, causa = null) }
                }
            }
        }

        try {
            withContext(Dispatchers.IO) {
                leerVideo(
                    llamada,
                    alLeerBytes = { medicion.bytes.addAndGet(it) },
                    alCuadro = { jpeg ->
                        medicion.leidos.incrementAndGet()
                        cuadros.trySend(jpeg) // Con DROP_OLDEST nunca espera: reemplaza el cuadro pendiente.
                    },
                )
            }
        } catch (e: IOException) {
            ensureActive()
            if (!primerCuadro.get() && causaDe(e).tipo == TipoFallo.TOKEN_RECHAZADO) {
                Log.w(ETIQUETA, "El equipo rechazó el token del video (403) en ${urlSinToken(url)}; se pide otro")
                return@coroutineScope false
            }
            throw e
        } finally {
            cuadros.close()
            medidor.cancel()
            cierre.cancel()
        }
        decodificador.join()
        true
    }

    /** Contadores compartidos entre el hilo de lectura, el decodificador y el medidor. */
    private class Medicion {
        val bytes = AtomicLong()
        val leidos = AtomicInteger()
        val entregados = AtomicInteger()
        val mostrados = AtomicInteger()
        var descartadosTotal = 0L
    }

    /**
     * Cada segundo: KB/s leídos del socket, cuadros leídos, mostrados y descartados, tiempo desde el
     * último `estado` y atraso del hilo principal. Va a Logcat (`SATCP_VIVO`) y a la pantalla.
     */
    private suspend fun medirCadaSegundo(m: Medicion) {
        var esperado = SystemClock.elapsedRealtime() + 1_000
        while (true) {
            delay(1_000)
            val ahora = SystemClock.elapsedRealtime()
            val atraso = ahora - esperado
            esperado = ahora + 1_000

            val bytes = m.bytes.getAndSet(0)
            val leidos = m.leidos.getAndSet(0)
            val entregados = m.entregados.getAndSet(0)
            val mostrados = m.mostrados.getAndSet(0)
            m.descartadosTotal += (leidos - entregados).coerceAtLeast(0)
            val ultimoEstado = cliente.nanosUltimoEstado
            val desdeEstado = if (ultimoEstado == 0L) null else (System.nanoTime() - ultimoEstado) / 1e9f

            val medida = MedidaVivo(bytes / 1024f, leidos, mostrados, m.descartadosTotal, desdeEstado)
            _ui.update { it.copy(medida = medida) }
            Registro.vivo(
                "%.1f KB/s, %d cuadros leídos/s, %d mostrados/s, %d descartados en total; último estado hace %s; hilo principal atrasado %d ms"
                    .format(medida.kilobytesPorSegundo, leidos, mostrados, m.descartadosTotal,
                        desdeEstado?.let { "%.1f s".format(it) } ?: "—", atraso)
            )
        }
    }

    /** El botón "Parar": cierra la conexión del video, lo que libera al ESP32 para otro espectador. */
    fun parar() {
        transmision?.cancel()
        transmision = null
        _ui.update { it.copy(estado = EstadoVivo.PARADO, medida = null, causa = null) }
    }

    /** Cierra la conexión del video. Se llama al salir de la pantalla o al pasar a segundo plano. */
    fun detener() {
        transmision?.cancel()
        transmision = null
        _ui.update {
            val estado = if (it.estado == EstadoVivo.PARADO) EstadoVivo.PARADO else EstadoVivo.DETENIDO
            it.copy(estado = estado, medida = null, causa = null)
        }
    }

    override fun onCleared() = detener()

    /** RGB_565 usa la mitad de memoria por cuadro; para encuadrar la cámara no hace falta más color. */
    private fun decodificar(jpeg: ByteArray): ImageBitmap? {
        val opciones = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opciones)?.asImageBitmap()?.also { it.prepareToDraw() }
    }

    private companion object {
        const val PUERTO_VIVO_POR_DEFECTO = 81
        /** Cuántas veces se pide otro token si el equipo responde 403 antes del primer cuadro. */
        const val REINTENTOS_TOKEN = 1
        const val HTTP_PROHIBIDO = 403
        const val ETIQUETA = "VivoViewModel"
    }
}
