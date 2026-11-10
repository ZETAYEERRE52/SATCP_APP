package com.example.satcp.protocolo

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmparejadorRespuestasTest {

    private val enviados = mutableListOf<String>()
    private fun idDe(texto: String) = Json.parseToJsonElement(texto).jsonObject["id"]!!.jsonPrimitive.long

    private fun respuesta(id: Long, cmd: String = "estado") =
        Respuesta(cmd = cmd, id = id, ok = true, datos = null, error = null, mensaje = null)

    @Test
    fun losIdsSonNumericosYCrecientes() = runTest {
        val emparejador = EmparejadorRespuestas()
        repeat(3) {
            val espera = async { emparejador.enviarYEsperar(Comando.Estado, 10_000) { enviados += it } }
            runCurrent()
            emparejador.entregar(respuesta(idDe(enviados.last())))
            espera.await()
        }
        assertEquals(listOf(1L, 2L, 3L), enviados.map(::idDe))
    }

    @Test
    fun emparejaRespuestasQueLleganEnDesorden() = runTest {
        val emparejador = EmparejadorRespuestas()
        val info = async { emparejador.enviarYEsperar(Comando.Info, 10_000) { enviados += it } }
        val foto = async { emparejador.enviarYEsperar(Comando.Foto(), 10_000) { enviados += it } }
        runCurrent()
        val (idInfo, idFoto) = enviados.map(::idDe)

        assertTrue(emparejador.entregar(respuesta(idFoto, "foto")))
        assertTrue(emparejador.entregar(respuesta(idInfo, "info")))

        assertEquals("info", info.await().cmd)
        assertEquals("foto", foto.await().cmd)
        assertEquals(0, emparejador.cantidadPendientes)
    }

    @Test
    fun respuestaQueLlegaDuranteElEnvioNoSePierde() = runTest {
        val emparejador = EmparejadorRespuestas()
        // El transporte entrega la respuesta antes de que enviar() devuelva el control.
        val r = emparejador.enviarYEsperar(Comando.Estado, 10_000) { emparejador.entregar(respuesta(idDe(it))) }
        assertEquals("estado", r.cmd)
    }

    @Test
    fun agotaElTiempoDeEsperaYLiberaElId() = runTest {
        val emparejador = EmparejadorRespuestas()
        val espera = async {
            runCatching { emparejador.enviarYEsperar(Comando.Foto(imagen = true), 30_000) { enviados += it } }
        }
        advanceTimeBy(29_999)
        runCurrent()
        assertFalse(espera.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        val error = espera.await().exceptionOrNull()
        assertTrue(error is TiempoEsperaAgotado)
        assertEquals("foto", (error as TiempoEsperaAgotado).cmd)
        assertEquals(30_000L, error.milisegundos)
        assertEquals(0, emparejador.cantidadPendientes)
        // Una respuesta tardía ya no tiene a quién entregarse.
        assertFalse(emparejador.entregar(respuesta(idDe(enviados.single()), "foto")))
    }

    @Test
    fun respuestaSinIdOConIdAjenoSeIgnora() = runTest {
        val emparejador = EmparejadorRespuestas()
        val espera = async { emparejador.enviarYEsperar(Comando.Info, 10_000) { enviados += it } }
        runCurrent()
        assertFalse(emparejador.entregar(respuesta(999)))
        assertFalse(emparejador.entregar(Respuesta("info", null, true, null, null, null)))
        assertFalse(espera.isCompleted)
        emparejador.entregar(respuesta(idDe(enviados.single()), "info"))
        assertEquals("info", espera.await().cmd)
    }

    @Test
    fun cancelarTodosDespiertaALosQueEsperan() = runTest {
        val emparejador = EmparejadorRespuestas()
        val espera = async { runCatching { emparejador.enviarYEsperar(Comando.Info, 10_000) { } } }
        runCurrent()
        emparejador.cancelarTodos()
        assertTrue(espera.await().exceptionOrNull() is ConexionCerrada)
        assertEquals(0, emparejador.cantidadPendientes)
    }

    @Test
    fun errorAlEnviarNoDejaEsperasColgadas() = runTest {
        val emparejador = EmparejadorRespuestas()
        try {
            emparejador.enviarYEsperar(Comando.Info, 10_000) { throw IllegalStateException("sin conexión") }
            fail("Debía fallar")
        } catch (e: IllegalStateException) {
            assertEquals(0, emparejador.cantidadPendientes)
        }
    }
}
