package com.example.satcp.protocolo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Versión del protocolo que entiende esta app (docs/PROTOCOLO_APP.md). */
const val VERSION_PROTOCOLO = 2

/** Límite del sobre cifrado completo en bytes, tanto por WebSocket como por Bluetooth (§1). */
const val LIMITE_BYTES_SOBRE = 767

/**
 * Límite del comando antes de cifrarlo. Un sobre de 767 bytes admite 539 de JSON, contando el campo
 * `n`, que ocupa hasta 19 bytes a más del `id` ya incluido.
 */
const val LIMITE_BYTES_COMANDO = 520

/** Mensajes que llegan del ESP32, ya clasificados por `tipo` y por `cmd` o `evento`. */
sealed interface MensajeEntrante

data class Respuesta(
    val cmd: String,
    val id: Long?,
    val ok: Boolean,
    val datos: JsonObject?,
    val error: String?,
    val mensaje: String?,
) : MensajeEntrante

data class EventoEstado(val datos: DatosEstado) : MensajeEntrante

data class ImagenInicio(
    val id: Long,
    val bytes: Int,
    val crc32: Long,
    val fragmento: Int?,
) : MensajeEntrante

data class ImagenFin(val id: Long) : MensajeEntrante

data class ImagenError(val id: Long, val error: String?) : MensajeEntrante

/** Respuesta a `hola`, en claro (§1b). No trae autenticación: solo sirve para abrir la sesión. */
data class MensajeHola(
    val ok: Boolean,
    val protocolo: Int?,
    val reto: String?,
    val error: String?,
) : MensajeEntrante

/** `no_autorizado`, `sin_sesion` o `repetido`, en claro y sin `cmd` (§1b). */
data class ErrorSeguridad(val error: String) : MensajeEntrante

/** Mensaje bien formado pero que esta versión de la app no conoce. Se ignora. */
data class MensajeDesconocido(val texto: String) : MensajeEntrante

/** Códigos de `error` de §7. */
object CodigosError {
    const val JSON_INVALIDO = "json_invalido"
    const val PARAMETROS_INVALIDOS = "parametros_invalidos"
    const val COMANDO_DESCONOCIDO = "comando_desconocido"
    const val CAMARA_OCUPADA = "camara_ocupada"
    const val CAMARA_NO_DISPONIBLE = "camara_no_disponible"
    const val SIN_ALERTA = "sin_alerta"
    const val NO_BLOQUEADO = "no_bloqueado"
    const val YA_BLOQUEADO = "ya_bloqueado"
    const val SIN_ESPACIO = "sin_espacio"
    const val WIFI_ERROR = "wifi_error"
    const val SIN_SUSCRIPCION = "sin_suscripcion"
    const val VALVULA_CERRADA = "valvula_cerrada"
    const val SOLO_WIFI = "solo_wifi"
    const val NO_AUTORIZADO = "no_autorizado"
    const val SIN_SESION = "sin_sesion"
    const val REPETIDO = "repetido"
}

// ---------- Evento `estado` (§5) ----------

@Serializable
data class DatosEstado(
    val fase: Fase = Fase.DESCONOCIDA,
    val temperatura: Temperatura = Temperatura(),
    val presencia: Presencia = Presencia(),
    val alerta: Alerta? = null,
    val valvula: Valvula = Valvula(),
    val conexion: ConexionEstado = ConexionEstado(),
)

@Serializable
data class Valvula(
    val estado: EstadoValvula = EstadoValvula.DESCONOCIDO,
    /** `true`: el estado está medido. `false`: es el que supone el ESP32, porque no tiene sensor de posición. */
    val sensor: Boolean = false,
)

@Serializable
enum class EstadoValvula {
    @SerialName("abierta") ABIERTA,
    @SerialName("cerrada") CERRADA,
    DESCONOCIDO,
}

@Serializable
enum class Fase {
    MONITOREO,
    VALIDAR_PRESENCIA,
    DETECCION_VISUAL,
    ALERTA_USUARIO,
    CIERRE_AUTOMATICO,
    BLOQUEADO,
    AUTOCALIBRACION,
    DESCONOCIDA,
}

@Serializable
data class Temperatura(
    /** `false`: el sensor no responde y la detección depende solo de la cámara (§5). */
    @SerialName("sensor_ok") val sensorOk: Boolean = true,
    val medida: Double? = null,
    val estimada: Double? = null,
    val sensor: Double? = null,
    @SerialName("linea_base") val lineaBase: Double? = null,
)

@Serializable
data class Presencia(
    val estado: EstadoPresencia = EstadoPresencia.DESCONOCIDO,
    @SerialName("distancia_movimiento_cm") val distanciaMovimientoCm: Int = 0,
    @SerialName("distancia_quieta_cm") val distanciaQuietaCm: Int = 0,
)

@Serializable
enum class EstadoPresencia {
    @SerialName("nadie") NADIE,
    @SerialName("en_movimiento") EN_MOVIMIENTO,
    @SerialName("quieta") QUIETA,
    @SerialName("en_movimiento_y_quieta") EN_MOVIMIENTO_Y_QUIETA,
    @SerialName("sin_datos") SIN_DATOS,
    DESCONOCIDO,
}

@Serializable
data class Alerta(
    val vision: Vision = Vision.DESCONOCIDA,
    /** En los cierres `manual` y `horario` es la del momento del cierre, o null si el sensor no tenía lectura. */
    val temperatura: Double? = null,
    val origen: OrigenAlerta = OrigenAlerta.DESCONOCIDO,
    @SerialName("cierre_en_s") val cierreEnS: Int = 0,
    @SerialName("admite_respuesta") val admiteRespuesta: Boolean = false,
    @SerialName("requiere_rearme") val requiereRearme: Boolean = false,
)

@Serializable
enum class Vision {
    FUEGO,
    HUMO,
    @SerialName("error de visión") ERROR_DE_VISION,
    @SerialName("sin riesgo") SIN_RIESGO,
    DESCONOCIDA,
}

@Serializable
enum class OrigenAlerta {
    @SerialName("temperatura") TEMPERATURA,
    @SerialName("autocalibracion") AUTOCALIBRACION,

    /** Comando `cerrar`: el usuario cortó el suministro desde la app. */
    @SerialName("manual") MANUAL,

    /** Un horario programado. */
    @SerialName("horario") HORARIO,

    /** El equipo arrancó con la válvula cerrada de antes y sigue `BLOQUEADO`. */
    @SerialName("reinicio") REINICIO,
    DESCONOCIDO,
    ;

    /** Cierres que no vienen de una revisión de la cámara: su `vision` no se muestra. */
    val esCierreSinVision: Boolean get() = this == MANUAL || this == HORARIO || this == REINICIO
}

@Serializable
data class ConexionEstado(
    @SerialName("wifi_casa_ip") val wifiCasaIp: String? = null,
    @SerialName("ble_conectado") val bleConectado: Boolean = false,
)

// ---------- Datos de las respuestas (§4) ----------

@Serializable
data class DatosInfo(
    val dispositivo: String? = null,
    val firmware: String? = null,
    val protocolo: Int? = null,
    val mac: String? = null,
    val wifi: WifiInfo = WifiInfo(),
    @SerialName("ble_nombre") val bleNombre: String? = null,
    val clases: List<String> = emptyList(),
)

@Serializable
data class WifiInfo(
    @SerialName("ap_ssid") val apSsid: String? = null,
    @SerialName("ap_ip") val apIp: String? = null,
    val mdns: String? = null,
    @SerialName("puerto_vivo") val puertoVivo: Int? = null,
    @SerialName("casa_ip") val casaIp: String? = null,
)

@Serializable
data class DatosFoto(
    val clasificada: Boolean = false,
    val clase: String? = null,
    val probabilidades: Map<String, Double>? = null,
    @SerialName("tiempo_ms") val tiempoMs: Int? = null,
    val guardada: Boolean = false,
    @SerialName("ruta_sd") val rutaSd: String? = null,
    val bytes: Int? = null,
    val imagen: ImagenFoto? = null,
)

@Serializable
data class ImagenFoto(
    val entrega: EntregaImagen = EntregaImagen.DESCONOCIDA,
    val url: String? = null,
)

/** Permiso para abrir el video: [url] trae un token de una sola apertura que vale 30 s (§4). */
@Serializable
data class DatosVivo(
    val puerto: Int? = null,
    val url: String? = null,
    @SerialName("vigencia_s") val vigenciaS: Int? = null,
)

@Serializable
enum class EntregaImagen {
    @SerialName("http") HTTP,
    @SerialName("ble") BLE,
    @SerialName("no_solicitada") NO_SOLICITADA,
    @SerialName("no_disponible") NO_DISPONIBLE,
    DESCONOCIDA,
}
