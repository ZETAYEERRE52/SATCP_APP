package com.example.satcp.servicio

import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.ConexionCerrada
import com.example.satcp.protocolo.TiempoEsperaAgotado
import com.example.satcp.transporte.SinConexion
import kotlinx.coroutines.CancellationException

/** Cómo terminó `responder`, `rearmar` o `cerrar`, para decírselo al usuario. */
enum class ResultadoAccion {
    RESPONDIDO,
    REARMADO,

    /** `cerrar` aceptado. La fase cambia después, con los eventos `estado`. */
    CIERRE_ACEPTADO,

    /** `sin_alerta`: la alerta ya había terminado (§7: ignorar). */
    SIN_ALERTA,

    /** `no_bloqueado`: el sistema ya no estaba bloqueado (§7: ignorar). */
    NO_BLOQUEADO,

    /** `ya_bloqueado`: el suministro ya estaba cerrado; para volver a usarlo hay que rearmar. */
    YA_BLOQUEADO,

    /** `valvula_cerrada`: el sensor de posición indica que falta abrir la válvula a mano. */
    VALVULA_CERRADA,

    /** `comando_desconocido`: el firmware es anterior al comando. */
    FIRMWARE_ANTIGUO,
    SIN_CONEXION,
    SIN_RESPUESTA,
    ERROR,
}

/** Envía [comando] (`responder`, `rearmar` o `cerrar`) y traduce la respuesta o la falla. */
suspend fun ClienteSatcp.enviarAccionAlerta(comando: Comando): ResultadoAccion = try {
    val respuesta = enviar(comando)
    when {
        respuesta.ok -> when (comando) {
            Comando.Rearmar -> ResultadoAccion.REARMADO
            Comando.Cerrar -> ResultadoAccion.CIERRE_ACEPTADO
            else -> ResultadoAccion.RESPONDIDO
        }
        respuesta.error == CodigosError.SIN_ALERTA -> ResultadoAccion.SIN_ALERTA
        respuesta.error == CodigosError.NO_BLOQUEADO -> ResultadoAccion.NO_BLOQUEADO
        respuesta.error == CodigosError.YA_BLOQUEADO -> ResultadoAccion.YA_BLOQUEADO
        respuesta.error == CodigosError.VALVULA_CERRADA -> ResultadoAccion.VALVULA_CERRADA
        respuesta.error == CodigosError.COMANDO_DESCONOCIDO -> ResultadoAccion.FIRMWARE_ANTIGUO
        else -> ResultadoAccion.ERROR
    }
} catch (e: CancellationException) {
    throw e
} catch (e: SinConexion) {
    ResultadoAccion.SIN_CONEXION
} catch (e: ConexionCerrada) {
    ResultadoAccion.SIN_CONEXION
} catch (e: TiempoEsperaAgotado) {
    ResultadoAccion.SIN_RESPUESTA
} catch (e: Exception) {
    ResultadoAccion.ERROR
}
