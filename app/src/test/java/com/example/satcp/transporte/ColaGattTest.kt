package com.example.satcp.transporte

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColaGattTest {

    private val cola = ColaGatt(tiempoMaximoMs = 5_000)
    private val iniciadas = mutableListOf<String>()

    @Test
    fun ejecutaUnaOperacionALaVez() = runTest {
        val mtu = async { cola.ejecutar("mtu") { iniciadas += "mtu"; true } }
        val cccd = async { cola.ejecutar("cccd") { iniciadas += "cccd"; true } }
        val rx = async { cola.ejecutar("rx") { iniciadas += "rx"; true } }
        runCurrent()
        assertEquals(listOf("mtu"), iniciadas)

        cola.completar(0)
        runCurrent()
        assertEquals(listOf("mtu", "cccd"), iniciadas)

        cola.completar(0)
        runCurrent()
        cola.completar(0)
        runCurrent()
        assertEquals(listOf("mtu", "cccd", "rx"), iniciadas)
        listOf(mtu, cccd, rx).forEach { it.await() }
    }

    @Test
    fun estadoDeErrorFalla() = runTest {
        val resultado = async { runCatching { cola.ejecutar("cccd") { true } } }
        runCurrent()
        cola.completar(133)
        assertTrue(resultado.await().exceptionOrNull() is OperacionGattFallida)
    }

    @Test
    fun operacionNoAceptadaFallaYLiberaLaCola() = runTest {
        val rechazada = runCatching { cola.ejecutar("rx") { false } }
        assertTrue(rechazada.exceptionOrNull() is OperacionGattFallida)

        val siguiente = async { cola.ejecutar("rx") { iniciadas += "rx"; true } }
        runCurrent()
        cola.completar(0)
        siguiente.await()
        assertEquals(listOf("rx"), iniciadas)
    }

    @Test
    fun sinCallbackAgotaElTiempoSinCancelarAQuienLlama() = runTest {
        val resultado = async { runCatching { cola.ejecutar("mtu") { true } } }
        advanceTimeBy(5_001)
        runCurrent()
        // No es una CancellationException: la sesión puede tratarla como cualquier falla.
        assertTrue(resultado.await().exceptionOrNull() is OperacionGattFallida)
    }

    @Test
    fun cancelarTodoDespiertaLaOperacionEnCurso() = runTest {
        val resultado = async { runCatching { cola.ejecutar("rx") { true } } }
        runCurrent()
        cola.cancelarTodo()
        assertTrue(resultado.await().exceptionOrNull() is OperacionGattFallida)
    }
}
