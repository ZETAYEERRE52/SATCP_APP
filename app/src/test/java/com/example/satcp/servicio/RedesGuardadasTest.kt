package com.example.satcp.servicio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedesGuardadasTest {

    private val casa = RedGuardada("MiCasa", "secreta123")
    private val taller = RedGuardada("Taller", null)

    @Test
    fun laRedNuevaQuedaPrimero() {
        assertEquals(listOf(taller, casa), listOf(casa).conRed(taller))
    }

    @Test
    fun guardarElMismoNombreReemplazaLaClaveSinDuplicar() {
        val corregida = RedGuardada("MiCasa", "otraClave1")
        assertEquals(listOf(corregida, taller), listOf(taller, casa).conRed(corregida))
    }

    @Test
    fun borrarQuitaSoloEsaRed() {
        assertEquals(listOf(taller), listOf(casa, taller).sinRed("MiCasa"))
    }

    @Test
    fun laListaSobreviveAlCodificarla() {
        val redes = listOf(casa, taller, RedGuardada("Cafetería ñ", "clave\"rara\\1"))
        assertEquals(redes, decodificarRedes(codificarRedes(redes)))
    }

    @Test
    fun unContenidoInvalidoEquivaleAListaVacia() {
        assertEquals(emptyList<RedGuardada>(), decodificarRedes("esto no es json"))
        assertEquals(emptyList<RedGuardada>(), decodificarRedes("""[{"sin":"ssid"}]"""))
    }

    @Test
    fun elTextoDelObjetoNoMuestraLaClave() {
        assertFalse(casa.toString().contains("secreta123"))
    }
}
