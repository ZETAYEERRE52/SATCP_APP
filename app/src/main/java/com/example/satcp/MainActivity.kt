package com.example.satcp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.satcp.servicio.EstadoConexion
import com.example.satcp.transporte.TipoTransporte
import com.example.satcp.ui.camara.PantallaCamara
import com.example.satcp.ui.clave.PantallaClave
import com.example.satcp.ui.conexion.PantallaConexion
import com.example.satcp.ui.configuracion.PantallaConfiguracion
import com.example.satcp.ui.horarios.PantallaHorarios
import com.example.satcp.ui.panel.PantallaPanel
import com.example.satcp.ui.theme.SATCPTheme
import com.example.satcp.ui.vivo.PantallaVivo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SATCPTheme {
                AppSatcp()
            }
        }
    }
}

/** Pestañas que se muestran mientras hay conexión. */
private enum class Pestania(@StringRes val titulo: Int, @DrawableRes val icono: Int) {
    PANEL(R.string.pestania_panel, R.drawable.ic_tab_panel),
    CAMARA(R.string.pestania_camara, R.drawable.ic_tab_camara),
    VIVO(R.string.pestania_vivo, R.drawable.ic_tab_vivo),
    HORARIOS(R.string.pestania_horarios, R.drawable.ic_tab_horarios),
    CONFIGURACION(R.string.pestania_configuracion, R.drawable.ic_tab_configuracion),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSatcp() {
    val app = LocalContext.current.applicationContext as SatcpApp
    val conexion by app.cliente.estadoConexion.collectAsStateWithLifecycle()
    val claveGuardada by app.almacenClave.clave.collectAsStateWithLifecycle()
    var editandoClave by rememberSaveable { mutableStateOf(false) }
    val claveRechazada = conexion == EstadoConexion.ClaveRechazada
    val pedirClave = claveGuardada == null || claveRechazada || editandoClave
    val conectado = conexion != EstadoConexion.Desconectado && !pedirClave
    val transporte by app.cliente.transporte.collectAsStateWithLifecycle()
    var pestania by rememberSaveable { mutableStateOf(Pestania.PANEL) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = {
                Text(stringResource(if (conectado) pestania.titulo else R.string.app_name))
            })
        },
        bottomBar = {
            if (conectado) {
                NavigationBar {
                    Pestania.entries.forEach { opcion ->
                        NavigationBarItem(
                            selected = pestania == opcion,
                            onClick = { pestania = opcion },
                            icon = { Icon(painterResource(opcion.icono), contentDescription = null) },
                            label = { Text(stringResource(opcion.titulo)) },
                        )
                    }
                }
            }
        },
    ) { margenes ->
        val modificador = Modifier.padding(margenes)
        if (pedirClave) {
            PantallaClave(
                rechazada = claveRechazada,
                guardar = { clave ->
                    app.almacenClave.guardar(clave)
                    editandoClave = false
                    if (claveRechazada) app.reconectar()
                },
                // La primera vez no hay nada a qué volver: sin clave la app no puede conectarse.
                cancelar = when {
                    claveRechazada -> ({ app.cliente.desconectar() })
                    editandoClave && claveGuardada != null -> ({ editandoClave = false })
                    else -> null
                },
                modifier = modificador,
            )
        } else if (!conectado) {
            PantallaConexion(cambiarClave = { editandoClave = true }, modifier = modificador)
        } else {
            when (pestania) {
                Pestania.PANEL -> PantallaPanel(modificador)
                Pestania.CAMARA -> PantallaCamara(modificador)
                Pestania.VIVO -> PantallaVivo(porBluetooth = transporte?.tipo == TipoTransporte.BLE, modifier = modificador)
                Pestania.HORARIOS -> PantallaHorarios(irAConfiguracion = { pestania = Pestania.CONFIGURACION }, modifier = modificador)
                Pestania.CONFIGURACION -> PantallaConfiguracion(modificador)
            }
        }
    }
}
