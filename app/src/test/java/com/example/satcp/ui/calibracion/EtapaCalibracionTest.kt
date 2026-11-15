package com.example.satcp.ui.calibracion

import com.example.satcp.protocolo.DatosEstado
import com.example.satcp.protocolo.Estufa
import com.example.satcp.protocolo.Fase
import com.example.satcp.protocolo.PasoCalibracion
import com.example.satcp.protocolo.ProgresoCalibracion
import com.example.satcp.protocolo.ResultadoCalibracion
import org.junit.Assert.assertEquals
import org.junit.Test

class EtapaCalibracionTest {

    private val monitoreo = DatosEstado(fase = Fase.MONITOREO, estufa = Estufa(ultimoResultado = ResultadoCalibracion.OK))
    private fun enPaso(paso: PasoCalibracion) = DatosEstado(
        fase = Fase.CALIBRACION_ESTUFA,
        estufa = Estufa(calibracion = ProgresoCalibracion(paso = paso, segundosRestantes = 5)),
    )

    @Test
    fun unResultadoViejoNoSeMuestraAlAbrirLaPantalla() {
        val seg = avanzarSeguimiento(Seguimiento.NINGUNO, monitoreo)
        assertEquals(Seguimiento.NINGUNO, seg)
        assertEquals(EtapaCalibracion.INICIO, etapaCalibracion(seg, monitoreo, enviando = false))
    }

    @Test
    fun recorreLosPasosHastaElResultado() {
        var seg = Seguimiento.ACEPTADA
        assertEquals(EtapaCalibracion.PREPARANDO, etapaCalibracion(seg, monitoreo, enviando = false))

        val referencia = enPaso(PasoCalibracion.REFERENCIA)
        seg = avanzarSeguimiento(seg, referencia)
        assertEquals(Seguimiento.EN_CURSO, seg)
        assertEquals(EtapaCalibracion.REFERENCIA, etapaCalibracion(seg, referencia, enviando = false))

        val midiendo = enPaso(PasoCalibracion.MIDIENDO)
        seg = avanzarSeguimiento(seg, midiendo)
        assertEquals(EtapaCalibracion.MIDIENDO, etapaCalibracion(seg, midiendo, enviando = false))

        seg = avanzarSeguimiento(seg, monitoreo)
        assertEquals(Seguimiento.TERMINADA, seg)
        assertEquals(EtapaCalibracion.RESULTADO, etapaCalibracion(seg, monitoreo, enviando = false))
    }

    @Test
    fun alVolverALaPantallaRetomaLaCalibracionEnCurso() {
        // El ViewModel no la había visto (NINGUNO), pero el equipo dice que sigue.
        val midiendo = enPaso(PasoCalibracion.MIDIENDO)
        val seg = avanzarSeguimiento(Seguimiento.NINGUNO, midiendo)
        assertEquals(Seguimiento.EN_CURSO, seg)
        assertEquals(EtapaCalibracion.MIDIENDO, etapaCalibracion(seg, midiendo, enviando = false))
    }

    @Test
    fun alPerderLaConexionConservaElSeguimientoYAlReconectarMuestraElResultado() {
        var seg = avanzarSeguimiento(Seguimiento.EN_CURSO, null)
        assertEquals(Seguimiento.EN_CURSO, seg)
        assertEquals(EtapaCalibracion.PREPARANDO, etapaCalibracion(seg, null, enviando = false))
        seg = avanzarSeguimiento(seg, monitoreo)
        assertEquals(EtapaCalibracion.RESULTADO, etapaCalibracion(seg, monitoreo, enviando = false))
    }

    @Test
    fun laFaseSinBloqueDeProgresoSeMuestraComoPreparando() {
        val estado = DatosEstado(fase = Fase.CALIBRACION_ESTUFA)
        assertEquals(EtapaCalibracion.PREPARANDO, etapaCalibracion(Seguimiento.NINGUNO, estado, enviando = false))
    }

    @Test
    fun mientrasSeEnviaElComando() {
        assertEquals(EtapaCalibracion.ENVIANDO, etapaCalibracion(Seguimiento.NINGUNO, monitoreo, enviando = true))
    }
}
