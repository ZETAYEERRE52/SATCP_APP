package com.example.satcp.ui.horarios

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.CodificadorProtocolo
import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.ConexionCerrada
import com.example.satcp.protocolo.DatosHorarios
import com.example.satcp.protocolo.DiasSemana
import com.example.satcp.protocolo.Horario
import com.example.satcp.protocolo.Respuesta
import com.example.satcp.protocolo.TiempoEsperaAgotado
import com.example.satcp.transporte.SinConexion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ErrorHorarios { SIN_ESPACIO, PARAMETROS_INVALIDOS, FIRMWARE_ANTIGUO, SIN_CONEXION, SIN_RESPUESTA, RECHAZADO }

/** Horario que se está creando ([indice] null) o editando en el formulario. */
data class EdicionHorario(
    val indice: Int?,
    val hora: Int,
    val minuto: Int,
    val dias: Int,
    val activo: Boolean,
)

data class UiHorarios(
    val cargando: Boolean = false,
    /** Lo que quedó en el ESP32 según el último `horarios`. */
    val datos: DatosHorarios? = null,
    /** Hay un `horario_guardar` o `horario_borrar` en curso. */
    val enviando: Boolean = false,
    val error: ErrorHorarios? = null,
    /** Formulario abierto; null si está cerrado. */
    val edicion: EdicionHorario? = null,
    /** Horario a borrar, esperando confirmación. */
    val porBorrar: Horario? = null,
)

/** Pide, guarda y borra horarios. Después de cada cambio vuelve a pedir `horarios`. */
class HorariosViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val cliente = (aplicacion as SatcpApp).cliente

    private val _ui = MutableStateFlow(UiHorarios())
    val ui: StateFlow<UiHorarios> = _ui.asStateFlow()

    /** La pantalla lo llama al mostrarse. */
    fun cargar() {
        if (_ui.value.cargando) return
        _ui.update { it.copy(cargando = true) }
        viewModelScope.launch {
            val error = try {
                val respuesta = cliente.enviar(Comando.Horarios)
                if (respuesta.ok) {
                    val datos = CodificadorProtocolo.datosDe(respuesta, DatosHorarios.serializer()) ?: DatosHorarios()
                    _ui.update { it.copy(datos = datos) }
                    null
                } else {
                    errorDe(respuesta)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallaDe(e)
            }
            // Un error de un cambio se sigue mostrando tras recargar; uno de conexión anterior, no.
            _ui.update { actual ->
                val anterior = actual.error.takeUnless { it == ErrorHorarios.SIN_CONEXION || it == ErrorHorarios.SIN_RESPUESTA }
                actual.copy(cargando = false, error = error ?: anterior)
            }
        }
    }

    fun nuevo() = _ui.update {
        it.copy(edicion = EdicionHorario(null, HORA_INICIAL, 0, DiasSemana.TODOS, activo = true), error = null)
    }

    fun editar(horario: Horario) = _ui.update {
        it.copy(edicion = EdicionHorario(horario.indice, horario.hora, horario.minuto, horario.dias, horario.activo), error = null)
    }

    fun cancelarEdicion() = _ui.update { it.copy(edicion = null) }

    /** Guarda el formulario: sin `indice` si es nuevo, con `indice` si se edita. Se cierra solo si salió bien. */
    fun guardarEdicion(hora: Int, minuto: Int, dias: Int) {
        val edicion = _ui.value.edicion ?: return
        if (dias == 0) return
        enviarCambio(Comando.HorarioGuardar(edicion.indice, hora, minuto, dias, edicion.activo), cerrarEdicion = true)
    }

    /** El interruptor: los mismos datos con `activo` cambiado. */
    fun cambiarActivo(horario: Horario, activo: Boolean) = enviarCambio(
        Comando.HorarioGuardar(horario.indice, horario.hora, horario.minuto, horario.dias, activo),
        cerrarEdicion = false,
    )

    fun pedirBorrar(horario: Horario) = _ui.update { it.copy(porBorrar = horario) }

    fun cancelarBorrar() = _ui.update { it.copy(porBorrar = null) }

    fun confirmarBorrar() {
        val horario = _ui.value.porBorrar ?: return
        _ui.update { it.copy(porBorrar = null) }
        enviarCambio(Comando.HorarioBorrar(horario.indice), cerrarEdicion = false)
    }

    fun olvidarError() = _ui.update { it.copy(error = null) }

    private fun enviarCambio(comando: Comando, cerrarEdicion: Boolean) {
        if (_ui.value.enviando) return
        _ui.update { it.copy(enviando = true, error = null) }
        viewModelScope.launch {
            val error = try {
                val respuesta = cliente.enviar(comando)
                if (respuesta.ok) null else errorDe(respuesta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallaDe(e)
            }
            _ui.update {
                it.copy(enviando = false, error = error, edicion = if (cerrarEdicion && error == null) null else it.edicion)
            }
            // Pase lo que pase, se muestra lo que quedó en el ESP32.
            cargar()
        }
    }

    private fun errorDe(respuesta: Respuesta) = when (respuesta.error) {
        CodigosError.SIN_ESPACIO -> ErrorHorarios.SIN_ESPACIO
        CodigosError.PARAMETROS_INVALIDOS -> ErrorHorarios.PARAMETROS_INVALIDOS
        CodigosError.COMANDO_DESCONOCIDO -> ErrorHorarios.FIRMWARE_ANTIGUO
        else -> ErrorHorarios.RECHAZADO
    }

    private fun fallaDe(e: Exception) = when (e) {
        is SinConexion, is ConexionCerrada -> ErrorHorarios.SIN_CONEXION
        is TiempoEsperaAgotado -> ErrorHorarios.SIN_RESPUESTA
        else -> ErrorHorarios.RECHAZADO
    }

    private companion object {
        const val HORA_INICIAL = 22
    }
}
