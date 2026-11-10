package com.example.satcp.transporte

import com.example.satcp.protocolo.CodigosError
import com.example.satcp.protocolo.ImagenInicio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.random.Random

class ReceptorImagenBleTest {

    private val receptor = ReceptorImagenBle()
    private val jpeg = Random(42).nextBytes(41234)
    private val crc = CRC32().apply { update(jpeg) }.value

    private fun enviarEnFragmentos(datos: ByteArray, tamanio: Int = 514) {
        datos.toList().chunked(tamanio).forEach { receptor.agregarFragmento(it.toByteArray()) }
    }

    @Test
    fun crc32EsElEstandar() {
        // Valor de referencia del CRC-32 estándar (IEEE 802.3) para "123456789".
        assertEquals(0xCBF43926L, CRC32().apply { update("123456789".toByteArray()) }.value)
    }

    @Test
    fun armaLaFotoConFragmentosDe514Bytes() {
        receptor.iniciar(ImagenInicio(id = 7, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        assertTrue(receptor.enCurso)
        enviarEnFragmentos(jpeg)
        assertEquals(jpeg.size, receptor.recibidos)

        val resultado = receptor.finalizar(7) as ResultadoImagen.Completa
        assertArrayEquals(jpeg, resultado.jpeg)
        assertFalse(receptor.enCurso)
    }

    @Test
    fun armaLaFotoConFragmentosDesparejos() {
        receptor.iniciar(ImagenInicio(id = 1, bytes = jpeg.size, crc32 = crc, fragmento = null))
        enviarEnFragmentos(jpeg, tamanio = 177)
        assertTrue(receptor.finalizar(1) is ResultadoImagen.Completa)
    }

    @Test
    fun crcDistintoDescartaLaFoto() {
        receptor.iniciar(ImagenInicio(id = 7, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        val alterada = jpeg.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        enviarEnFragmentos(alterada)

        val resultado = receptor.finalizar(7) as ResultadoImagen.CrcInvalido
        assertEquals(crc, resultado.esperado)
        assertFalse(receptor.enCurso)
    }

    @Test
    fun faltanBytesEsIncompleta() {
        receptor.iniciar(ImagenInicio(id = 7, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        enviarEnFragmentos(jpeg.copyOf(jpeg.size - 514))
        assertEquals(ResultadoImagen.Incompleta(7, jpeg.size, jpeg.size - 514), receptor.finalizar(7))
    }

    @Test
    fun finConIdAjenoSeIgnora() {
        receptor.iniciar(ImagenInicio(id = 7, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        enviarEnFragmentos(jpeg)
        assertNull(receptor.finalizar(8))
        assertTrue(receptor.enCurso)
        assertTrue(receptor.finalizar(7) is ResultadoImagen.Completa)
    }

    @Test
    fun bytesFueraDeUnaTransferenciaSeIgnoran() {
        receptor.agregarFragmento(byteArrayOf(1, 2, 3))
        receptor.iniciar(ImagenInicio(id = 2, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        enviarEnFragmentos(jpeg)
        assertTrue(receptor.finalizar(2) is ResultadoImagen.Completa)
    }

    @Test
    fun nuevaTransferenciaEmpiezaDeCero() {
        receptor.iniciar(ImagenInicio(id = 1, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        enviarEnFragmentos(jpeg.copyOf(2000))
        receptor.iniciar(ImagenInicio(id = 2, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        enviarEnFragmentos(jpeg)
        assertTrue(receptor.finalizar(2) is ResultadoImagen.Completa)
    }

    @Test
    fun imagenErrorInterrumpe() {
        receptor.iniciar(ImagenInicio(id = 7, bytes = jpeg.size, crc32 = crc, fragmento = 514))
        enviarEnFragmentos(jpeg.copyOf(3000))
        assertEquals(ResultadoImagen.Interrumpida(7, null), receptor.interrumpir(7, null))
        assertFalse(receptor.enCurso)
    }

    @Test
    fun sinSuscripcionLlegaSinImagenInicio() {
        assertEquals(
            ResultadoImagen.Interrumpida(7, CodigosError.SIN_SUSCRIPCION),
            receptor.interrumpir(7, CodigosError.SIN_SUSCRIPCION),
        )
    }
}
