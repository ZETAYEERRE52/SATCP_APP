package com.example.satcp.servicio

import com.example.satcp.Registro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * La bitácora en un archivo privado de la app (una línea JSON por evento).
 * Se agrega al final y cada [REESCRIBIR_CADA] eventos se recorta a [DIAS_BITACORA] días.
 */
class AlmacenBitacora(private val archivo: File, alcance: CoroutineScope) {

    private val _eventos = MutableStateFlow<List<EventoBitacora>>(emptyList())
    /** De más viejo a más nuevo. */
    val eventos: StateFlow<List<EventoBitacora>> = _eventos.asStateFlow()

    private val candado = Mutex()
    private var agregadosDesdeRecorte = 0

    init {
        alcance.launch {
            candado.withLock {
                val leidos = withContext(Dispatchers.IO) { leer() }
                _eventos.value = recortarBitacora(leidos, System.currentTimeMillis())
            }
        }
    }

    suspend fun agregar(nuevos: List<EventoBitacora>) {
        if (nuevos.isEmpty()) return
        candado.withLock {
            var lista = _eventos.value + nuevos
            agregadosDesdeRecorte += nuevos.size
            withContext(Dispatchers.IO) {
                if (agregadosDesdeRecorte >= REESCRIBIR_CADA || lista.size > MAXIMO_EVENTOS_BITACORA) {
                    lista = recortarBitacora(lista, System.currentTimeMillis())
                    escribirTodo(lista)
                    agregadosDesdeRecorte = 0
                } else {
                    anexar(nuevos)
                }
            }
            _eventos.value = lista
        }
    }

    suspend fun borrar() = candado.withLock {
        withContext(Dispatchers.IO) { archivo.delete() }
        _eventos.value = emptyList()
    }

    private fun leer(): List<EventoBitacora> = try {
        if (!archivo.exists()) emptyList() else archivo.readLines().mapNotNull(::decodificarLinea)
    } catch (e: IOException) {
        Registro.aviso("No se pudo leer la bitácora: ${e.message}")
        emptyList()
    }

    private fun anexar(nuevos: List<EventoBitacora>) = try {
        archivo.appendText(nuevos.joinToString("") { codificarLinea(it) + "\n" })
    } catch (e: IOException) {
        Registro.aviso("No se pudo guardar en la bitácora: ${e.message}")
    }

    private fun escribirTodo(lista: List<EventoBitacora>) {
        try {
            val temporal = File(archivo.parentFile, archivo.name + ".tmp")
            temporal.writeText(lista.joinToString("") { codificarLinea(it) + "\n" })
            if (!temporal.renameTo(archivo)) {
                archivo.delete()
                temporal.renameTo(archivo)
            }
        } catch (e: IOException) {
            Registro.aviso("No se pudo reescribir la bitácora: ${e.message}")
        }
    }

    companion object {
        const val NOMBRE_ARCHIVO = "bitacora.jsonl"
        private const val REESCRIBIR_CADA = 200

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun codificarLinea(evento: EventoBitacora): String = json.encodeToString(EventoBitacora.serializer(), evento)

        /** `null` si la línea está dañada (p. ej. el teléfono se apagó mientras se escribía). */
        fun decodificarLinea(linea: String): EventoBitacora? = try {
            if (linea.isBlank()) null else json.decodeFromString(EventoBitacora.serializer(), linea)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
