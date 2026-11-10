package com.example.satcp.servicio

import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.Fase
import com.example.satcp.transporte.SinConexion
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClienteSatcpSeguridadTest {

    private fun estado(fase: String) = """{"tipo":"evento","evento":"estado","datos":{"fase":"$fase"}}"""

    private fun TestScope.conectado(transporte: TransporteSimulado = TransporteSimulado()): Pair<ClienteSatcp, TransporteSimulado> {
        val cliente = clienteDePrueba()
        cliente.conectar(transporte)
        runCurrent()
        return cliente to transporte
    }

    @Test
    fun loPrimeroEsHolaYDespuesTodoVaCifrado() = runTest {
        val (cliente, transporte) = conectado()
        assertEquals(1, transporte.holas)
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        // TransporteSimulado falla si lo que llega después del hola no descifra con el AAD "app".
        assertTrue(transporte.enviados.single().contains("\"n\":1"))
        assertTrue(transporte.enviados.single().contains("\"cmd\":\"info\""))
    }

    @Test
    fun sinRespuestaAHolaNoQuedaConectado() = runTest {
        val cliente = clienteDePrueba()
        val transporte = TransporteSimulado().apply { respuestaHola = """{"tipo":"otra"}""" }
        cliente.conectar(transporte)
        runCurrent()
        assertTrue(cliente.estadoConexion.value is EstadoConexion.Conectando)
        val error = runCatching { cliente.enviar(Comando.Estado) }.exceptionOrNull()
        assertTrue(error is SinConexion)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(cliente.estadoConexion.value is EstadoConexion.Perdida)
    }

    @Test
    fun cadaReconexionAbreOtraSesion() = runTest {
        val (cliente, transporte) = conectado()
        transporte.cerrarDesdeElEsp32()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, transporte.holas)
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
    }

    @Test
    fun unEstadoEnClaroSeDescarta() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibirEnClaro(estado("ALERTA_USUARIO"))
        runCurrent()
        assertNull(cliente.estado.value)
    }

    @Test
    fun unaRespuestaEnClaroNoCompletaUnComando() = runTest {
        val (cliente, transporte) = conectado()
        val enviados = transporte.enviados.size
        val espera = backgroundScope.async { runCatching { cliente.enviar(Comando.Responder) } }
        runCurrent()
        assertEquals(enviados + 1, transporte.enviados.size)
        transporte.recibirEnClaro("""{"tipo":"respuesta","cmd":"responder","id":2,"ok":true}""")
        runCurrent()
        assertTrue(espera.isActive)
    }

    @Test
    fun unHolaQueNadieEsperaNoCambiaLaSesion() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibirEnClaro(
            """{"tipo":"hola","id":1,"ok":true,"protocolo":2,"reto":"ffffffffffffffffffffffffffffffff"}"""
        )
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
    }

    @Test
    fun descartaLosContadoresQueNoCrecen() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibirEnClaro(transporte.sobre(estado("MONITOREO"), n = 5))
        runCurrent()
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
        transporte.recibirEnClaro(transporte.sobre(estado("ALERTA_USUARIO"), n = 5))
        transporte.recibirEnClaro(transporte.sobre(estado("ALERTA_USUARIO"), n = 4))
        runCurrent()
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
    }

    @Test
    fun noAutorizadoPideLaClaveYNoReintenta() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibirEnClaro("""{"tipo":"respuesta","ok":false,"error":"no_autorizado","mensaje":"Clave incorrecta"}""")
        runCurrent()
        assertEquals(EstadoConexion.ClaveRechazada, cliente.estadoConexion.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, transporte.conexiones)
        assertEquals(EstadoConexion.ClaveRechazada, cliente.estadoConexion.value)
    }

    @Test
    fun conOtraClaveElPrimerSobreDelEquipoNoDescifra() = runTest {
        val (cliente, transporte) = conectado(TransporteSimulado(claveDelEquipo = "otra-clave-distinta"))
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        assertEquals(EstadoConexion.ClaveRechazada, cliente.estadoConexion.value)
        assertNull(cliente.estado.value)
    }

    @Test
    fun yaConfirmadaUnSobreAlteradoSoloSeDescarta() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibir(estado("MONITOREO"))
        transporte.recibirEnClaro("""{"c":"AAECAwQFBgcICQoLrG0G4TJP4eVdrRGusTJ3EilOrwdGXi6jklhe2L8lbcyNocwuf+j87jHfxtYL"}""")
        runCurrent()
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
    }

    @Test
    fun sinClaveGuardadaLaPide() = runTest {
        val cliente = clienteDePrueba(clave = null)
        cliente.conectar(TransporteSimulado())
        runCurrent()
        assertEquals(EstadoConexion.ClaveRechazada, cliente.estadoConexion.value)
    }

    @Test
    fun sinSesionYRepetidoAbrenOtraSesion() = runTest {
        for (codigo in listOf("sin_sesion", "repetido")) {
            val (cliente, transporte) = conectado()
            transporte.recibirEnClaro("""{"tipo":"respuesta","ok":false,"error":"$codigo"}""")
            runCurrent()
            assertEquals(MotivoPerdida.SESION_PERDIDA, (cliente.estadoConexion.value as EstadoConexion.Perdida).motivo)
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(2, transporte.holas)
            cliente.desconectar()
        }
    }

    @Test
    fun sinEspacioSeInforma() = runTest {
        val cliente = clienteDePrueba()
        cliente.conectar(TransporteSimulado().apply { respuestaHola = """{"tipo":"hola","id":1,"ok":false,"error":"sin_espacio"}""" })
        runCurrent()
        assertEquals(MotivoPerdida.SIN_ESPACIO, (cliente.estadoConexion.value as EstadoConexion.Perdida).motivo)
    }

    @Test
    fun unFirmwareAnteriorSeInformaComoIncompatible() = runTest {
        val cliente = clienteDePrueba()
        cliente.conectar(
            TransporteSimulado().apply {
                respuestaHola = """{"tipo":"respuesta","cmd":"hola","id":1,"ok":false,"error":"comando_desconocido"}"""
            }
        )
        runCurrent()
        assertEquals(MotivoPerdida.FIRMWARE_INCOMPATIBLE, (cliente.estadoConexion.value as EstadoConexion.Perdida).motivo)
    }

    @Test
    fun otraVersionDeProtocoloSeInformaComoIncompatible() = runTest {
        val cliente = clienteDePrueba()
        cliente.conectar(TransporteSimulado().apply { protocoloEnHola = 3 })
        runCurrent()
        assertEquals(MotivoPerdida.FIRMWARE_INCOMPATIBLE, (cliente.estadoConexion.value as EstadoConexion.Perdida).motivo)
    }

    @Test
    fun unRetoNoValidoNoAbreSesion() = runTest {
        val cliente = clienteDePrueba()
        cliente.conectar(TransporteSimulado().apply { respuestaHola = """{"tipo":"hola","id":1,"ok":true,"protocolo":2,"reto":"abc"}""" })
        runCurrent()
        assertEquals(MotivoPerdida.FIRMWARE_INCOMPATIBLE, (cliente.estadoConexion.value as EstadoConexion.Perdida).motivo)
    }

    @Test
    fun pedirVivoDevuelveLaUrlConToken() = runTest {
        val (cliente, transporte) = conectado()
        val permiso = backgroundScope.async { cliente.pedirVivo() }
        runCurrent()
        val id = Regex("\"id\":(\\d+)").find(transporte.enviados.last())!!.groupValues[1]
        assertTrue(transporte.enviados.last().contains("\"cmd\":\"vivo\""))
        transporte.recibir("""{"tipo":"respuesta","cmd":"vivo","id":$id,"ok":true,"datos":{"puerto":81,"url":"/vivo?t=8c1e04","vigencia_s":30}}""")
        runCurrent()
        assertEquals("/vivo?t=8c1e04", permiso.await()!!.url)
    }

    @Test
    fun cadaAperturaDelVideoPideUnTokenNuevo() = runTest {
        val (cliente, transporte) = conectado()
        repeat(2) { vez ->
            val permiso = backgroundScope.async { cliente.pedirVivo() }
            runCurrent()
            val id = Regex("\"id\":(\\d+)").find(transporte.enviados.last())!!.groupValues[1]
            transporte.recibir("""{"tipo":"respuesta","cmd":"vivo","id":$id,"ok":true,"datos":{"url":"/vivo?t=$vez"}}""")
            runCurrent()
            assertEquals("/vivo?t=$vez", permiso.await()!!.url)
        }
        assertEquals(2, transporte.enviados.count { it.contains("\"cmd\":\"vivo\"") })
    }
}
