package com.example.satcp.transporte

import com.example.satcp.protocolo.TiempoEsperaAgotado
import com.example.satcp.ui.vivo.VivoOcupado
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException

class FallosRedTest {

    @Test
    fun codigosHttp() {
        assertEquals(CausaFallo(TipoFallo.TOKEN_RECHAZADO, 403), causaDe(ErrorHttp(403)))
        assertEquals(CausaFallo(TipoFallo.OCUPADO, 503), causaDe(ErrorHttp(503)))
        assertEquals(CausaFallo(TipoFallo.OCUPADO, 503), causaDe(VivoOcupado()))
        assertEquals(CausaFallo(TipoFallo.NO_ENCONTRADO, 404), causaDe(ErrorHttp(404)))
        assertEquals(CausaFallo(TipoFallo.HTTP, 500), causaDe(ErrorHttp(500)))
    }

    @Test
    fun tiemposAgotados() {
        assertEquals(TipoFallo.TIEMPO_AGOTADO, causaDe(SocketTimeoutException("Read timed out")).tipo)
        assertEquals(TipoFallo.TIEMPO_AGOTADO, causaDe(InterruptedIOException("timeout")).tipo)
        assertEquals(TipoFallo.TIEMPO_AGOTADO, causaDe(TiempoEsperaAgotado("vivo", 10_000)).tipo)
    }

    @Test
    fun sinRutaAlEquipo() {
        assertEquals(TipoFallo.SIN_RUTA, causaDe(NoRouteToHostException("No route to host")).tipo)
        assertEquals(TipoFallo.SIN_RUTA, causaDe(ConnectException("Connection refused")).tipo)
        assertEquals(TipoFallo.SIN_RUTA, causaDe(UnknownHostException("satcp.local")).tipo)
    }

    @Test
    fun cleartextBloqueado() {
        val error = UnknownServiceException("CLEARTEXT communication to 26.6.2.136 not permitted by network security policy")
        assertEquals(TipoFallo.CLEARTEXT, causaDe(error).tipo)
        assertEquals(TipoFallo.OTRO, causaDe(UnknownServiceException("otra cosa")).tipo)
    }

    @Test
    fun buscaEnLaCadenaDeCausas() {
        val envuelto = IOException("falló", IOException("intermedio", NoRouteToHostException("sin ruta")))
        assertEquals(TipoFallo.SIN_RUTA, causaDe(envuelto).tipo)
        assertEquals(TipoFallo.SIN_RUTA, causaDe(EquipoNoEncontrado(ConnectException("rechazada"))).tipo)
        assertEquals(TipoFallo.OTRO, causaDe(IllegalStateException("nada que ver")).tipo)
    }

    @Test
    fun quitaElTokenDeLaUrl() {
        assertEquals("http://satcp.local:81/vivo?t=…", urlSinToken("http://satcp.local:81/vivo?t=8c1e04ab"))
        assertEquals(
            "http://satcp.local/foto/ultima.jpg?x=1&t=…&y=2",
            urlSinToken("http://satcp.local/foto/ultima.jpg?x=1&t=3f9a0ce1&y=2"),
        )
        assertEquals("http://192.168.4.1/info", urlSinToken("http://192.168.4.1/info"))
        // Un parámetro que solo termina en "t" no es el token.
        assertEquals("http://satcp.local/a?st=1", urlSinToken("http://satcp.local/a?st=1"))
    }

    @Test
    fun elWebSocketLlevaLatidoDe15Segundos() {
        assertEquals(15_000, TransporteWifi.clientePorDefecto.pingIntervalMillis)
    }
}
