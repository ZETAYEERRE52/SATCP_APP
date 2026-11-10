package com.example.satcp.servicio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.Comando
import kotlinx.coroutines.launch

/** La acción "Estoy en la cocina" de la notificación: envía `responder` sin abrir la app. */
class ReceptorAccionAlerta : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACCION_RESPONDER) return
        val app = context.applicationContext as SatcpApp
        val pendiente = goAsync()
        app.alcance.launch {
            try {
                when (app.cliente.enviarAccionAlerta(Comando.Responder)) {
                    // Con éxito, el próximo evento estado trae alerta: null y la notificación se va sola.
                    ResultadoAccion.RESPONDIDO, ResultadoAccion.SIN_ALERTA -> Unit
                    else -> NotificadorAlertas(app).avisarFalloRespuesta()
                }
            } finally {
                pendiente.finish()
            }
        }
    }

    companion object {
        private const val ACCION_RESPONDER = "com.example.satcp.RESPONDER"

        fun intentResponder(contexto: Context): PendingIntent = PendingIntent.getBroadcast(
            contexto,
            0,
            Intent(contexto, ReceptorAccionAlerta::class.java).setAction(ACCION_RESPONDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
