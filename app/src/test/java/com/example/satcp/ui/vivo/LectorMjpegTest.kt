package com.example.satcp.ui.vivo

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class LectorMjpegTest {

    private val cuadro1 = Random(1).nextBytes(41234)
    private val cuadro2 = Random(2).nextBytes(38000)

    private fun parte(jpeg: ByteArray, conLargo: Boolean = true) = Buffer()
        .writeUtf8("--satcpcuadro\r\n")
        .writeUtf8("Content-Type: image/jpeg\r\n")
        .apply { if (conLargo) writeUtf8("Content-Length: ${jpeg.size}\r\n") }
        .writeUtf8("\r\n")
        .write(jpeg)
        .writeUtf8("\r\n")

    @Test
    fun separaCuadrosPorContentLength() {
        val flujo = Buffer().apply {
            writeAll(parte(cuadro1))
            writeAll(parte(cuadro2))
        }
        val lector = LectorMjpeg(flujo, "satcpcuadro")
        assertArrayEquals(cuadro1, lector.siguienteCuadro())
        assertArrayEquals(cuadro2, lector.siguienteCuadro())
        assertNull("al cerrarse la conexión termina", lector.siguienteCuadro())
    }

    @Test
    fun sinContentLengthLeeHastaElSiguienteBoundary() {
        val flujo = Buffer().apply {
            writeAll(parte(cuadro1, conLargo = false))
            writeAll(parte(cuadro2))
        }
        val lector = LectorMjpeg(flujo, "satcpcuadro")
        assertArrayEquals(cuadro1, lector.siguienteCuadro())
        assertArrayEquals(cuadro2, lector.siguienteCuadro())
    }

    @Test
    fun boundaryDeCierreTermina() {
        val flujo = Buffer().apply {
            writeAll(parte(cuadro1))
            writeUtf8("--satcpcuadro--\r\n")
            writeAll(parte(cuadro2))
        }
        val lector = LectorMjpeg(flujo, "satcpcuadro")
        assertArrayEquals(cuadro1, lector.siguienteCuadro())
        assertNull(lector.siguienteCuadro())
    }

    @Test
    fun encabezadosEnMinusculasYExtras() {
        val flujo = Buffer()
            .writeUtf8("\r\n--satcpcuadro\r\ncontent-type: image/jpeg\r\nX-Timestamp: 12.5\r\ncontent-length: ${cuadro1.size}\r\n\r\n")
            .write(cuadro1)
        assertArrayEquals(cuadro1, LectorMjpeg(flujo, "satcpcuadro").siguienteCuadro())
    }

    @Test
    fun cuadroCortadoAMitadTermina() {
        val flujo = Buffer()
            .writeUtf8("--satcpcuadro\r\nContent-Length: ${cuadro1.size}\r\n\r\n")
            .write(cuadro1.copyOf(1000))
        assertNull(LectorMjpeg(flujo, "satcpcuadro").siguienteCuadro())
    }

    @Test
    fun leeElBoundaryDelContentType() {
        assertEquals("satcpcuadro", LectorMjpeg.boundaryDe("multipart/x-mixed-replace;boundary=satcpcuadro"))
        assertEquals("otro", LectorMjpeg.boundaryDe("multipart/x-mixed-replace; boundary=\"otro\""))
        assertEquals("x", LectorMjpeg.boundaryDe("multipart/x-mixed-replace; boundary=--x"))
        assertNull(LectorMjpeg.boundaryDe("image/jpeg"))
        assertNull(LectorMjpeg.boundaryDe(null))
    }
}
