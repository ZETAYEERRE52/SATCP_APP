package com.example.satcp.servicio

import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.EstadoPresencia
import com.example.satcp.protocolo.EstadoValvula
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.OrigenAlerta
import com.example.satcp.protocolo.ResultadoCalibracion
import com.example.satcp.protocolo.Vision
import kotlinx.serialization.Serializable

/**
 * Bitácora de eventos. El equipo no guarda historial ni lo envía: la app la arma con los `estado` que
 * recibe, así que solo cubre el tiempo con conexión (los cortes quedan anotados).
 */
@Serializable
enum class TipoEventoBitacora {
    /** Primer `estado` de una conexión: foto de cómo estaba el equipo. */
    ESTADO_AL_CONECTAR,
    CAMBIO_FASE,

    /** Un cambio de fase que la máquina de estados del firmware no permite (ni saltándose una fase breve). */
    TRANSICION_INESPERADA,
    ALERTA,
    SUMINISTRO_CORTADO,

    /** La alerta terminó sin cierre: el usuario respondió o la fase volvió a vigilar. */
    ALERTA_TERMINADA,
    REARMADO,
    VALVULA,
    SENSOR_FALLA,
    SENSOR_RECUPERADO,
    REFERENCIA_NUEVA,
    REFERENCIA_DESCARTADA,
    UMBRAL_NUEVO,
    CALIBRACION_TERMINADA,
    CONEXION_PERDIDA,
    CONEXION_RECUPERADA,
    DESCONECTADO,
}

@Serializable
data class EventoBitacora(
    /** `System.currentTimeMillis()` del teléfono al recibirlo. */
    val ms: Long,
    val tipo: TipoEventoBitacora,
    val fase: Fase? = null,
    val faseAnterior: Fase? = null,
    val origen: OrigenAlerta? = null,
    val vision: Vision? = null,
    val disparo: DisparoAlerta? = null,
    /** `alerta.temperatura`. */
    val temperaturaAlerta: Double? = null,
    val medida: Double? = null,
    val estimada: Double? = null,
    val referencia: Double? = null,
    val umbralIncremento: Double? = null,
    val umbralRevision: Double? = null,
    val presencia: EstadoPresencia? = null,
    val sensorOk: Boolean? = null,
    val valvula: EstadoValvula? = null,
    val resultadoCalibracion: ResultadoCalibracion? = null,
    /** En [TipoEventoBitacora.CONEXION_PERDIDA]: el nombre de `MotivoPerdida`. */
    val motivoPerdida: String? = null,
) {
    /** Eventos que se muestran con el filtro "Importantes". */
    val importante: Boolean
        get() = tipo != TipoEventoBitacora.CAMBIO_FASE && tipo != TipoEventoBitacora.REFERENCIA_NUEVA &&
            tipo != TipoEventoBitacora.REFERENCIA_DESCARTADA && tipo != TipoEventoBitacora.ESTADO_AL_CONECTAR

    /** Lo que conviene revisar: transiciones imposibles y alertas que no vienen de superar la referencia. */
    val destacado: Boolean
        get() = tipo == TipoEventoBitacora.TRANSICION_INESPERADA ||
            ((tipo == TipoEventoBitacora.ALERTA || tipo == TipoEventoBitacora.SUMINISTRO_CORTADO) &&
                (disparo?.esSospechoso == true || vision == Vision.ERROR_DE_VISION))
}

/** Un evento con las lecturas del momento. */
fun eventoCon(tipo: TipoEventoBitacora, estado: DatosEstado, ms: Long, anterior: DatosEstado? = null): EventoBitacora {
    val alerta = estado.alerta
    return EventoBitacora(
        ms = ms,
        tipo = tipo,
        fase = estado.fase,
        faseAnterior = anterior?.fase,
        origen = alerta?.origen,
        vision = alerta?.vision,
        disparo = alerta?.let { disparoDe(it, estado) },
        temperaturaAlerta = alerta?.temperatura,
        medida = estado.temperatura.medida,
        estimada = estado.temperatura.estimada,
        referencia = estado.temperatura.lineaBase,
        umbralIncremento = estado.estufa.umbralIncremento,
        umbralRevision = estado.umbralRevision,
        presencia = estado.presencia.estado,
        sensorOk = estado.temperatura.sensorOk,
        valvula = estado.valvula.estado,
        resultadoCalibracion = estado.estufa.ultimoResultado,
    )
}

/**
 * Transiciones que permite `maquina_estados.c` (firmware 2.1.0). El cierre manual puede ocurrir desde
 * cualquier fase salvo `BLOQUEADO`.
 */
private val TRANSICIONES: Map<Fase, Set<Fase>> = mapOf(
    Fase.MONITOREO to setOf(Fase.VALIDAR_PRESENCIA, Fase.AUTOCALIBRACION, Fase.CALIBRACION_ESTUFA, Fase.CIERRE_AUTOMATICO),
    Fase.VALIDAR_PRESENCIA to setOf(Fase.MONITOREO, Fase.DETECCION_VISUAL, Fase.CIERRE_AUTOMATICO),
    Fase.DETECCION_VISUAL to setOf(Fase.MONITOREO, Fase.ALERTA_USUARIO, Fase.CIERRE_AUTOMATICO),
    Fase.ALERTA_USUARIO to setOf(Fase.MONITOREO, Fase.CIERRE_AUTOMATICO),
    Fase.CIERRE_AUTOMATICO to setOf(Fase.BLOQUEADO),
    Fase.BLOQUEADO to setOf(Fase.MONITOREO),
    Fase.AUTOCALIBRACION to setOf(Fase.MONITOREO, Fase.ALERTA_USUARIO, Fase.CIERRE_AUTOMATICO),
    Fase.CALIBRACION_ESTUFA to setOf(Fase.MONITOREO, Fase.CIERRE_AUTOMATICO),
)

/**
 * Fases que pueden durar menos que el muestreo del firmware (revisa la fase cada 250 ms): la app puede
 * no verlas. `VALIDAR_PRESENCIA` responde al instante si el radar no ve a nadie y `CIERRE_AUTOMATICO`
 * dura lo que tarda la válvula.
 */
private val FASES_BREVES = setOf(Fase.VALIDAR_PRESENCIA, Fase.CIERRE_AUTOMATICO)

fun transicionPermitida(desde: Fase, hasta: Fase): Boolean {
    if (desde == hasta || desde == Fase.DESCONOCIDA || hasta == Fase.DESCONOCIDA) return true
    val directas = TRANSICIONES[desde].orEmpty()
    if (hasta in directas) return true
    return directas.any { intermedia -> intermedia in FASES_BREVES && hasta in TRANSICIONES[intermedia].orEmpty() }
}

/** Compara cada `estado` con el anterior y produce los eventos de la bitácora. No es seguro entre hilos. */
class AnalizadorBitacora {

    private var anterior: DatosEstado? = null

    /** Tras un corte no hay continuidad: el siguiente `estado` no se compara con el de antes. */
    fun reiniciar() {
        anterior = null
    }

    fun analizar(nuevo: DatosEstado, ms: Long): List<EventoBitacora> {
        val previo = anterior
        anterior = nuevo
        if (previo == null) return listOf(eventoCon(TipoEventoBitacora.ESTADO_AL_CONECTAR, nuevo, ms))

        val eventos = mutableListOf<EventoBitacora>()
        fun anotar(tipo: TipoEventoBitacora) {
            eventos += eventoCon(tipo, nuevo, ms, previo)
        }

        if (nuevo.fase != previo.fase) {
            anotar(
                if (transicionPermitida(previo.fase, nuevo.fase)) TipoEventoBitacora.CAMBIO_FASE
                else TipoEventoBitacora.TRANSICION_INESPERADA
            )
        }

        val alertaPrevia = previo.alerta
        val alerta = nuevo.alerta
        when {
            alertaPrevia == null && alerta != null -> {
                anotar(TipoEventoBitacora.ALERTA)
                if (alerta.requiereRearme) anotar(TipoEventoBitacora.SUMINISTRO_CORTADO)
            }
            alertaPrevia != null && alerta != null && !alertaPrevia.requiereRearme && alerta.requiereRearme ->
                anotar(TipoEventoBitacora.SUMINISTRO_CORTADO)
            alertaPrevia != null && alerta == null -> anotar(
                if (alertaPrevia.requiereRearme || previo.fase == Fase.BLOQUEADO) TipoEventoBitacora.REARMADO
                else TipoEventoBitacora.ALERTA_TERMINADA
            )
        }

        if (previo.valvula.estado != nuevo.valvula.estado && nuevo.valvula.estado != EstadoValvula.DESCONOCIDO) {
            anotar(TipoEventoBitacora.VALVULA)
        }
        if (previo.temperatura.sensorOk && !nuevo.temperatura.sensorOk) anotar(TipoEventoBitacora.SENSOR_FALLA)
        if (!previo.temperatura.sensorOk && nuevo.temperatura.sensorOk) anotar(TipoEventoBitacora.SENSOR_RECUPERADO)

        val referenciaPrevia = previo.temperatura.lineaBase
        val referencia = nuevo.temperatura.lineaBase
        when {
            referencia != null && referencia != referenciaPrevia -> anotar(TipoEventoBitacora.REFERENCIA_NUEVA)
            referencia == null && referenciaPrevia != null -> anotar(TipoEventoBitacora.REFERENCIA_DESCARTADA)
        }
        if (previo.estufa.umbralIncremento != nuevo.estufa.umbralIncremento) anotar(TipoEventoBitacora.UMBRAL_NUEVO)
        if (previo.estufa.calibracion != null && nuevo.estufa.calibracion == null) {
            anotar(TipoEventoBitacora.CALIBRACION_TERMINADA)
        }
        return eventos
    }
}

/** Cuánto se guarda: los últimos [DIAS_BITACORA] días y como mucho [MAXIMO_EVENTOS_BITACORA] eventos. */
fun recortarBitacora(eventos: List<EventoBitacora>, ahoraMs: Long): List<EventoBitacora> {
    val desde = ahoraMs - DIAS_BITACORA * 24L * 60 * 60 * 1000
    return eventos.filter { it.ms >= desde }.takeLast(MAXIMO_EVENTOS_BITACORA)
}

const val DIAS_BITACORA = 30
const val MAXIMO_EVENTOS_BITACORA = 2_000
