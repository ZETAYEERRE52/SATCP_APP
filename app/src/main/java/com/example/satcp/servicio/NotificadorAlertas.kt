package com.example.satcp.servicio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.satcp.MainActivity
import com.example.satcp.R
import com.example.satcp.protocolo.Alerta
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.OrigenAlerta
import com.example.satcp.ui.alerta.AlertaActivity
import com.example.satcp.ui.comun.recursoConexionCorto
import com.example.satcp.ui.comun.recursoVision

/**
 * Notificaciones de la app: la permanente del servicio y las de alerta, que deben sonar y
 * mostrarse a pantalla completa aunque la interfaz esté cerrada.
 */
class NotificadorAlertas(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val gestor = NotificationManagerCompat.from(this.contexto)

    fun crearCanales() {
        val sistema = contexto.getSystemService(NotificationManager::class.java)

        sistema.createNotificationChannel(
            NotificationChannel(CANAL_VIGILANCIA, contexto.getString(R.string.canal_vigilancia), NotificationManager.IMPORTANCE_LOW)
                .apply { description = contexto.getString(R.string.canal_vigilancia_descripcion) }
        )
        sistema.createNotificationChannel(
            NotificationChannel(CANAL_AVISOS, contexto.getString(R.string.canal_avisos), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = contexto.getString(R.string.canal_avisos_descripcion) }
        )
        sistema.createNotificationChannel(
            NotificationChannel(CANAL_ALERTAS, contexto.getString(R.string.canal_alertas), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = contexto.getString(R.string.canal_alertas_descripcion)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = PATRON_VIBRACION
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        )
    }

    /** La notificación permanente "SATCP vigilando la cocina". */
    fun notificacionVigilancia(conexion: EstadoConexion): Notification =
        NotificationCompat.Builder(contexto, CANAL_VIGILANCIA)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(contexto.getString(R.string.notificacion_vigilando))
            .setContentText(contexto.getString(recursoConexionCorto(conexion)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(intentAbrir(MainActivity::class.java, CODIGO_ABRIR_APP))
            .addAction(0, contexto.getString(R.string.notificacion_detener), ServicioVigilancia.intentDetener(contexto))
            .build()

    fun actualizarVigilancia(conexion: EstadoConexion) = publicar(ID_VIGILANCIA, notificacionVigilancia(conexion))

    /** Muestra, actualiza o quita la alerta según el último evento `estado`. */
    fun procesarEstado(estado: DatosEstado?) {
        val alerta = estado?.alerta
        when {
            alerta == null -> quitarAlertas()
            // Cierre manual u horario: no hay nada que responder, solo avisar del corte.
            alerta.origen.esCierreSinVision -> {
                gestor.cancel(ID_ALERTA)
                publicar(ID_REARME, notificacionCierre(estado.fase, alerta.origen))
            }
            alerta.requiereRearme -> {
                gestor.cancel(ID_ALERTA)
                publicar(ID_REARME, notificacionRearme())
            }
            else -> {
                gestor.cancel(ID_REARME)
                publicar(ID_ALERTA, notificacionAlerta(estado.fase, alerta))
            }
        }
    }

    fun quitarAlertas() {
        gestor.cancel(ID_ALERTA)
        gestor.cancel(ID_REARME)
    }

    /** Sin conexión no llegan alertas, y el usuario debe saberlo aunque no esté mirando la app. */
    fun avisarSinConexion() = publicar(
        ID_SIN_CONEXION,
        NotificationCompat.Builder(contexto, CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(contexto.getString(R.string.notificacion_sin_conexion_titulo))
            .setContentText(contexto.getString(R.string.notificacion_sin_conexion_texto))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contexto.getString(R.string.notificacion_sin_conexion_texto)))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(intentAbrir(MainActivity::class.java, CODIGO_ABRIR_APP))
            .build(),
    )

    fun quitarAvisoSinConexion() = gestor.cancel(ID_SIN_CONEXION)

    /** No se pudo enviar `responder` desde la notificación. */
    fun avisarFalloRespuesta() = publicar(
        ID_FALLO,
        NotificationCompat.Builder(contexto, CANAL_ALERTAS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(contexto.getString(R.string.notificacion_fallo_respuesta))
            .setContentText(contexto.getString(R.string.notificacion_fallo_respuesta_texto))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(intentAbrir(AlertaActivity::class.java, CODIGO_ABRIR_ALERTA))
            .build(),
    )

    private fun notificacionAlerta(fase: Fase, alerta: Alerta): Notification {
        val motivo = contexto.getString(recursoVision(alerta.vision))
        val enCuentaRegresiva = fase == Fase.ALERTA_USUARIO && alerta.cierreEnS > 0
        val texto = if (enCuentaRegresiva) {
            contexto.getString(R.string.notificacion_alerta_texto, motivo, alerta.cierreEnS)
        } else {
            contexto.getString(R.string.notificacion_alerta_sin_cuenta, motivo)
        }
        val pantalla = intentAbrir(AlertaActivity::class.java, CODIGO_ABRIR_ALERTA)
        return NotificationCompat.Builder(contexto, CANAL_ALERTAS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(contexto.getString(R.string.notificacion_alerta_titulo))
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Suena solo al aparecer; con cada evento (cada 5 s) se actualiza en silencio.
            .setOnlyAlertOnce(true)
            .setSilent(!alerta.admiteRespuesta)
            .setContentIntent(pantalla)
            .apply {
                if (alerta.admiteRespuesta) {
                    setFullScreenIntent(pantalla, true)
                    addAction(0, contexto.getString(R.string.alerta_estoy_en_la_cocina), ReceptorAccionAlerta.intentResponder(contexto))
                }
                if (enCuentaRegresiva) {
                    setWhen(System.currentTimeMillis() + alerta.cierreEnS * 1_000L)
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                }
            }
            .build()
    }

    private fun notificacionRearme(): Notification {
        val pantalla = intentAbrir(AlertaActivity::class.java, CODIGO_ABRIR_ALERTA)
        return NotificationCompat.Builder(contexto, CANAL_ALERTAS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(contexto.getString(R.string.notificacion_rearme_titulo))
            .setContentText(contexto.getString(R.string.notificacion_rearme_texto))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contexto.getString(R.string.notificacion_rearme_texto)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(pantalla, true)
            // Rearmar pide confirmación, así que la notificación abre la pantalla en vez de enviarlo.
            .setContentIntent(pantalla)
            .build()
    }

    /**
     * Cierre por horario o equipo que arrancó bloqueado: suena, porque ocurre sin que el usuario esté
     * mirando la app. Cierre manual: silencioso, porque normalmente lo hizo el propio usuario.
     * Ninguno usa pantalla completa: no hay nada urgente que responder.
     */
    private fun notificacionCierre(fase: Fase, origen: OrigenAlerta): Notification {
        val titulo = contexto.getString(
            when (origen) {
                OrigenAlerta.HORARIO -> R.string.cierre_horario
                OrigenAlerta.REINICIO -> R.string.cierre_reinicio
                else -> R.string.cierre_manual
            }
        )
        val texto = contexto.getString(
            if (fase == Fase.CIERRE_AUTOMATICO) R.string.notificacion_cierre_en_curso else R.string.notificacion_rearme_texto
        )
        return NotificationCompat.Builder(contexto, CANAL_ALERTAS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(if (origen == OrigenAlerta.MANUAL) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Suena una sola vez: el paso de CIERRE_AUTOMATICO a BLOQUEADO actualiza en silencio.
            .setOnlyAlertOnce(true)
            .setSilent(origen == OrigenAlerta.MANUAL)
            .setContentIntent(intentAbrir(AlertaActivity::class.java, CODIGO_ABRIR_ALERTA))
            .build()
    }

    private fun intentAbrir(actividad: Class<*>, codigo: Int): PendingIntent =
        PendingIntent.getActivity(
            contexto,
            codigo,
            Intent(contexto, actividad).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission") // Se comprueba en puedeNotificar().
    private fun publicar(id: Int, notificacion: Notification) {
        if (puedeNotificar()) gestor.notify(id, notificacion)
    }

    private fun puedeNotificar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CANAL_VIGILANCIA = "vigilancia"
        const val CANAL_ALERTAS = "alertas"
        const val CANAL_AVISOS = "avisos"
        const val ID_VIGILANCIA = 1
        private const val ID_ALERTA = 2
        private const val ID_REARME = 3
        private const val ID_FALLO = 4
        private const val ID_SIN_CONEXION = 5
        private const val CODIGO_ABRIR_APP = 10
        private const val CODIGO_ABRIR_ALERTA = 11
        private val PATRON_VIBRACION = longArrayOf(0, 800, 400, 800, 400, 800)
    }
}
