package com.example.satcp.protocolo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ejemplos de docs/PROTOCOLO_APP.md agregados en el firmware 1.2.0. */
class ProtocoloV12Test {

    private fun assertMismoJson(esperado: String, obtenido: String) =
        assertEquals(Json.parseToJsonElement(esperado), Json.parseToJsonElement(obtenido))

    private fun respuesta(texto: String) = CodificadorProtocolo.decodificar(texto) as Respuesta

    // ---------- cerrar ----------

    @Test
    fun codificaCerrar() =
        assertMismoJson("""{"cmd":"cerrar","id":7}""", CodificadorProtocolo.codificar(Comando.Cerrar, 7))

    @Test
    fun decodificaRespuestaCerrar() {
        val r = respuesta("""{"tipo":"respuesta","cmd":"cerrar","id":7,"ok":true}""")
        assertEquals("cerrar", r.cmd)
        assertEquals(7L, r.id)
        assertTrue(r.ok)
    }

    @Test
    fun decodificaErrorYaBloqueado() {
        val r = respuesta("""{"tipo":"respuesta","cmd":"cerrar","id":7,"ok":false,"error":"ya_bloqueado"}""")
        assertFalse(r.ok)
        assertEquals(CodigosError.YA_BLOQUEADO, r.error)
    }

    // ---------- horarios ----------

    @Test
    fun codificaHorarios() =
        assertMismoJson("""{"cmd":"horarios","id":8}""", CodificadorProtocolo.codificar(Comando.Horarios, 8))

    @Test
    fun decodificaRespuestaHorarios() {
        val r = respuesta(
            """{"tipo":"respuesta","cmd":"horarios","id":8,"ok":true,"datos":{
     "hora_valida":true,"maximo":8,
     "horarios":[{"indice":0,"hora":22,"minuto":30,"dias":127,"activo":true},
                 {"indice":2,"hora":15,"minuto":0,"dias":31,"activo":false}]}}"""
        )
        val datos = CodificadorProtocolo.datosDe(r, DatosHorarios.serializer())!!
        assertTrue(datos.horaValida)
        assertEquals(8, datos.maximo)
        assertEquals(
            listOf(
                Horario(indice = 0, hora = 22, minuto = 30, dias = 127, activo = true),
                Horario(indice = 2, hora = 15, minuto = 0, dias = 31, activo = false),
            ),
            datos.horarios,
        )
    }

    @Test
    fun decodificaHorariosSinHoraValidaYVacios() {
        val r = respuesta(
            """{"tipo":"respuesta","cmd":"horarios","id":8,"ok":true,"datos":{"hora_valida":false,"maximo":8,"horarios":[]}}"""
        )
        val datos = CodificadorProtocolo.datosDe(r, DatosHorarios.serializer())!!
        assertFalse(datos.horaValida)
        assertTrue(datos.horarios.isEmpty())
    }

    // ---------- horario_guardar ----------

    @Test
    fun codificaHorarioGuardarNuevoSinIndice() =
        assertMismoJson(
            """{"cmd":"horario_guardar","id":9,"hora":22,"minuto":30,"dias":127,"activo":true}""",
            CodificadorProtocolo.codificar(Comando.HorarioGuardar(indice = null, hora = 22, minuto = 30, dias = 127), 9),
        )

    @Test
    fun codificaHorarioGuardarEditandoConIndice() =
        assertMismoJson(
            """{"cmd":"horario_guardar","id":11,"indice":2,"hora":15,"minuto":0,"dias":31,"activo":false}""",
            CodificadorProtocolo.codificar(
                Comando.HorarioGuardar(indice = 2, hora = 15, minuto = 0, dias = 31, activo = false),
                11,
            ),
        )

    @Test
    fun decodificaRespuestaHorarioGuardar() {
        val r = respuesta(
            """{"tipo":"respuesta","cmd":"horario_guardar","id":9,"ok":true,"datos":{"indice":0,"hora_valida":true}}"""
        )
        val datos = CodificadorProtocolo.datosDe(r, DatosHorarioGuardado.serializer())!!
        assertEquals(0, datos.indice)
        assertEquals(true, datos.horaValida)
    }

    @Test
    fun decodificaErroresDeHorarioGuardar() {
        assertEquals(
            CodigosError.SIN_ESPACIO,
            respuesta("""{"tipo":"respuesta","cmd":"horario_guardar","id":9,"ok":false,"error":"sin_espacio"}""").error,
        )
        assertEquals(
            CodigosError.PARAMETROS_INVALIDOS,
            respuesta("""{"tipo":"respuesta","cmd":"horario_guardar","id":9,"ok":false,"error":"parametros_invalidos"}""").error,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun horarioGuardarExigeAlMenosUnDia() {
        Comando.HorarioGuardar(indice = null, hora = 7, minuto = 0, dias = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun horarioGuardarRechazaHoraFueraDeRango() {
        Comando.HorarioGuardar(indice = null, hora = 24, minuto = 0, dias = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun horarioGuardarRechazaIndiceFueraDeRango() {
        Comando.HorarioGuardar(indice = 8, hora = 7, minuto = 0, dias = 1)
    }

    // ---------- horario_borrar ----------

    @Test
    fun codificaHorarioBorrar() =
        assertMismoJson(
            """{"cmd":"horario_borrar","id":10,"indice":2}""",
            CodificadorProtocolo.codificar(Comando.HorarioBorrar(2), 10),
        )

    @Test
    fun decodificaRespuestaHorarioBorrarYSuError() {
        assertTrue(respuesta("""{"tipo":"respuesta","cmd":"horario_borrar","id":10,"ok":true}""").ok)
        assertEquals(
            CodigosError.PARAMETROS_INVALIDOS,
            respuesta("""{"tipo":"respuesta","cmd":"horario_borrar","id":10,"ok":false,"error":"parametros_invalidos"}""").error,
        )
    }

    // ---------- alerta.origen ----------

    private fun alertaCon(campos: String): Alerta =
        (CodificadorProtocolo.decodificar(
            """{"tipo":"evento","evento":"estado","datos":{"fase":"BLOQUEADO","alerta":{$campos}}}"""
        ) as EventoEstado).datos.alerta!!

    @Test
    fun decodificaCierreManualConTemperaturaNula() {
        val alerta = alertaCon(
            """"vision":"sin riesgo","temperatura":null,"origen":"manual","cierre_en_s":0,"admite_respuesta":false,"requiere_rearme":true"""
        )
        assertEquals(OrigenAlerta.MANUAL, alerta.origen)
        assertNull(alerta.temperatura)
        assertEquals(Vision.SIN_RIESGO, alerta.vision)
        assertTrue(alerta.requiereRearme)
        assertTrue(alerta.origen.esCierreSinVision)
    }

    @Test
    fun decodificaCierrePorHorario() {
        val alerta = alertaCon(
            """"vision":"sin riesgo","temperatura":26.1,"origen":"horario","cierre_en_s":0,"admite_respuesta":false,"requiere_rearme":true"""
        )
        assertEquals(OrigenAlerta.HORARIO, alerta.origen)
        assertEquals(26.1, alerta.temperatura!!, 0.0)
        assertTrue(alerta.origen.esCierreSinVision)
        assertFalse(OrigenAlerta.TEMPERATURA.esCierreSinVision)
        assertFalse(OrigenAlerta.AUTOCALIBRACION.esCierreSinVision)
    }

    // ---------- días ----------

    @Test
    fun resumeLasMascarasDelDocumento() {
        assertEquals(ResumenDias.TodosLosDias, resumirDias(127))
        assertEquals(ResumenDias.LunesAViernes, resumirDias(31))
        assertEquals(ResumenDias.FinDeSemana, resumirDias(96))
    }

    @Test
    fun otrasCombinacionesSonUnaListaDeLunesADomingo() {
        // Lunes (bit 0), miércoles (bit 2) y domingo (bit 6).
        assertEquals(ResumenDias.Lista(listOf(0, 2, 6)), resumirDias(0b1000101))
        assertEquals(ResumenDias.Lista(listOf(4)), resumirDias(1 shl 4))
    }

    @Test
    fun alternarDiasEnLaMascara() {
        var mascara = 0
        mascara = DiasSemana.alternar(mascara, 0)
        mascara = DiasSemana.alternar(mascara, 6)
        assertEquals(65, mascara)
        assertTrue(DiasSemana.contiene(mascara, 6))
        assertFalse(DiasSemana.contiene(DiasSemana.alternar(mascara, 6), 6))
    }
}
