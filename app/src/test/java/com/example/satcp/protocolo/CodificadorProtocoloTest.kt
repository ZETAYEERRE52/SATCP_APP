package com.example.satcp.protocolo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cada mensaje de ejemplo de docs/PROTOCOLO_APP.md, de ida y de vuelta. */
class CodificadorProtocoloTest {

    // ---------- App → ESP32 ----------

    private fun assertMismoJson(esperado: String, obtenido: String) =
        assertEquals(Json.parseToJsonElement(esperado), Json.parseToJsonElement(obtenido))

    @Test
    fun codificaInfo() =
        assertMismoJson("""{"cmd":"info","id":1}""", CodificadorProtocolo.codificar(Comando.Info, 1))

    @Test
    fun codificaEstado() =
        assertMismoJson("""{"cmd":"estado","id":2}""", CodificadorProtocolo.codificar(Comando.Estado, 2))

    @Test
    fun codificaFotoSinImagen() =
        assertMismoJson("""{"cmd":"foto","id":3}""", CodificadorProtocolo.codificar(Comando.Foto(), 3))

    @Test
    fun codificaFotoConImagen() =
        assertMismoJson(
            """{"cmd": "foto", "id": 7, "imagen": true}""",
            CodificadorProtocolo.codificar(Comando.Foto(imagen = true), 7),
        )

    @Test
    fun codificaResponder() =
        assertMismoJson("""{"cmd":"responder","id":4}""", CodificadorProtocolo.codificar(Comando.Responder, 4))

    @Test
    fun codificaRearmar() =
        assertMismoJson("""{"cmd":"rearmar","id":5}""", CodificadorProtocolo.codificar(Comando.Rearmar, 5))

    @Test
    fun codificaWifi() =
        assertMismoJson(
            """{"cmd":"wifi","id":6,"ssid":"MiCasa","clave":"secreta123"}""",
            CodificadorProtocolo.codificar(Comando.Wifi("MiCasa", "secreta123"), 6),
        )

    @Test
    fun codificaWifiAbiertaSinClave() =
        assertMismoJson(
            """{"cmd":"wifi","id":6,"ssid":"Abierta"}""",
            CodificadorProtocolo.codificar(Comando.Wifi("Abierta", ""), 6),
        )

    @Test
    fun wifiConCamposAlLimiteCabeEnElComando() {
        val texto = CodificadorProtocolo.codificar(Comando.Wifi("ñ".repeat(31), "ñ".repeat(63)), Long.MAX_VALUE)
        assertTrue(texto.toByteArray().size <= LIMITE_BYTES_COMANDO)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wifiRechazaSsidLargo() {
        Comando.Wifi("x".repeat(32), null)
    }

    // ---------- ESP32 → app: respuestas ----------

    @Test
    fun decodificaRespuestaInfo() {
        val r = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"info","id":1,"ok":true,"datos":{
     "dispositivo":"SATCP","firmware":"1.1.0","protocolo":1,"mac":"AA:BB:CC:DD:EE:FF",
     "wifi":{"ap_ssid":"SATCP","ap_ip":"192.168.4.1","mdns":"satcp.local","puerto_vivo":81,"casa_ip":"192.168.1.40"},
     "ble_nombre":"SATCP","clases":["fuego","humo","normal"]}}"""
        ) as Respuesta
        assertEquals("info", r.cmd)
        assertEquals(1L, r.id)
        assertTrue(r.ok)
        val datos = CodificadorProtocolo.datosDe(r, DatosInfo.serializer())!!
        assertEquals("SATCP", datos.dispositivo)
        assertEquals("1.1.0", datos.firmware)
        assertEquals(1, datos.protocolo)
        assertEquals("AA:BB:CC:DD:EE:FF", datos.mac)
        assertEquals("SATCP", datos.wifi.apSsid)
        assertEquals("192.168.4.1", datos.wifi.apIp)
        assertEquals("satcp.local", datos.wifi.mdns)
        assertEquals(81, datos.wifi.puertoVivo)
        assertEquals("192.168.1.40", datos.wifi.casaIp)
        assertEquals("SATCP", datos.bleNombre)
        assertEquals(listOf("fuego", "humo", "normal"), datos.clases)
    }

    @Test
    fun decodificaRespuestaEstado() {
        val r = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"estado","id":2,"ok":true,"datos":$DATOS_ESTADO}"""
        ) as Respuesta
        assertEquals("estado", r.cmd)
        assertEquals(2L, r.id)
        val datos = CodificadorProtocolo.datosDe(r, DatosEstado.serializer())!!
        assertEquals(Fase.ALERTA_USUARIO, datos.fase)
    }

    @Test
    fun decodificaRespuestaFoto() {
        val r = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"foto","id":3,"ok":true,"datos":{
     "clasificada":true,
     "clase":"fuego",
     "probabilidades":{"fuego":0.921,"humo":0.031,"normal":0.048},
     "tiempo_ms":310,
     "guardada":true,
     "ruta_sd":"/sdcard/telefono/s0004/fuego_92_000012.jpg",
     "bytes":41234,
     "imagen":{"entrega":"http","url":"/foto/ultima.jpg"}}}"""
        ) as Respuesta
        assertEquals(3L, r.id)
        val foto = CodificadorProtocolo.datosDe(r, DatosFoto.serializer())!!
        assertTrue(foto.clasificada)
        assertEquals("fuego", foto.clase)
        assertEquals(mapOf("fuego" to 0.921, "humo" to 0.031, "normal" to 0.048), foto.probabilidades)
        assertEquals(310, foto.tiempoMs)
        assertTrue(foto.guardada)
        assertEquals("/sdcard/telefono/s0004/fuego_92_000012.jpg", foto.rutaSd)
        assertEquals(41234, foto.bytes)
        assertEquals(EntregaImagen.HTTP, foto.imagen!!.entrega)
        assertEquals("/foto/ultima.jpg", foto.imagen!!.url)
    }

    @Test
    fun decodificaFotoSinModeloNiSd() {
        val r = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"foto","id":9,"ok":true,"datos":{
     "clasificada":false,"clase":null,"tiempo_ms":0,"guardada":false,"ruta_sd":null,
     "bytes":41234,"imagen":{"entrega":"no_solicitada"}}}"""
        ) as Respuesta
        val foto = CodificadorProtocolo.datosDe(r, DatosFoto.serializer())!!
        assertFalse(foto.clasificada)
        assertNull(foto.clase)
        assertNull(foto.probabilidades)
        assertFalse(foto.guardada)
        assertNull(foto.rutaSd)
        assertEquals(EntregaImagen.NO_SOLICITADA, foto.imagen!!.entrega)
    }

    @Test
    fun decodificaEntregasDeImagen() {
        fun entrega(valor: String) = CodificadorProtocolo.json
            .decodeFromString(ImagenFoto.serializer(), """{"entrega":"$valor"}""").entrega
        assertEquals(EntregaImagen.BLE, entrega("ble"))
        assertEquals(EntregaImagen.NO_DISPONIBLE, entrega("no_disponible"))
        assertEquals(EntregaImagen.DESCONOCIDA, entrega("otra_cosa"))
    }

    @Test
    fun decodificaErrorCamaraOcupada() {
        val r = CodificadorProtocolo.decodificar(
            """{"tipo": "respuesta", "cmd": "foto", "id": 7, "ok": false, "error": "camara_ocupada", "mensaje": "La cámara está ocupada, reintenta en unos segundos"}"""
        ) as Respuesta
        assertEquals("foto", r.cmd)
        assertEquals(7L, r.id)
        assertFalse(r.ok)
        assertEquals(CodigosError.CAMARA_OCUPADA, r.error)
        assertEquals("La cámara está ocupada, reintenta en unos segundos", r.mensaje)
        assertNull(r.datos)
    }

    @Test
    fun decodificaResponderYRearmar() {
        val responder = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"responder","id":4,"ok":true}"""
        ) as Respuesta
        assertEquals("responder", responder.cmd)
        assertEquals(4L, responder.id)
        assertTrue(responder.ok)
        assertNull(responder.datos)

        val rearmar = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"rearmar","id":5,"ok":true}"""
        ) as Respuesta
        assertEquals("rearmar", rearmar.cmd)
        assertEquals(5L, rearmar.id)
        assertTrue(rearmar.ok)
    }

    @Test
    fun decodificaErroresSinAlertaYNoBloqueado() {
        val sinAlerta = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"responder","id":4,"ok":false,"error":"sin_alerta"}"""
        ) as Respuesta
        assertEquals(CodigosError.SIN_ALERTA, sinAlerta.error)
        val noBloqueado = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"rearmar","id":5,"ok":false,"error":"no_bloqueado"}"""
        ) as Respuesta
        assertEquals(CodigosError.NO_BLOQUEADO, noBloqueado.error)
    }

    @Test
    fun decodificaRespuestaWifi() {
        val r = CodificadorProtocolo.decodificar(
            """{"tipo":"respuesta","cmd":"wifi","id":6,"ok":true,"mensaje":"Conectando; la IP llegará en el evento de estado"}"""
        ) as Respuesta
        assertEquals("wifi", r.cmd)
        assertTrue(r.ok)
        assertEquals("Conectando; la IP llegará en el evento de estado", r.mensaje)
    }

    @Test
    fun aceptaIdComoStringNumerico() {
        val r = CodificadorProtocolo.decodificar("""{"tipo":"respuesta","cmd":"info","id":"12","ok":true}""") as Respuesta
        assertEquals(12L, r.id)
    }

    // ---------- ESP32 → app: eventos ----------

    @Test
    fun decodificaEventoEstado() {
        val e = CodificadorProtocolo.decodificar(
            """{"tipo":"evento","evento":"estado","datos":$DATOS_ESTADO}"""
        ) as EventoEstado
        val d = e.datos
        assertEquals(Fase.ALERTA_USUARIO, d.fase)
        assertEquals(31.42, d.temperatura.medida!!, 0.0)
        assertEquals(31.4, d.temperatura.estimada!!, 0.0)
        assertEquals(24.8, d.temperatura.sensor!!, 0.0)
        assertNull(d.temperatura.lineaBase)
        assertEquals(EstadoPresencia.NADIE, d.presencia.estado)
        assertEquals(0, d.presencia.distanciaMovimientoCm)
        assertEquals(0, d.presencia.distanciaQuietaCm)
        val alerta = d.alerta!!
        assertEquals(Vision.FUEGO, alerta.vision)
        assertEquals(31.4, alerta.temperatura!!, 0.0)
        assertEquals(OrigenAlerta.TEMPERATURA, alerta.origen)
        assertEquals(95, alerta.cierreEnS)
        assertTrue(alerta.admiteRespuesta)
        assertFalse(alerta.requiereRearme)
        assertEquals("192.168.1.40", d.conexion.wifiCasaIp)
        assertTrue(d.conexion.bleConectado)
    }

    @Test
    fun decodificaEstadoSinAlertaYSinLectura() {
        val e = CodificadorProtocolo.decodificar(
            """{"tipo":"evento","evento":"estado","datos":{"fase":"MONITOREO",
              "temperatura":{"medida":null,"estimada":null,"sensor":24.8,"linea_base":28.5},
              "presencia":{"estado":"en_movimiento_y_quieta","distancia_movimiento_cm":120,"distancia_quieta_cm":80},
              "alerta":null,"conexion":{"wifi_casa_ip":null,"ble_conectado":false}}}"""
        ) as EventoEstado
        assertEquals(Fase.MONITOREO, e.datos.fase)
        assertNull(e.datos.temperatura.medida)
        assertEquals(28.5, e.datos.temperatura.lineaBase!!, 0.0)
        assertEquals(EstadoPresencia.EN_MOVIMIENTO_Y_QUIETA, e.datos.presencia.estado)
        assertEquals(120, e.datos.presencia.distanciaMovimientoCm)
        assertNull(e.datos.alerta)
        assertNull(e.datos.conexion.wifiCasaIp)
    }

    @Test
    fun decodificaTodosLosValoresDeFasePresenciaVisionYOrigen() {
        Fase.entries.filter { it != Fase.DESCONOCIDA }.forEach { fase ->
            assertEquals(fase, estadoCon(""""fase":"${fase.name}"""").fase)
        }
        mapOf(
            "nadie" to EstadoPresencia.NADIE,
            "en_movimiento" to EstadoPresencia.EN_MOVIMIENTO,
            "quieta" to EstadoPresencia.QUIETA,
            "en_movimiento_y_quieta" to EstadoPresencia.EN_MOVIMIENTO_Y_QUIETA,
            "sin_datos" to EstadoPresencia.SIN_DATOS,
        ).forEach { (texto, valor) ->
            assertEquals(valor, estadoCon(""""presencia":{"estado":"$texto"}""").presencia.estado)
        }
        mapOf(
            "FUEGO" to Vision.FUEGO,
            "HUMO" to Vision.HUMO,
            "error de visión" to Vision.ERROR_DE_VISION,
            "sin riesgo" to Vision.SIN_RIESGO,
        ).forEach { (texto, valor) ->
            assertEquals(valor, estadoCon(""""alerta":{"vision":"$texto"}""").alerta!!.vision)
        }
        assertEquals(
            OrigenAlerta.AUTOCALIBRACION,
            estadoCon(""""alerta":{"origen":"autocalibracion","requiere_rearme":true}""").alerta!!.origen,
        )
    }

    @Test
    fun valoresNuevosDelFirmwareNoRompenLaDecodificacion() {
        val datos = estadoCon(""""fase":"FASE_FUTURA","campo_nuevo":1,"presencia":{"estado":"otro"}""")
        assertEquals(Fase.DESCONOCIDA, datos.fase)
        assertEquals(EstadoPresencia.DESCONOCIDO, datos.presencia.estado)
    }

    @Test
    fun decodificaEventosDeImagen() {
        val inicio = CodificadorProtocolo.decodificar(
            """{"tipo":"evento","evento":"imagen_inicio","id":7,"bytes":41234,"crc32":2864434397,"fragmento":514}"""
        ) as ImagenInicio
        assertEquals(ImagenInicio(id = 7, bytes = 41234, crc32 = 2864434397L, fragmento = 514), inicio)

        assertEquals(
            ImagenFin(7),
            CodificadorProtocolo.decodificar("""{"tipo":"evento","evento":"imagen_fin","id":7}"""),
        )
        assertEquals(
            ImagenError(7, null),
            CodificadorProtocolo.decodificar("""{"tipo":"evento","evento":"imagen_error","id":7}"""),
        )
        assertEquals(
            ImagenError(7, CodigosError.SIN_SUSCRIPCION),
            CodificadorProtocolo.decodificar(
                """{"tipo":"evento","evento":"imagen_error","id":7,"error":"sin_suscripcion"}"""
            ),
        )
    }

    @Test
    fun eventoOTipoDesconocidoNoFalla() {
        assertTrue(CodificadorProtocolo.decodificar("""{"tipo":"evento","evento":"nuevo"}""") is MensajeDesconocido)
        assertTrue(CodificadorProtocolo.decodificar("""{"tipo":"otro"}""") is MensajeDesconocido)
    }

    @Test(expected = MensajeInvalido::class)
    fun textoQueNoEsJsonEsInvalido() {
        CodificadorProtocolo.decodificar("""{"tipo":"respuesta","cmd":""")
    }

    @Test(expected = MensajeInvalido::class)
    fun arregloJsonEsInvalido() {
        CodificadorProtocolo.decodificar("""[1,2]""")
    }

    private fun estadoCon(campos: String): DatosEstado =
        (CodificadorProtocolo.decodificar("""{"tipo":"evento","evento":"estado","datos":{$campos}}""") as EventoEstado).datos

    private companion object {
        const val DATOS_ESTADO = """{
  "fase":"ALERTA_USUARIO",
  "temperatura":{"medida":31.42,"estimada":31.4,"sensor":24.8,"linea_base":null},
  "presencia":{"estado":"nadie","distancia_movimiento_cm":0,"distancia_quieta_cm":0},
  "alerta":{"vision":"FUEGO","temperatura":31.4,"origen":"temperatura","cierre_en_s":95,
            "admite_respuesta":true,"requiere_rearme":false},
  "conexion":{"wifi_casa_ip":"192.168.1.40","ble_conectado":true}}"""
    }
}
