package com.example.satcp.ui.alerta

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Fase
import com.example.satcp.servicio.ResultadoAccion
import com.example.satcp.servicio.enviarAccionAlerta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiAlerta(
    val estado: DatosEstado? = null,
    /** Segundos que faltan para el cierre, descontados localmente entre eventos; null sin cuenta regresiva. */
    val segundosParaCierre: Int? = null,
    val enviando: Boolean = false,
    /** Resultado de `responder` o `rearmar`. */
    val resultado: ResultadoAccion? = null,
    /** Resultado de `cerrar`, aparte para mostrarlo junto a su botón. */
    val resultadoCierre: ResultadoAccion? = null,
)

class AlertaViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val cliente = (aplicacion as SatcpApp).cliente

    private val accion = MutableStateFlow(UiAlerta())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cuentaRegresiva = cliente.estado.flatMapLatest { estado ->
        flow {
            val alerta = estado?.alerta
            if (alerta == null || estado.fase != Fase.ALERTA_USUARIO) {
                emit(null)
                return@flow
            }
            // cierre_en_s llega cada 5 s; entre eventos se descuenta en el teléfono.
            val recibido = SystemClock.elapsedRealtime()
            while (true) {
                val transcurridos = ((SystemClock.elapsedRealtime() - recibido) / 1_000).toInt()
                emit((alerta.cierreEnS - transcurridos).coerceAtLeast(0))
                delay(1_000)
            }
        }
    }

    val ui: StateFlow<UiAlerta> = combine(cliente.estado, cuentaRegresiva, accion) { estado, segundos, accion ->
        accion.copy(estado = estado, segundosParaCierre = segundos)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiAlerta())

    fun responder() = ejecutar(Comando.Responder)

    /** La pantalla lo llama después de que el usuario confirma. */
    fun rearmar() = ejecutar(Comando.Rearmar)

    /** La pantalla lo llama después de que el usuario confirma. La fase cambia con los eventos, no aquí. */
    fun cerrar() = ejecutar(Comando.Cerrar)

    private fun ejecutar(comando: Comando) {
        if (accion.value.enviando) return
        val esCierre = comando == Comando.Cerrar
        accion.update {
            if (esCierre) it.copy(enviando = true, resultadoCierre = null) else it.copy(enviando = true, resultado = null)
        }
        viewModelScope.launch {
            val resultado = cliente.enviarAccionAlerta(comando)
            accion.update {
                when {
                    esCierre -> it.copy(enviando = false, resultadoCierre = resultado)
                    // Al rearmar, el aviso de "ya estaba bloqueado" del cierre deja de tener sentido.
                    comando == Comando.Rearmar -> it.copy(enviando = false, resultado = resultado, resultadoCierre = null)
                    else -> it.copy(enviando = false, resultado = resultado)
                }
            }
        }
    }
}
