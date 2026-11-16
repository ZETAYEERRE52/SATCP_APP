package com.example.satcp.servicio

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.satcp.SatcpApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano de tipo `connectedDevice`. El ESP32 no tiene servidor en la nube: las
 * alertas solo llegan mientras la conexión sigue abierta, y este servicio impide que Android cierre
 * el proceso con la interfaz en segundo plano. También es quien lanza la notificación de alerta.
 *
 * La conexión en sí la lleva [ClienteSatcp] (compartido con las pantallas); el servicio vive
 * mientras el cliente no esté desconectado y se detiene solo cuando el usuario desconecta.
 */
class ServicioVigilancia : Service() {

    private val alcance = MainScope()
    private var observacion: Job? = null
    private lateinit var notificador: NotificadorAlertas
    private val candadoReintento by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "satcp:reintento")
            .apply { setReferenceCounted(false) }
    }

    private val cliente get() = (application as SatcpApp).cliente

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificador = NotificadorAlertas(this)
        notificador.crearCanales()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            cliente.desconectar()
            detener()
            return START_NOT_STICKY
        }
        if (!entrarEnPrimerPlano()) return START_NOT_STICKY
        if (observacion == null) observacion = alcance.launch { observarCliente() }
        return START_NOT_STICKY
    }

    private fun entrarEnPrimerPlano(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificadorAlertas.ID_VIGILANCIA,
            notificador.notificacionVigilancia(cliente.estadoConexion.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        true
    } catch (e: Exception) {
        // Android puede negarse (p. ej. si se intenta iniciar desde segundo plano).
        Log.e(ETIQUETA, "No se pudo iniciar el servicio en primer plano", e)
        stopSelf()
        false
    }

    private suspend fun observarCliente() {
        alcance.launch {
            cliente.estado.collect { notificador.procesarEstado(it) }
        }
        alcance.launch {
            // Se espera un poco: una reconexión de pocos segundos no merece un aviso.
            cliente.estadoConexion.map { it == EstadoConexion.Conectado }.distinctUntilChanged().collectLatest { conectado ->
                if (conectado) {
                    notificador.quitarAvisoSinConexion()
                } else {
                    delay(ESPERA_AVISO_SIN_CONEXION_MS)
                    notificador.avisarSinConexion()
                }
            }
        }
        alcance.launch {
            // Con la pantalla apagada la CPU duerme y la pausa entre intentos se estira a minutos.
            // Mientras se reintenta se mantiene despierta, con un tope por cada corte.
            cliente.estadoConexion.map { it is EstadoConexion.Perdida || it is EstadoConexion.Conectando }
                .distinctUntilChanged()
                .collect { reintentando ->
                    if (reintentando) {
                        candadoReintento.acquire(TOPE_CANDADO_REINTENTO_MS)
                    } else if (candadoReintento.isHeld) {
                        candadoReintento.release()
                    }
                }
        }
        cliente.estadoConexion.collect { conexion ->
            if (conexion == EstadoConexion.Desconectado) detener() else notificador.actualizarVigilancia(conexion)
        }
    }

    private fun detener() {
        alcance.coroutineContext.cancelChildren()
        if (candadoReintento.isHeld) candadoReintento.release()
        notificador.quitarAlertas()
        notificador.quitarAvisoSinConexion()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        alcance.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACCION_DETENER = "com.example.satcp.DETENER"
        private const val ETIQUETA = "ServicioVigilancia"
        private const val ESPERA_AVISO_SIN_CONEXION_MS = 60_000L

        /** Tiempo máximo con la CPU despierta por cada corte de conexión. */
        private const val TOPE_CANDADO_REINTENTO_MS = 2 * 60_000L

        /** Lo llama la interfaz al conectar, con la app en primer plano. */
        fun iniciar(contexto: Context) {
            ContextCompat.startForegroundService(contexto, Intent(contexto, ServicioVigilancia::class.java))
        }

        fun intentDetener(contexto: Context): PendingIntent = PendingIntent.getService(
            contexto,
            0,
            Intent(contexto, ServicioVigilancia::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
