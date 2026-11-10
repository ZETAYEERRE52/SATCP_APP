package com.example.satcp.servicio

import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.Comando
import com.example.satcp.transporte.TipoTransporte
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.random.Random

class ClienteSatcpFotoTest {

    private val jpeg = Random(7).nextBytes(5_000)

    private fun json(texto: String) = Json.parseToJsonElement(texto).jsonObject
    private fun TransporteSimulado.ultimoFoto() = enviados.last { it.contains("\"cmd\":\"foto\"") }
    private fun TransporteSimulado.idUltimaFoto() = json(ultimoFoto())["id"]!!.jsonPrimitive.long

    private fun respuestaFoto(id: Long, entrega: String) =
        """{"tipo":"respuesta","cmd":"foto","id":$id,"ok":true,"datos":{"clasificada":true,"clase":"normal","probabilidades":{"fuego":0.01,"humo":0.02,"normal":0.97},"tiempo_ms":300,"guardada":true,"ruta_sd":"/sdcard/x.jpg","bytes":${jpeg.size},"imagen":{"entrega":"$entrega","url":"/foto/ultima.jpg?t=3f9a0c"}}}"""

    private fun TestScope.conectado(tipo: TipoTransporte): Pair<ClienteSatcp, TransporteSimulado> {
        val cliente = clienteDePrueba()
        val transporte = TransporteSimulado(tipo)
        cliente.conectar(transporte)
        runCurrent()
        return cliente to transporte
    }

    /** Envía la foto como el ESP32: cifrada, con el CRC-32 calculado sobre los bytes cifrados (§6). */
    private fun TransporteSimulado.enviarImagen(
        id: Long,
        crcErroneo: Boolean = false,
        alterar: Boolean = false,
    ) {
        val datos = imagenCifrada(jpeg)
        if (alterar) datos[datos.size - 1] = (datos.last().toInt() xor 1).toByte()
        val crc = CRC32().apply { update(datos) }.value
        val anunciado = if (crcErroneo) crc xor 1 else crc
        recibir("""{"tipo":"evento","evento":"imagen_inicio","id":$id,"bytes":${datos.size},"crc32":$anunciado,"fragmento":514}""")
        datos.toList().chunked(514).forEach { recibirBytes(it.toByteArray()) }
        recibir("""{"tipo":"evento","evento":"imagen_fin","id":$id}""")
    }

    @Test
    fun fotoPorBluetoothPideLaImagenYLaArma() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.BLE)
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        assertEquals(true, json(transporte.ultimoFoto())["imagen"]!!.jsonPrimitive.content.toBoolean())

        val id = transporte.idUltimaFoto()
        transporte.recibir(respuestaFoto(id, "ble"))
        transporte.enviarImagen(id)
        runCurrent()

        val tomada = foto.await() as ResultadoFoto.Tomada
        assertArrayEquals(jpeg, tomada.jpeg)
        assertNull(tomada.aviso)
        assertEquals("normal", tomada.datos.clase)
    }

    @Test
    fun crcDistintoDescartaLaImagenPeroConservaLaClasificacion() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.BLE)
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        val id = transporte.idUltimaFoto()
        transporte.recibir(respuestaFoto(id, "ble"))
        transporte.enviarImagen(id, crcErroneo = true)
        runCurrent()

        val tomada = foto.await() as ResultadoFoto.Tomada
        assertNull(tomada.jpeg)
        assertEquals(AvisoImagen.CRC_INVALIDO, tomada.aviso)
        assertEquals("normal", tomada.datos.clase)
    }

    @Test
    fun laImagenAlteradaConCrcCorrectoSeDescarta() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.BLE)
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        val id = transporte.idUltimaFoto()
        transporte.recibir(respuestaFoto(id, "ble"))
        transporte.enviarImagen(id, alterar = true)
        runCurrent()

        val tomada = foto.await() as ResultadoFoto.Tomada
        assertNull(tomada.jpeg)
        assertEquals(AvisoImagen.ALTERADA, tomada.aviso)
        assertEquals("normal", tomada.datos.clase)
    }

    @Test
    fun sinSuscripcionSeInforma() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.BLE)
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        val id = transporte.idUltimaFoto()
        transporte.recibir(respuestaFoto(id, "ble"))
        transporte.recibir("""{"tipo":"evento","evento":"imagen_error","id":$id,"error":"sin_suscripcion"}""")
        runCurrent()
        assertEquals(AvisoImagen.SIN_SUSCRIPCION, (foto.await() as ResultadoFoto.Tomada).aviso)
    }

    @Test
    fun losComandosEsperanAQueTermineLaTransferencia() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.BLE)
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        val id = transporte.idUltimaFoto()
        transporte.recibir(respuestaFoto(id, "ble"))
        runCurrent()

        val enviadosAntes = transporte.enviados.size
        val responder = async { cliente.enviar(Comando.Responder) }
        runCurrent()
        assertEquals("responder no debe salir durante la foto", enviadosAntes, transporte.enviados.size)

        transporte.enviarImagen(id)
        runCurrent()
        assertTrue(transporte.enviados.last().contains("\"cmd\":\"responder\""))

        val idResponder = json(transporte.enviados.last())["id"]!!.jsonPrimitive.long
        transporte.recibir("""{"tipo":"respuesta","cmd":"responder","id":$idResponder,"ok":true}""")
        runCurrent()
        assertTrue(responder.await().ok)
        assertTrue(foto.await() is ResultadoFoto.Tomada)
    }

    @Test
    fun camaraOcupadaSeReintentaUnaVezA3Segundos() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.WIFI)
        transporte.archivo = jpeg
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        transporte.recibir(
            """{"tipo":"respuesta","cmd":"foto","id":${transporte.idUltimaFoto()},"ok":false,"error":"camara_ocupada"}"""
        )
        runCurrent()
        val fotosEnviadas = { transporte.enviados.count { it.contains("\"cmd\":\"foto\"") } }
        assertEquals(1, fotosEnviadas())

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, fotosEnviadas())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, fotosEnviadas())

        transporte.recibir(respuestaFoto(transporte.idUltimaFoto(), "http"))
        runCurrent()
        assertTrue(foto.await() is ResultadoFoto.Tomada)
    }

    @Test
    fun camaraOcupadaDosVecesSeRechaza() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.WIFI)
        val foto = async { cliente.tomarFoto() }
        repeat(2) {
            runCurrent()
            transporte.recibir(
                """{"tipo":"respuesta","cmd":"foto","id":${transporte.idUltimaFoto()},"ok":false,"error":"camara_ocupada"}"""
            )
            runCurrent()
            advanceTimeBy(3_001)
        }
        runCurrent()
        assertEquals(ResultadoFoto.Rechazada(CodigosError.CAMARA_OCUPADA, null), foto.await())
        assertEquals(2, transporte.enviados.count { it.contains("\"cmd\":\"foto\"") })
    }

    @Test
    fun fotoPorWifiDescargaLaUrlYNoPideImagen() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.WIFI)
        transporte.archivo = jpeg
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        assertNull(json(transporte.ultimoFoto())["imagen"])
        transporte.recibir(respuestaFoto(transporte.idUltimaFoto(), "http"))
        runCurrent()

        val tomada = foto.await() as ResultadoFoto.Tomada
        assertEquals("la URL se usa tal cual, con su token", listOf("/foto/ultima.jpg?t=3f9a0c"), transporte.descargas)
        assertArrayEquals(jpeg, tomada.jpeg)
    }

    @Test
    fun fotoPorWifiCon404AvisaQueNoHayImagen() = runTest {
        val (cliente, transporte) = conectado(TipoTransporte.WIFI)
        val foto = async { cliente.tomarFoto() }
        runCurrent()
        transporte.recibir(respuestaFoto(transporte.idUltimaFoto(), "http"))
        runCurrent()
        assertEquals(AvisoImagen.NO_ENCONTRADA, (foto.await() as ResultadoFoto.Tomada).aviso)
    }
}
