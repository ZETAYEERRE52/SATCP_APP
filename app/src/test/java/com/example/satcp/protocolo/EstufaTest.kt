package com.example.satcp.protocolo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calibración con la estufa (firmware 2.1.0): comando, fase nueva y bloque `estufa`. */
class EstufaTest {

    private fun estadoCon(campos: String): DatosEstado =
        (CodificadorProtocolo.decodificar("""{"tipo":"evento","evento":"estado","datos":{$campos}}""") as EventoEstado).datos

    @Test
    fun codificaCalibrarEstufa() = assertEquals(
        Json.parseToJsonElement("""{"cmd":"calibrar_estufa","id":7}"""),
        Json.parseToJsonElement(CodificadorProtocolo.codificar(Comando.CalibrarEstufa, 7)),
    )

    @Test
    fun decodificaLaFaseNueva() {
        assertEquals(Fase.CALIBRACION_ESTUFA, estadoCon(""""fase":"CALIBRACION_ESTUFA"""").fase)
    }

    @Test
    fun decodificaEstufaSinCalibracionEnCurso() {
        val d = estadoCon(
            """"fase":"MONITOREO","temperatura":{"linea_base":20.0},
               "estufa":{"umbral_incremento":6.5,"calibrado":true,"calibracion":null,"ultimo_resultado":"ok"}"""
        )
        assertEquals(6.5, d.estufa.umbralIncremento, 0.0)
        assertTrue(d.estufa.calibrado)
        assertNull(d.estufa.calibracion)
        assertEquals(ResultadoCalibracion.OK, d.estufa.ultimoResultado)
        // linea_base + umbral_incremento
        assertEquals(26.5, d.umbralRevision!!, 0.0)
    }

    @Test
    fun decodificaElPasoReferencia() {
        val d = estadoCon(
            """"fase":"CALIBRACION_ESTUFA","estufa":{"umbral_incremento":4.0,"calibrado":false,
               "calibracion":{"paso":"referencia","segundos_restantes":8,"referencia":null,"incremento":0.0},
               "ultimo_resultado":null}"""
        )
        val c = d.estufa.calibracion!!
        assertEquals(PasoCalibracion.REFERENCIA, c.paso)
        assertEquals(8, c.segundosRestantes)
        assertNull(c.referencia)
        assertEquals(0.0, c.incremento!!, 0.0)
        assertFalse(d.estufa.calibrado)
        assertNull(d.estufa.ultimoResultado)
    }

    @Test
    fun decodificaElPasoMidiendo() {
        val c = estadoCon(
            """"fase":"CALIBRACION_ESTUFA","estufa":{"umbral_incremento":4.0,"calibrado":false,
               "calibracion":{"paso":"midiendo","segundos_restantes":150,"referencia":18.2,"incremento":3.1},
               "ultimo_resultado":"sin_calor"}"""
        ).estufa.calibracion!!
        assertEquals(PasoCalibracion.MIDIENDO, c.paso)
        assertEquals(150, c.segundosRestantes)
        assertEquals(18.2, c.referencia!!, 0.0)
        assertEquals(3.1, c.incremento!!, 0.0)
    }

    @Test
    fun decodificaTodosLosResultados() {
        mapOf(
            "ok" to ResultadoCalibracion.OK,
            "sin_calor" to ResultadoCalibracion.SIN_CALOR,
            "cancelada" to ResultadoCalibracion.CANCELADA,
            "error_sensor" to ResultadoCalibracion.ERROR_SENSOR,
        ).forEach { (texto, valor) ->
            assertEquals(valor, estadoCon(""""estufa":{"ultimo_resultado":"$texto"}""").estufa.ultimoResultado)
        }
    }

    @Test
    fun unFirmwareAnteriorSinBloqueEstufaUsaElUmbralDeFabrica() {
        val d = estadoCon(""""fase":"MONITOREO","temperatura":{"linea_base":21.0}""")
        assertEquals(Estufa.UMBRAL_FABRICA_C, d.estufa.umbralIncremento, 0.0)
        assertFalse(d.estufa.calibrado)
        assertNull(d.estufa.calibracion)
        assertEquals(25.0, d.umbralRevision!!, 0.0)
    }

    @Test
    fun sinReferenciaNoHayUmbralDeRevision() {
        assertNull(estadoCon(""""temperatura":{"linea_base":null},"estufa":{"umbral_incremento":6.0}""").umbralRevision)
    }

    @Test
    fun valoresDesconocidosNoRompenLaLectura() {
        val e = estadoCon(""""estufa":{"calibracion":{"paso":"otro"},"ultimo_resultado":"nuevo"}""").estufa
        assertEquals(PasoCalibracion.DESCONOCIDO, e.calibracion!!.paso)
        assertNull(e.ultimoResultado)
    }
}
