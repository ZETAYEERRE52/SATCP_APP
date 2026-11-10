package com.example.satcp.servicio

import com.example.satcp.protocolo.Comando
import com.example.satcp.protocolo.Fase
import com.example.satcp.transporte.EquipoNoEncontrado
import com.example.satcp.transporte.EventoTransporte
import com.example.satcp.transporte.SinConexion
import com.example.satcp.transporte.TipoTransporte
import com.example.satcp.transporte.Transporte
import com.example.satcp.transporte.WebSocketFallido
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClienteSatcpTest {

    private fun estado(fase: String) =
        """{"tipo":"evento","evento":"estado","datos":{"fase":"$fase","temperatura":{"medida":30.0,"estimada":30.0,"sensor":25.0,"linea_base":null},"presencia":{"estado":"nadie","distancia_movimiento_cm":0,"distancia_quieta_cm":0},"alerta":null,"conexion":{"wifi_casa_ip":null,"ble_conectado":false}}}"""

    private fun idDe(texto: String) = Json.parseToJsonElement(texto).jsonObject["id"]!!.jsonPrimitive.long

    private fun TestScope.conectado(): Pair<ClienteSatcp, TransporteSimulado> {
        val cliente = clienteDePrueba()
        val transporte = TransporteSimulado()
        cliente.conectar(transporte)
        runCurrent()
        return cliente to transporte
    }

    private fun TestScope.motivoAlFallarCon(error: Exception): MotivoPerdida? {
        val cliente = ClienteSatcp(backgroundScope)
        cliente.conectar(object : Transporte {
            override val tipo = TipoTransporte.WIFI
            override fun conectar(): Flow<EventoTransporte> = flow { throw error }
            override suspend fun enviar(texto: String) = Unit
        })
        runCurrent()
        return (cliente.estadoConexion.value as? EstadoConexion.Perdida)?.motivo
    }

    @Test
    fun noLlegarAlEquipoSeInformaComoNoEncontrado() = runTest {
        assertEquals(MotivoPerdida.NO_ENCONTRADO, motivoAlFallarCon(EquipoNoEncontrado(null)))
    }

    @Test
    fun unWebSocketCortadoSeInformaComoInterrumpido() = runTest {
        assertEquals(MotivoPerdida.INTERRUMPIDA, motivoAlFallarCon(WebSocketFallido(null, null)))
    }

    @Test
    fun alAbrirQuedaConectadoYPideInfo() = runTest {
        val (cliente, transporte) = conectado()
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        assertTrue(transporte.enviados.single().contains("\"cmd\":\"info\""))

        transporte.recibir(
            """{"tipo":"respuesta","cmd":"info","id":${idDe(transporte.enviados.single())},"ok":true,"datos":{"firmware":"1.1.0","protocolo":1}}"""
        )
        runCurrent()
        assertEquals("1.1.0", cliente.info.value!!.firmware)
    }

    @Test
    fun cadaEventoEstadoActualizaElEstado() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
        transporte.recibir(estado("ALERTA_USUARIO"))
        runCurrent()
        assertEquals(Fase.ALERTA_USUARIO, cliente.estado.value!!.fase)
    }

    @Test
    fun mensajesInvalidosSeIgnoran() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibirEnClaro("esto no es json")
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        assertEquals(Fase.MONITOREO, cliente.estado.value!!.fase)
    }

    @Test
    fun quinceSegundosSinEventosEsConexionPerdidaYReconecta() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibir(estado("MONITOREO"))
        runCurrent()

        // Los latidos cada 5 s mantienen la conexión.
        repeat(3) {
            advanceTimeBy(5_000)
            transporte.recibir(estado("MONITOREO"))
            runCurrent()
        }
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)

        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        advanceTimeBy(2)
        runCurrent()
        val perdida = cliente.estadoConexion.value as EstadoConexion.Perdida
        assertEquals(MotivoPerdida.SIN_EVENTOS, perdida.motivo)
        assertEquals(1_000L, perdida.reintentoEnMs)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, transporte.conexiones)
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
    }

    @Test
    fun soloQuedaAbiertoElTransporteQueEligioElUsuario() = runTest {
        val cliente = clienteDePrueba()
        val bluetooth = TransporteSimulado(tipo = TipoTransporte.BLE)
        val wifi = TransporteSimulado(tipo = TipoTransporte.WIFI)

        cliente.conectar(bluetooth)
        runCurrent()
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        assertEquals(1, bluetooth.abiertas)

        // Elegir Wi-Fi cierra el Bluetooth: no comparten la antena del ESP32.
        cliente.conectar(wifi)
        runCurrent()
        assertEquals(0, bluetooth.abiertas)
        assertEquals(1, wifi.abiertas)
        assertEquals(TipoTransporte.WIFI, cliente.transporte.value?.tipo)

        // Y al revés.
        cliente.conectar(bluetooth)
        runCurrent()
        assertEquals(0, wifi.abiertas)
        assertEquals(1, bluetooth.abiertas)

        cliente.desconectar()
        runCurrent()
        assertEquals(0, bluetooth.abiertas + wifi.abiertas)
    }

    @Test
    fun cualquierMensajeDescifradoReiniciaElVigilante() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibir(estado("MONITOREO"))
        runCurrent()

        // 30 s sin estado, pero con una respuesta autenticada cada 10 s: la conexión sigue viva.
        repeat(3) {
            advanceTimeBy(10_000)
            transporte.recibir("""{"tipo":"respuesta","cmd":"foto","id":999,"ok":true}""")
            runCurrent()
        }
        assertEquals(EstadoConexion.Conectado, cliente.estadoConexion.value)
        assertEquals(1, transporte.conexiones)

        // Un mensaje en claro no autenticado no la mantiene.
        transporte.recibirEnClaro("""{"tipo":"evento","evento":"estado","datos":{"fase":"MONITOREO"}}""")
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(MotivoPerdida.SIN_EVENTOS, (cliente.estadoConexion.value as EstadoConexion.Perdida).motivo)
    }

    @Test
    fun laEsperaDeReconexionCreceHasta30Segundos() = runTest {
        val cliente = clienteDePrueba()
        val transporte = TransporteSimulado().apply { fallarAlConectar = true }
        cliente.conectar(transporte)
        runCurrent()

        val esperas = mutableListOf<Long>()
        repeat(7) {
            val perdida = cliente.estadoConexion.value as EstadoConexion.Perdida
            assertEquals(MotivoPerdida.ERROR, perdida.motivo)
            esperas += perdida.reintentoEnMs
            advanceTimeBy(perdida.reintentoEnMs + 1)
            runCurrent()
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), esperas)
    }

    @Test
    fun trasUnaSesionQueFuncionoLaEsperaVuelveA1Segundo() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        transporte.cerrarDesdeElEsp32()
        runCurrent()
        val perdida = cliente.estadoConexion.value as EstadoConexion.Perdida
        assertEquals(MotivoPerdida.CERRADA, perdida.motivo)
        assertEquals(1_000L, perdida.reintentoEnMs)
    }

    @Test
    fun enviarEmparejaLaRespuesta() = runTest {
        val (cliente, transporte) = conectado()
        val respuesta = async { cliente.enviar(Comando.Responder) }
        runCurrent()
        val id = idDe(transporte.enviados.last())
        transporte.recibir("""{"tipo":"respuesta","cmd":"responder","id":$id,"ok":true}""")
        runCurrent()
        assertTrue(respuesta.await().ok)
    }

    @Test
    fun enviarSinConexionFalla() = runTest {
        val cliente = ClienteSatcp(backgroundScope)
        val error = runCatching { cliente.enviar(Comando.Estado) }.exceptionOrNull()
        assertTrue(error is SinConexion)
    }

    @Test
    fun desconectarDejaTodoLimpioYNoReconecta() = runTest {
        val (cliente, transporte) = conectado()
        transporte.recibir(estado("MONITOREO"))
        runCurrent()
        cliente.desconectar()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(EstadoConexion.Desconectado, cliente.estadoConexion.value)
        assertNull(cliente.estado.value)
        assertNull(cliente.transporte.value)
        assertEquals(1, transporte.conexiones)
    }
}
