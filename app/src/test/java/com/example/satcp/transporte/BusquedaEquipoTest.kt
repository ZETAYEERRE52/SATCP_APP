package com.example.satcp.transporte

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class BusquedaEquipoTest {

    @Test
    fun ganaElPrimeroQueRespondeAunqueLleguePorMdnsDespues() = runTest {
        // La IP guardada ya no responde (tarda los 3 s y falla); mDNS encuentra la nueva al segundo.
        val candidatos = flow {
            emit("26.6.2.100")
            delay(1_000)
            emit("26.6.2.136")
        }
        val elegido = primeroQueResponda(candidatos, limiteMs = 7_000) { ip ->
            if (ip == "26.6.2.100") {
                delay(3_000)
                false
            } else {
                delay(20)
                true
            }
        }
        assertEquals("26.6.2.136", elegido)
        // No espera a que la IP vieja termine de fallar.
        assertEquals(1_020L, currentTime)
    }

    @Test
    fun laIpGuardadaQueRespondeGanaEnseguida() = runTest {
        val candidatos = flow {
            emit("26.6.2.136")
            delay(4_000) // mDNS todavía buscando
            emit("26.6.2.136")
        }
        val elegido = primeroQueResponda(candidatos, limiteMs = 7_000) { delay(15); true }
        assertEquals("26.6.2.136", elegido)
        assertEquals(15L, currentTime)
    }

    @Test
    fun noRepiteCandidatos() = runTest {
        val probados = mutableListOf<String>()
        primeroQueResponda(flowOf("a", "b", "a", "b"), limiteMs = 1_000) { probados += it; false }
        assertEquals(listOf("a", "b"), probados)
    }

    @Test
    fun sinRespuestaDevuelveNullAlVencerElLimite() = runTest {
        val elegido = primeroQueResponda(flow { emit("x"); delay(60_000) }, limiteMs = 7_000) { false }
        assertNull(elegido)
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun subredIpv4() {
        val local = InetAddress.getByName("26.6.2.50") as Inet4Address
        assertTrue(mismaSubredIpv4(local, 24, "26.6.2.136"))
        assertFalse(mismaSubredIpv4(local, 24, "26.6.3.136"))
        assertTrue(mismaSubredIpv4(local, 16, "26.6.200.1"))
        assertFalse(mismaSubredIpv4(local, 24, "no-es-ip"))
    }
}
