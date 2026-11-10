package com.example.satcp.protocolo

import com.example.satcp.seguridad.Criptografia

/** Comandos de §4. Cada uno sabe su nombre `cmd` y sus parámetros propios. */
sealed class Comando(val nombre: String) {
    data object Info : Comando("info")
    data object Estado : Comando("estado")

    /** [imagen] solo tiene efecto por Bluetooth: pide que el JPEG llegue por la característica de imagen. */
    data class Foto(val imagen: Boolean = false) : Comando("foto")

    data object Responder : Comando("responder")
    data object Rearmar : Comando("rearmar")

    /** Corta el suministro. Vale en cualquier fase excepto `BLOQUEADO` (error `ya_bloqueado`). */
    data object Cerrar : Comando("cerrar")

    data object Horarios : Comando("horarios")

    /** Crea un horario en el primer lugar libre ([indice] null) o reemplaza el de [indice]. */
    data class HorarioGuardar(
        val indice: Int?,
        val hora: Int,
        val minuto: Int,
        val dias: Int,
        val activo: Boolean = true,
    ) : Comando("horario_guardar") {
        init {
            require(indice == null || indice in INDICES) { "indice de 0 a 7" }
            require(hora in 0..23) { "hora de 0 a 23" }
            require(minuto in 0..59) { "minuto de 0 a 59" }
            require(dias in 1..DiasSemana.TODOS) { "al menos un día" }
        }
    }

    data class HorarioBorrar(val indice: Int) : Comando("horario_borrar") {
        init {
            require(indice in INDICES) { "indice de 0 a 7" }
        }
    }

    /** [clave] nula o vacía para una red abierta. */
    data class Wifi(val ssid: String, val clave: String?) : Comando("wifi") {
        init {
            require(ssid.isNotEmpty() && ssid.length <= MAX_SSID) { "ssid de 1 a $MAX_SSID caracteres" }
            require(clave == null || clave.length <= MAX_CLAVE) { "clave de hasta $MAX_CLAVE caracteres" }
        }

        companion object {
            const val MAX_SSID = 31
            const val MAX_CLAVE = 63
        }
    }

    /** Pide permiso para abrir el video en vivo. Solo por Wi-Fi. */
    data object Vivo : Comando("vivo")

    /** Cambia la clave del equipo. No es `data class` para que su texto nunca muestre la clave. */
    class CambiarClave(val claveNueva: String) : Comando("cambiar_clave") {
        init {
            require(Criptografia.esClaveValida(claveNueva)) { "clave de 8 a 63 caracteres ASCII imprimibles" }
        }
    }

    private companion object {
        val INDICES = 0 until MAXIMO_HORARIOS
    }
}
