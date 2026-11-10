package com.example.satcp.ui.camara

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.satcp.SatcpApp
import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.ConexionCerrada
import com.example.satcp.protocolo.DatosFoto
import com.example.satcp.protocolo.TiempoEsperaAgotado
import com.example.satcp.servicio.AvisoImagen
import com.example.satcp.transporte.CausaFallo
import com.example.satcp.servicio.ResultadoFoto
import com.example.satcp.transporte.SinConexion
import com.example.satcp.transporte.TipoTransporte
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ErrorFoto { CAMARA_OCUPADA, CAMARA_NO_DISPONIBLE, RECHAZADA, SIN_CONEXION, SIN_RESPUESTA, ERROR }

data class UiCamara(
    val tomando: Boolean = false,
    val datos: DatosFoto? = null,
    val imagen: ImageBitmap? = null,
    val avisoImagen: AvisoImagen? = null,
    /** Detalle de red cuando falló la descarga por HTTP. */
    val causaDescarga: CausaFallo? = null,
    /** Llegó un JPEG con CRC correcto que Android no pudo decodificar. */
    val imagenIlegible: Boolean = false,
    val error: ErrorFoto? = null,
    /** Orden de las barras: `clases` de `info`, o las claves de `probabilidades` si aún no llegó. */
    val clases: List<String> = emptyList(),
    val porBluetooth: Boolean = false,
) {
    /** Problemas de transferencia que se resuelven pidiendo la foto otra vez. */
    val sePuedeReintentar: Boolean
        get() = avisoImagen in setOf(
            AvisoImagen.CRC_INVALIDO,
            AvisoImagen.ALTERADA,
            AvisoImagen.INCOMPLETA,
            AvisoImagen.INTERRUMPIDA,
            AvisoImagen.SIN_TERMINAR,
            AvisoImagen.DESCARGA_FALLIDA,
        ) || imagenIlegible
}

/** Toma fotos con `foto`. También lo usa la vista previa por Bluetooth de la pantalla en vivo. */
class CamaraViewModel(aplicacion: Application) : AndroidViewModel(aplicacion) {

    private val cliente = (aplicacion as SatcpApp).cliente
    private val foto = MutableStateFlow(UiCamara())

    val ui: StateFlow<UiCamara> = combine(foto, cliente.info, cliente.transporte) { foto, info, transporte ->
        foto.copy(
            clases = info?.clases?.takeIf { it.isNotEmpty() } ?: foto.datos?.probabilidades?.keys?.toList().orEmpty(),
            porBluetooth = transporte?.tipo == TipoTransporte.BLE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiCamara())

    fun tomarFoto() {
        if (foto.value.tomando) return
        foto.update { it.copy(tomando = true, error = null) }
        viewModelScope.launch {
            val nuevo = try {
                when (val resultado = cliente.tomarFoto()) {
                    is ResultadoFoto.Tomada -> {
                        val imagen = resultado.jpeg?.let { decodificar(it) }
                        UiCamara(
                            datos = resultado.datos,
                            imagen = imagen,
                            avisoImagen = resultado.aviso,
                            causaDescarga = resultado.causa,
                            imagenIlegible = resultado.jpeg != null && imagen == null,
                        )
                    }
                    is ResultadoFoto.Rechazada -> foto.value.copy(error = errorDe(resultado.error))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SinConexion) {
                foto.value.copy(error = ErrorFoto.SIN_CONEXION)
            } catch (e: ConexionCerrada) {
                foto.value.copy(error = ErrorFoto.SIN_CONEXION)
            } catch (e: TiempoEsperaAgotado) {
                foto.value.copy(error = ErrorFoto.SIN_RESPUESTA)
            } catch (e: Exception) {
                foto.value.copy(error = ErrorFoto.ERROR)
            }
            foto.value = nuevo.copy(tomando = false)
        }
    }

    private fun errorDe(codigo: String?) = when (codigo) {
        CodigosError.CAMARA_OCUPADA -> ErrorFoto.CAMARA_OCUPADA
        CodigosError.CAMARA_NO_DISPONIBLE -> ErrorFoto.CAMARA_NO_DISPONIBLE
        else -> ErrorFoto.RECHAZADA
    }

    private suspend fun decodificar(jpeg: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
    }
}
