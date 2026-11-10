package com.example.satcp.transporte

import org.junit.Assert.assertEquals
import org.junit.Test

class ReensambladorLineasTest {

    private val reensamblador = ReensambladorLineas()
    private fun agregar(texto: String) = reensamblador.agregar(texto.toByteArray(Charsets.UTF_8))

    @Test
    fun mensajeCompletoEnUnaNotificacion() {
        assertEquals(listOf("""{"tipo":"respuesta","cmd":"responder","id":4,"ok":true}"""),
            agregar("{\"tipo\":\"respuesta\",\"cmd\":\"responder\",\"id\":4,\"ok\":true}\n"))
    }

    @Test
    fun mensajePartidoEnVariasNotificaciones() {
        assertEquals(emptyList<String>(), agregar("""{"tipo":"evento","""))
        assertEquals(emptyList<String>(), agregar(""""evento":"imagen_fin""""))
        assertEquals(listOf("""{"tipo":"evento","evento":"imagen_fin","id":7}"""), agregar(""","id":7}""" + "\n"))
    }

    @Test
    fun variosMensajesEnUnaNotificacionYRestoPendiente() {
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), agregar("{\"a\":1}\n{\"b\":2}\n{\"c\""))
        assertEquals(listOf("""{"c":3}"""), agregar(":3}\n"))
    }

    @Test
    fun caracterUtf8PartidoEntreDosNotificaciones() {
        val bytes = "{\"mensaje\":\"cámara\"}\n".toByteArray(Charsets.UTF_8)
        val corte = bytes.indexOf(0xC3.toByte()) + 1 // a la mitad de la "á"
        assertEquals(emptyList<String>(), reensamblador.agregar(bytes.copyOfRange(0, corte)))
        assertEquals(listOf("{\"mensaje\":\"cámara\"}"), reensamblador.agregar(bytes.copyOfRange(corte, bytes.size)))
    }

    @Test
    fun mensajeDe20BytesPorNotificacionSinMtu() {
        val mensaje = """{"tipo":"evento","evento":"estado","datos":{"fase":"MONITOREO"}}"""
        val bytes = (mensaje + "\n").toByteArray()
        val lineas = bytes.toList().chunked(20).flatMap { reensamblador.agregar(it.toByteArray()) }
        assertEquals(listOf(mensaje), lineas)
    }

    @Test
    fun lineasVaciasYRetornoDeCarroSeIgnoran() {
        assertEquals(listOf("""{"a":1}"""), agregar("\n\r\n{\"a\":1}\r\n\n"))
    }

    @Test
    fun reiniciarDescartaLoAcumulado() {
        agregar("""{"incompleto":""")
        reensamblador.reiniciar()
        assertEquals(listOf("""{"a":1}"""), agregar("{\"a\":1}\n"))
    }

    @Test
    fun basuraSinFinDeLineaNoCreceSinLimite() {
        val chico = ReensambladorLineas(maximoBytes = 10)
        chico.agregar("x".repeat(25).toByteArray())
        assertEquals(listOf("""{"a":1}"""), chico.agregar("\n{\"a\":1}\n".toByteArray()).filter { it.startsWith("{") })
    }
}
