package com.calcvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calcvault.data.BiometricGate

@Composable fun CalcVaultApp(model: VaultViewModel, biometric: BiometricGate) {
    val state by model.state.collectAsStateWithLifecycle()
    CalcVaultTheme(state.preferences.light) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when {
                    !state.ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.fatal -> Column(Modifier.align(Alignment.Center).padding(28.dp)) {
                        Text("Storage needs attention", style = MaterialTheme.typography.headlineSmall)
                        Text(state.message.orEmpty(), modifier = Modifier.padding(top = 16.dp))
                    }
                    state.concealed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Calculator", style = MaterialTheme.typography.headlineSmall) }
                    state.unlocked && state.pinStage == 0 -> VaultScreen(model, state, onBiometric = {
                        biometric.request(true, null, model::biometricSaved, model::message)
                    }, biometricAvailable = biometric.available())
                    else -> CalculatorScreen(state, model::key, model::startPinChange, model::cancelPin) {
                        runCatching { model.auth.biometricRecord() }.onSuccess {
                            biometric.request(false, it, { _, _ -> model.biometricUnlocked() }, model::message)
                        }.onFailure { model.message("Biometric unlock is unavailable. Use your PIN.") }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                if (state.message != null && !state.concealed && !state.fatal) AlertDialog(
                    onDismissRequest = { model.message(null) }, title = { Text("Calc Vault") }, text = { Text(state.message.orEmpty()) },
                    confirmButton = { TextButton(onClick = { model.message(null) }) { Text("OK") } })
            }
            BackHandler(state.pinStage > 0) { model.cancelPin() }
        }
    }
}
