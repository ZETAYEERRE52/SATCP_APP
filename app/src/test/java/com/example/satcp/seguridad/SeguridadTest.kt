package com.example.satcp.seguridad

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Los vectores vienen de §1b del protocolo: deben dar exactamente esos valores. */
class SeguridadTest {

    private val clave = "k7m3pq9xw2ha"
    private val maestraEsperada = "df80c3463906c28f62504e9a8f4387ec5db9bfd080ffd0b8a0dcc0fc2e4d8666"
    private val reto = hex("00112233445566778899aabbccddeeff")
    private val sesionEsperada = "fc7a9ef318e8000f1636280f04002a294c8a2a39a127f6596919c6672c425402"
    private val nonce = hex("000102030405060708090a0b")
    private val claro = """{"n":1,"cmd":"estado","id":1}"""
    private val sobreEsperado = """{"c":"AAECAwQFBgcICQoLrG0G4TJP4eVdrRGusTJ3EilOrwdGXi6jklhe2L8lbcyNocwuf+j87jHfxtYL"}"""

    private val claveSesion = hex(sesionEsperada)

    private fun hex(texto: String) = Criptografia.bytesDeHex(texto)!!
    private fun ByteArray.aHex() = joinToString("") { "%02x".format(it) }

    /** Lo que enviaría el ESP32, para probar la recepción. */
    private fun sobreDelEsp(texto: String, aad: String = Criptografia.AAD_ESP, claveUsada: ByteArray = claveSesion): String {
        val datos = Criptografia.cifrar(claveUsada, Criptografia.nonceAleatorio(), texto.toByteArray(), aad)
        return """{"c":"${Base64.getEncoder().encodeToString(datos)}"}"""
    }

    private fun evento(n: Long) = """{"n":$n,"tipo":"evento","evento":"estado","datos":{}}"""

    @Test
    fun vectorClaveMaestra() = assertEquals(maestraEsperada, Criptografia.claveMaestra(clave).aHex())

    @Test
    fun vectorClaveSesion() = assertEquals(sesionEsperada, Criptografia.claveSesion(hex(maestraEsperada), reto).aHex())

    @Test
    fun vectorSobreCompleto() {
        val sesion = SesionSegura(claveSesion) { nonce }
        assertEquals(sobreEsperado, sesion.sellar("""{"cmd":"estado","id":1}"""))
    }

    @Test
    fun vectorSeDescifraConAadApp() {
        val datos = Base64.getDecoder().decode(Json.parseToJsonElement(sobreEsperado).jsonObject["c"]!!.jsonPrimitive.content)
        assertEquals(claro, Criptografia.descifrar(claveSesion, datos, Criptografia.AAD_APP)!!.toString(Charsets.UTF_8))
        assertNull("con otro AAD no debe descifrar", Criptografia.descifrar(claveSesion, datos, Criptografia.AAD_ESP))
    }

    @Test
    fun elContadorDeEnvioSubeYElNonceCambia() {
        val sesion = SesionSegura(claveSesion)
        val primero = sesion.sellar("""{"cmd":"estado","id":1}""")
        val segundo = sesion.sellar("""{"cmd":"estado","id":1}""")
        assertNotEquals(primero, segundo)
        fun n(sobre: String): String {
            val datos = Base64.getDecoder().decode(Json.parseToJsonElement(sobre).jsonObject["c"]!!.jsonPrimitive.content)
            val texto = Criptografia.descifrar(claveSesion, datos, Criptografia.AAD_APP)!!.toString(Charsets.UTF_8)
            return Json.parseToJsonElement(texto).jsonObject["n"]!!.jsonPrimitive.content
        }
        assertEquals("1", n(primero))
        assertEquals("2", n(segundo))
    }

    @Test
    fun abreUnSobreDelEsp() {
        val sesion = SesionSegura(claveSesion)
        assertFalse(sesion.confirmada)
        assertEquals(MensajeAbierto.Valido(evento(1)), sesion.abrir(sobreDelEsp(evento(1))))
        assertTrue(sesion.confirmada)
    }

    @Test
    fun descartaLosContadoresQueNoCrecen() {
        val sesion = SesionSegura(claveSesion)
        assertTrue(sesion.abrir(sobreDelEsp(evento(5))) is MensajeAbierto.Valido)
        assertEquals(MensajeAbierto.Repetido, sesion.abrir(sobreDelEsp(evento(5))))
        assertEquals(MensajeAbierto.Repetido, sesion.abrir(sobreDelEsp(evento(4))))
        assertTrue(sesion.abrir(sobreDelEsp(evento(6))) is MensajeAbierto.Valido)
    }

    @Test
    fun unMensajeRepetidoExactamenteSeDescarta() {
        val sesion = SesionSegura(claveSesion)
        val capturado = sobreDelEsp(evento(1))
        assertTrue(sesion.abrir(capturado) is MensajeAbierto.Valido)
        assertEquals(MensajeAbierto.Repetido, sesion.abrir(capturado))
    }

    @Test
    fun sinContadorNoSeAcepta() {
        assertEquals(MensajeAbierto.Repetido, SesionSegura(claveSesion).abrir(sobreDelEsp("""{"tipo":"evento"}""")))
    }

    @Test
    fun rechazaOtraClaveOtroSentidoYDatosAlterados() {
        val sesion = SesionSegura(claveSesion)
        assertEquals(MensajeAbierto.NoDescifra, sesion.abrir(sobreDelEsp(evento(1), claveUsada = ByteArray(32))))
        assertEquals(MensajeAbierto.NoDescifra, sesion.abrir(sobreDelEsp(evento(1), aad = Criptografia.AAD_APP)))
        assertEquals(MensajeAbierto.NoDescifra, sesion.abrir("""{"c":"no es base64!"}"""))
        assertEquals(MensajeAbierto.NoDescifra, sesion.abrir("""{"c":"AAEC"}"""))
        val valido = Base64.getDecoder().decode(
            Json.parseToJsonElement(sobreDelEsp(evento(1))).jsonObject["c"]!!.jsonPrimitive.content
        )
        valido[valido.size - 1] = (valido.last().toInt() xor 1).toByte()
        assertEquals(MensajeAbierto.NoDescifra, sesion.abrir("""{"c":"${Base64.getEncoder().encodeToString(valido)}"}"""))
        assertFalse(sesion.confirmada)
    }

    @Test
    fun loQueNoTieneFormaDeSobreSeInformaAsi() {
        val sesion = SesionSegura(claveSesion)
        assertEquals(MensajeAbierto.NoEsSobre, sesion.abrir("""{"tipo":"evento","evento":"estado","datos":{}}"""))
        assertEquals(MensajeAbierto.NoEsSobre, sesion.abrir("esto no es json"))
        assertEquals(MensajeAbierto.NoEsSobre, sesion.abrir("[1,2]"))
    }

    @Test
    fun laImagenUsaSuPropioAad() {
        val jpeg = ByteArray(1_000) { it.toByte() }
        val cifrada = Criptografia.cifrar(claveSesion, nonce, jpeg, Criptografia.AAD_IMAGEN)
        assertEquals(jpeg.size + 28, cifrada.size)
        assertArrayEquals(jpeg, SesionSegura(claveSesion).descifrarImagen(cifrada))
        val deMensaje = Criptografia.cifrar(claveSesion, nonce, jpeg, Criptografia.AAD_ESP)
        assertNull(SesionSegura(claveSesion).descifrarImagen(deMensaje))
    }

    @Test
    fun validaLaClaveDelEquipo() {
        assertTrue(Criptografia.esClaveValida("k7m3pq9xw2ha"))
        assertTrue(Criptografia.esClaveValida("Clave con espacios ~!"))
        assertTrue(Criptografia.esClaveValida("a".repeat(8)))
        assertTrue(Criptografia.esClaveValida("a".repeat(63)))
        assertFalse(Criptografia.esClaveValida("a".repeat(7)))
        assertFalse(Criptografia.esClaveValida("a".repeat(64)))
        assertFalse(Criptografia.esClaveValida("contraseña1"))
        assertFalse(Criptografia.esClaveValida("salto\nlinea"))
    }

    @Test
    fun hexInvalidoDevuelveNull() {
        assertNull(Criptografia.bytesDeHex("abc"))
        assertNull(Criptografia.bytesDeHex("zz"))
    }
}
