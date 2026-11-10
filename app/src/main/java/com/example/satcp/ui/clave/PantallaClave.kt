package com.example.satcp.ui.clave

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.satcp.R
import com.example.satcp.seguridad.Criptografia

/**
 * Pide la clave del equipo: al agregarlo, cuando el equipo la rechaza o cuando el usuario quiere
 * escribir otra. El texto se guarda con `remember`, no `rememberSaveable`, para que no quede en el
 * estado que Android conserva de la actividad.
 */
@Composable
fun PantallaClave(
    rechazada: Boolean,
    guardar: (String) -> Unit,
    cancelar: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var clave by remember { mutableStateOf("") }
    var mostrar by remember { mutableStateOf(false) }
    val valida = Criptografia.esClaveValida(clave)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.clave_titulo), style = MaterialTheme.typography.headlineSmall)
        if (rechazada) {
            Text(stringResource(R.string.clave_rechazada), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.clave_explicacion))

        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it.take(MAXIMO_CARACTERES) },
            label = { Text(stringResource(R.string.clave_campo)) },
            isError = clave.isNotEmpty() && !valida,
            supportingText = {
                Text(
                    if (clave.isNotEmpty() && !valida) stringResource(R.string.clave_invalida)
                    else stringResource(R.string.config_contador, clave.length, MAXIMO_CARACTERES)
                )
            },
            singleLine = true,
            visualTransformation = if (mostrar) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                TextButton(onClick = { mostrar = !mostrar }) {
                    Text(stringResource(if (mostrar) R.string.config_ocultar else R.string.config_mostrar))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = { guardar(clave) }, enabled = valida, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.clave_guardar))
        }
        cancelar?.let { TextButton(onClick = it) { Text(stringResource(R.string.cancelar)) } }
        Text(stringResource(R.string.clave_olvidada), style = MaterialTheme.typography.bodySmall)
    }
}

private const val MAXIMO_CARACTERES = 63
