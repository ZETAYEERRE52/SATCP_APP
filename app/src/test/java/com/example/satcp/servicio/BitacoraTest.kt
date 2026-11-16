package com.example.satcp.servicio

import com.example.satcp.protocolo.Alerta
import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Estufa
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.OrigenAlerta
import com.example.satcp.protocolo.Temperatura
import com.example.satcp.protocolo.Vision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitacoraTest {

    private val referencia = Temperatura(medida = 22.0, estimada = 22.0, lineaBase = 21.0)

    private fun estado(fase: Fase, alerta: Alerta? = null, temperatura: Temperatura = referencia) =
        DatosEstado(fase = fase, temperatura = temperatura, alerta = alerta, estufa = Estufa(umbralIncremento = 4.0))

    private fun alerta(origen: OrigenAlerta, temperatura: Double?, vision: Vision = Vision.HUMO, rearme: Boolean = false) =
        Alerta(vision = vision, temperatura = temperatura, origen = origen, admiteRespuesta = !rearme, requiereRearme = rearme)

    // ---------- Causa de la alerta ----------

    @Test
    fun alertaQueSuperoLaReferenciaMasElUmbral() {
        // Referencia 21 + umbral 4 = revisión desde 25 °C.
        assertEquals(DisparoAlerta.SUPERO_REFERENCIA, disparoDe(alerta(OrigenAlerta.TEMPERATURA, 26.0), estado(Fase.ALERTA_USUARIO)))
    }

    @Test
    fun alertaPorTemperaturaSinLlegarAlUmbralEsUnaSubidaBrusca() {
        val disparo = disparoDe(alerta(OrigenAlerta.TEMPERATURA, 23.5), estado(Fase.ALERTA_USUARIO))
        assertEquals(DisparoAlerta.SUBIDA_BRUSCA, disparo)
        assertTrue(disparo.esSospechoso)
    }

    @Test
    fun alertaSinLecturaEsFallaDelSensor() {
        assertEquals(DisparoAlerta.SENSOR_SIN_RESPUESTA, disparoDe(alerta(OrigenAlerta.TEMPERATURA, null), estado(Fase.ALERTA_USUARIO)))
    }

    @Test
    fun alertaDeLaVerificacionAutomaticaEsSospechosa() {
        val disparo = disparoDe(alerta(OrigenAlerta.AUTOCALIBRACION, 22.0), estado(Fase.ALERTA_USUARIO))
        assertEquals(DisparoAlerta.VERIFICACION_AUTOMATICA, disparo)
        assertTrue(disparo.esSospechoso)
        assertFalse(DisparoAlerta.SUPERO_REFERENCIA.esSospechoso)
    }

    // ---------- Transiciones ----------

    @Test
    fun transicionesDelFirmware() {
        assertTrue(transicionPermitida(Fase.MONITOREO, Fase.VALIDAR_PRESENCIA))
        assertTrue(transicionPermitida(Fase.AUTOCALIBRACION, Fase.ALERTA_USUARIO))
        assertTrue(transicionPermitida(Fase.ALERTA_USUARIO, Fase.CIERRE_AUTOMATICO))
        assertTrue(transicionPermitida(Fase.BLOQUEADO, Fase.MONITOREO))
    }

    @Test
    fun seToleraNoVerUnaFaseBreve() {
        // VALIDAR_PRESENCIA sin nadie responde al instante; CIERRE_AUTOMATICO dura lo que la válvula.
        assertTrue(transicionPermitida(Fase.MONITOREO, Fase.DETECCION_VISUAL))
        assertTrue(transicionPermitida(Fase.ALERTA_USUARIO, Fase.BLOQUEADO))
        assertTrue(transicionPermitida(Fase.MONITOREO, Fase.BLOQUEADO))
    }

    @Test
    fun transicionesImposibles() {
        assertFalse(transicionPermitida(Fase.MONITOREO, Fase.ALERTA_USUARIO))
        assertFalse(transicionPermitida(Fase.BLOQUEADO, Fase.ALERTA_USUARIO))
        assertFalse(transicionPermitida(Fase.VALIDAR_PRESENCIA, Fase.ALERTA_USUARIO))
        assertFalse(transicionPermitida(Fase.CALIBRACION_ESTUFA, Fase.VALIDAR_PRESENCIA))
    }

    // ---------- Analizador ----------

    @Test
    fun registraLaAlertaYElCierreConSuCausa() {
        val analizador = AnalizadorBitacora()
        assertEquals(TipoEventoBitacora.ESTADO_AL_CONECTAR, analizador.analizar(estado(Fase.MONITOREO), 1).single().tipo)

        analizador.analizar(estado(Fase.DETECCION_VISUAL), 2)
        val alerta = analizador.analizar(estado(Fase.ALERTA_USUARIO, alerta(OrigenAlerta.TEMPERATURA, 23.0)), 3)
        assertEquals(listOf(TipoEventoBitacora.CAMBIO_FASE, TipoEventoBitacora.ALERTA), alerta.map { it.tipo })
        val evento = alerta.last()
        assertEquals(DisparoAlerta.SUBIDA_BRUSCA, evento.disparo)
        assertEquals(25.0, evento.umbralRevision!!, 0.0)
        assertTrue(evento.destacado)

        val cierre = analizador.analizar(estado(Fase.BLOQUEADO, alerta(OrigenAlerta.TEMPERATURA, 23.0, rearme = true)), 4)
        assertEquals(listOf(TipoEventoBitacora.CAMBIO_FASE, TipoEventoBitacora.SUMINISTRO_CORTADO), cierre.map { it.tipo })

        val rearme = analizador.analizar(estado(Fase.MONITOREO), 5)
        assertEquals(listOf(TipoEventoBitacora.CAMBIO_FASE, TipoEventoBitacora.REARMADO), rearme.map { it.tipo })
    }

    @Test
    fun marcaLaTransicionInesperada() {
        val analizador = AnalizadorBitacora()
        analizador.analizar(estado(Fase.MONITOREO), 1)
        val eventos = analizador.analizar(estado(Fase.ALERTA_USUARIO, alerta(OrigenAlerta.TEMPERATURA, 30.0)), 2)
        assertEquals(TipoEventoBitacora.TRANSICION_INESPERADA, eventos.first().tipo)
        assertEquals(Fase.MONITOREO, eventos.first().faseAnterior)
        assertTrue(eventos.first().destacado)
    }

    @Test
    fun trasUnCorteNoComparaConElEstadoDeAntes() {
        val analizador = AnalizadorBitacora()
        analizador.analizar(estado(Fase.MONITOREO), 1)
        analizador.reiniciar()
        // Sin reiniciar, MONITOREO -> ALERTA_USUARIO sería una transición inesperada.
        val eventos = analizador.analizar(estado(Fase.ALERTA_USUARIO, alerta(OrigenAlerta.TEMPERATURA, 30.0)), 2)
        assertEquals(TipoEventoBitacora.ESTADO_AL_CONECTAR, eventos.single().tipo)
    }

    @Test
    fun registraLaFallaDelSensorYLaReferenciaNueva() {
        val analizador = AnalizadorBitacora()
        analizador.analizar(estado(Fase.MONITOREO, temperatura = Temperatura(lineaBase = null)), 1)
        val referencia = analizador.analizar(estado(Fase.MONITOREO, temperatura = Temperatura(lineaBase = 21.5)), 2)
        assertEquals(TipoEventoBitacora.REFERENCIA_NUEVA, referencia.single().tipo)
        val falla = analizador.analizar(estado(Fase.MONITOREO, temperatura = Temperatura(sensorOk = false, lineaBase = 21.5)), 3)
        assertEquals(TipoEventoBitacora.SENSOR_FALLA, falla.single().tipo)
    }

    @Test
    fun sinCambiosNoRegistraNada() {
        val analizador = AnalizadorBitacora()
        analizador.analizar(estado(Fase.MONITOREO), 1)
        assertTrue(analizador.analizar(estado(Fase.MONITOREO), 2).isEmpty())
    }

    // ---------- Guardado ----------

    @Test
    fun cadaEventoVaYVuelveDelArchivo() {
        val evento = eventoCon(TipoEventoBitacora.ALERTA, estado(Fase.ALERTA_USUARIO, alerta(OrigenAlerta.AUTOCALIBRACION, 22.0, Vision.ERROR_DE_VISION)), 1234)
        val linea = AlmacenBitacora.codificarLinea(evento)
        assertEquals(evento, AlmacenBitacora.decodificarLinea(linea))
        assertNull(AlmacenBitacora.decodificarLinea("""{"ms":1,"tipo":"ALER"""))
        assertNull(AlmacenBitacora.decodificarLinea(""))
    }

    @Test
    fun seRecortaA30DiasY2000Eventos() {
        val dia = 24L * 60 * 60 * 1000
        val ahora = 100 * dia
        val viejos = listOf(EventoBitacora(ahora - 31 * dia, TipoEventoBitacora.CONEXION_PERDIDA))
        val recientes = (1..2_100).map { EventoBitacora(ahora - it, TipoEventoBitacora.CONEXION_RECUPERADA) }.reversed()
        val recortados = recortarBitacora(viejos + recientes, ahora)
        assertEquals(MAXIMO_EVENTOS_BITACORA, recortados.size)
        assertTrue(recortados.all { it.ms > ahora - 31 * dia })
        assertEquals(ahora - 1, recortados.last().ms)
    }
}
