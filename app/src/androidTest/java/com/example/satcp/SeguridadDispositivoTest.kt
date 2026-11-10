package com.example.satcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.satcp.seguridad.Criptografia
import com.example.satcp.seguridad.SesionSegura
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Los vectores de §1b en un Android real: los proveedores criptográficos no son los de la computadora. */
@RunWith(AndroidJUnit4::class)
class SeguridadDispositivoTest {

    private fun ByteArray.aHex() = joinToString("") { "%02x".format(it) }

    private val claveSesion = Criptografia.bytesDeHex("fc7a9ef318e8000f1636280f04002a294c8a2a39a127f6596919c6672c425402")!!

    @Test
    fun vectoresDeClaves() {
        val maestra = Criptografia.claveMaestra("k7m3pq9xw2ha")
        assertEquals("df80c3463906c28f62504e9a8f4387ec5db9bfd080ffd0b8a0dcc0fc2e4d8666", maestra.aHex())
        val reto = Criptografia.bytesDeHex("00112233445566778899aabbccddeeff")!!
        assertEquals(claveSesion.aHex(), Criptografia.claveSesion(maestra, reto).aHex())
    }

    @Test
    fun vectorDelSobre() {
        val nonce = Criptografia.bytesDeHex("000102030405060708090a0b")!!
        assertEquals(
            """{"c":"AAECAwQFBgcICQoLrG0G4TJP4eVdrRGusTJ3EilOrwdGXi6jklhe2L8lbcyNocwuf+j87jHfxtYL"}""",
            SesionSegura(claveSesion) { nonce }.sellar("""{"cmd":"estado","id":1}"""),
        )
    }

    @Test
    fun laImagenSeDescifra() {
        val jpeg = ByteArray(50_000) { it.toByte() }
        val cifrada = Criptografia.cifrar(claveSesion, Criptografia.nonceAleatorio(), jpeg, Criptografia.AAD_IMAGEN)
        assertArrayEquals(jpeg, SesionSegura(claveSesion).descifrarImagen(cifrada))
    }
}
