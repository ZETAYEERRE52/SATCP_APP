package com.example.satcp.protocolo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mensajes nuevos del protocolo 2, con los ejemplos de docs/PROTOCOLO_APP.md. */
class ProtocoloV2Test {

    private fun mismoJson(esperado: String, real: String) =
        assertEquals(Json.parseToJsonElement(esperado).jsonObject, Json.parseToJsonElement(real).jsonObject)

    private fun estado(json: String) = (CodificadorProtocolo.decodificar(
        """{"n":3,"tipo":"evento","evento":"estado","datos":$json}"""
    ) as EventoEstado).datos

    @Test
    fun codificaHola() = mismoJson("""{"cmd":"hola","id":1}""", CodificadorProtocolo.codificarHola(1))

    @Test
    fun decodificaHola() {
        val hola = CodificadorProtocolo.decodificar(
            """{"tipo":"hola","id":1,"ok":true,"protocolo":2,"reto":"00112233445566778899aabbccddeeff","sal":"SATCP-clave-v1","iteraciones":10000}"""
        ) as MensajeHola
        assertTrue(hola.ok)
        assertEquals(2, hola.protocolo)
        assertEquals("00112233445566778899aabbccddeeff", hola.reto)
    }

    @Test
    fun decodificaHolaSinEspacio() {
        val hola = CodificadorProtocolo.decodificar("""{"tipo":"hola","id":1,"ok":false,"error":"sin_espacio"}""") as MensajeHola
        assertFalse(hola.ok)
        assertEquals(CodigosError.SIN_ESPACIO, hola.error)
    }

    @Test
    fun decodificaLosErroresDeSeguridadQueNoTraenCmd() {
        for (codigo in listOf(CodigosError.NO_AUTORIZADO, CodigosError.SIN_SESION, CodigosError.REPETIDO)) {
            val error = CodificadorProtocolo.decodificar("""{"tipo":"respuesta","ok":false,"error":"$codigo","mensaje":"x"}""")
            assertEquals(ErrorSeguridad(codigo), error)
        }
    }

    @Test
    fun otraRespuestaSinCmdSigueSiendoInvalida() {
        assertThrows(MensajeInvalido::class.java) {
            CodificadorProtocolo.decodificar("""{"tipo":"respuesta","ok":false,"error":"camara_ocupada"}""")
        }
    }

    @Test
    fun codificaVivo() = mismoJson("""{"cmd":"vivo","id":11}""", CodificadorProtocolo.codificar(Comando.Vivo, 11))

    @Test
    fun decodificaLaRespuestaDeVivo() {
        val respuesta = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"vivo","id":11,"ok":true,"datos":{"puerto":81,"url":"/vivo?t=8c1e04","vigencia_s":30}}"""
        ) as Respuesta
        val datos = CodificadorProtocolo.datosDe(respuesta, DatosVivo.serializer())!!
        assertEquals(81, datos.puerto)
        assertEquals("/vivo?t=8c1e04", datos.url)
    }

    @Test
    fun codificaCambiarClave() = mismoJson(
        """{"cmd":"cambiar_clave","id":12,"clave_nueva":"MiClaveSegura2026"}""",
        CodificadorProtocolo.codificar(Comando.CambiarClave("MiClaveSegura2026"), 12),
    )

    @Test
    fun cambiarClaveRechazaLoQueElFirmwareNoAdmite() {
        assertThrows(IllegalArgumentException::class.java) { Comando.CambiarClave("corta") }
        assertThrows(IllegalArgumentException::class.java) { Comando.CambiarClave("contraseña1") }
    }

    @Test
    fun elTextoDeCambiarClaveNoMuestraLaClave() {
        assertFalse(Comando.CambiarClave("MiClaveSegura2026").toString().contains("MiClaveSegura2026"))
    }

    @Test
    fun unaWifiConClaveLargaCabeEnElSobre() {
        val texto = CodificadorProtocolo.codificar(Comando.Wifi("a".repeat(31), "b".repeat(63)), Long.MAX_VALUE)
        val cifrado = (texto.toByteArray().size + "\"n\":${Long.MAX_VALUE},".length + 28 + 2) / 3 * 4 + """{"c":""}""".length
        assertTrue(cifrado <= LIMITE_BYTES_SOBRE)
    }

    @Test
    fun decodificaLaValvula() {
        val datos = estado("""{"fase":"MONITOREO","valvula":{"estado":"cerrada","sensor":true}}""")
        assertEquals(EstadoValvula.CERRADA, datos.valvula.estado)
        assertTrue(datos.valvula.sensor)
    }

    @Test
    fun sinValvulaElEstadoEsDesconocido() {
        assertEquals(EstadoValvula.DESCONOCIDO, estado("""{"fase":"MONITOREO"}""").valvula.estado)
    }

    @Test
    fun decodificaElOrigenReinicio() {
        val alerta = estado(
            """{"fase":"BLOQUEADO","alerta":{"vision":"sin riesgo","temperatura":null,"origen":"reinicio","cierre_en_s":0,"admite_respuesta":false,"requiere_rearme":true}}"""
        ).alerta!!
        assertEquals(OrigenAlerta.REINICIO, alerta.origen)
        assertTrue(alerta.origen.esCierreSinVision)
        assertTrue(alerta.requiereRearme)
    }

    @Test
    fun decodificaLaFallaDelSensor() {
        val temperatura = estado(
            """{"fase":"MONITOREO","temperatura":{"sensor_ok":false,"medida":null,"estimada":null,"sensor":null,"linea_base":null}}"""
        ).temperatura
        assertFalse(temperatura.sensorOk)
        assertNull(temperatura.medida)
    }

    @Test
    fun sinElCampoElSensorSeConsideraBien() {
        assertTrue(estado("""{"fase":"MONITOREO","temperatura":{"medida":30.0}}""").temperatura.sensorOk)
    }

    @Test
    fun conocenLosErroresNuevos() {
        assertEquals("valvula_cerrada", CodigosError.VALVULA_CERRADA)
        assertEquals("solo_wifi", CodigosError.SOLO_WIFI)
    }
}
