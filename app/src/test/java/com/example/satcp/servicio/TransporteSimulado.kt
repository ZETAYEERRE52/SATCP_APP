package com.example.satcp.servicio

import com.example.satcp.seguridad.Criptografia
import com.example.satcp.transporte.EventoTransporte
import com.example.satcp.transporte.TipoTransporte
import com.example.satcp.transporte.Transporte
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64

const val CLAVE_DE_PRUEBA = "k7m3pq9xw2ha"

/** Cliente con la clave de prueba, que calcula la clave maestra sin salir del reloj virtual. */
fun TestScope.clienteDePrueba(alcance: CoroutineScope = backgroundScope, clave: String? = CLAVE_DE_PRUEBA) =
    ClienteSatcp(alcance, leerClave = { clave }, despachadorDerivacion = UnconfinedTestDispatcher(testScheduler))

/**
 * Hace de ESP32 en memoria y habla §1b de verdad: responde `hola`, cifra lo que "envía" y descifra lo
 * que le llega. [enviados] contiene los comandos ya descifrados y sin el `hola`.
 */
class TransporteSimulado(
    override val tipo: TipoTransporte = TipoTransporte.WIFI,
    claveDelEquipo: String = CLAVE_DE_PRUEBA,
) : Transporte {

    private val maestra = Criptografia.claveMaestra(claveDelEquipo)
    private var claveSesion = ByteArray(32)
    private var contador = 0L

    var canal: Channel<EventoTransporte>? = null
    var conexiones = 0
    var holas = 0
    var fallarAlConectar = false
    var protocoloEnHola = 2

    /** El texto en claro con que responde a `hola`; null para responder normalmente. */
    var respuestaHola: String? = null

    val enviados = mutableListOf<String>()
    val descargas = mutableListOf<String>()
    var archivo: ByteArray? = null

    /** Conexiones que siguen abiertas: en un transporte real, un GATT o un WebSocket vivos. */
    var abiertas = 0
        private set

    override fun conectar(): Flow<EventoTransporte> = flow {
        conexiones++
        if (fallarAlConectar) throw IOException("sin red")
        val nuevo = Channel<EventoTransporte>(Channel.UNLIMITED)
        canal = nuevo
        abiertas++
        try {
            emit(EventoTransporte.Abierto)
            for (evento in nuevo) emit(evento)
        } finally {
            abiertas--
        }
    }

    override suspend fun enviar(texto: String) {
        if (texto.contains("\"cmd\":\"hola\"")) {
            holas++
            abrirSesion()
            return
        }
        val sobre = Json.parseToJsonElement(texto).jsonObject["c"]!!.jsonPrimitive.content
        val claro = Criptografia.descifrar(claveSesion, Base64.getDecoder().decode(sobre), Criptografia.AAD_APP)
        enviados += claro!!.toString(Charsets.UTF_8)
    }

    private fun abrirSesion() {
        val retoHex = "00112233445566778899aabbccddeeff"
        claveSesion = Criptografia.claveSesion(maestra, Criptografia.bytesDeHex(retoHex)!!)
        contador = 0
        recibirEnClaro(
            respuestaHola
                ?: """{"tipo":"hola","id":1,"ok":true,"protocolo":$protocoloEnHola,"reto":"$retoHex","sal":"otra","iteraciones":1}"""
        )
    }

    override suspend fun descargar(ruta: String): ByteArray? {
        descargas += ruta
        return archivo
    }

    /** Un mensaje del ESP32, cifrado como manda §1b. */
    fun recibir(json: String) = recibirEnClaro(sobre(json))

    /** Un sobre con el contador que se indique, para simular una repetición. */
    fun sobre(json: String, n: Long = ++contador): String {
        val conContador = """{"n":$n,${json.trim().removePrefix("{")}"""
        val datos = Criptografia.cifrar(claveSesion, Criptografia.nonceAleatorio(), conContador.toByteArray(), Criptografia.AAD_ESP)
        return """{"c":"${Base64.getEncoder().encodeToString(datos)}"}"""
    }

    fun recibirEnClaro(texto: String) = canal!!.trySend(EventoTransporte.Texto(texto))
    fun recibirBytes(bytes: ByteArray) = canal!!.trySend(EventoTransporte.BytesImagen(bytes))
    fun cerrarDesdeElEsp32() = canal!!.close()

    /** Los bytes de una foto tal como salen por la característica de imagen (§6). */
    fun imagenCifrada(jpeg: ByteArray): ByteArray =
        Criptografia.cifrar(claveSesion, Criptografia.nonceAleatorio(), jpeg, Criptografia.AAD_IMAGEN)
}
