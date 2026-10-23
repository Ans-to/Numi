package com.calcvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun CalculatorScreen(state: AppState, key: (String) -> Unit, setup: () -> Unit, cancel: () -> Unit, biometric: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        val landscape = maxWidth > maxHeight
        @Composable fun Display(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.pinStage > 0) "PRIVATE STORAGE" else "CALCULATOR", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    if (state.preferences.biometric && state.pinStage == 0) IconButton(onClick = biometric, enabled = !state.busy) { Icon(Icons.Outlined.Fingerprint, "Unlock with biometrics") }
                    if (state.pinStage > 0) TextButton(onClick = cancel) { Text("Cancel") }
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.End) {
                    val digitsOnly = state.input.isNotEmpty() && state.input.all { it in '0'..'9' }
                    val display = when {
                        state.pinStage > 0 -> if (state.input.isEmpty()) "—" else "•".repeat(state.input.length)
                        digitsOnly -> "•".repeat(state.input.length.coerceAtMost(18))
                        state.input.isNotEmpty() -> state.input
                        else -> state.result
                    }
                    Text(if (state.pinStage == 1) "Choose a 6–12 digit PIN" else if (state.pinStage == 2) "Enter your PIN again" else if (digitsOnly) "Digits hidden · = to calculate or unlock" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(display, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End, maxLines = 3,
                        fontWeight = FontWeight.Light, fontSize = if (display.length > 18) 30.sp else if (display.length > 10) 40.sp else 58.sp,
                        lineHeight = 62.sp)
                    if (state.pinStage > 0) Text("Press = to continue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!state.configured && state.pinStage == 0) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp)) {
                        Text("A little space, just for you.", fontWeight = FontWeight.Medium)
                        Text("Optional private storage. Choose a PIN, then enter it here and press = to unlock. No PIN recovery.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = setup, enabled = !state.busy) { Text("Set up private storage") }
                    }
                } else Spacer(Modifier.height(8.dp))
            }
        }
        @Composable fun Keypad(modifier: Modifier) {
            val rows = listOf(listOf("AC", "(", ")", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("±", "0", ".", "="))
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { key("%") }, enabled = state.pinStage == 0 && !state.busy) { Text("%") }
                    TextButton(onClick = { key("⌫") }, enabled = !state.busy) { Text("⌫", fontSize = 24.sp) }
                }
                rows.forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { label ->
                            val operator = label in listOf("÷", "×", "−", "+")
                            val disabled = state.busy || (state.pinStage > 0 && label !in listOf("AC", "=") && label.any { !it.isDigit() })
                            Button(onClick = { key(label) }, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = !disabled,
                                shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when { label == "=" -> MaterialTheme.colorScheme.primary; operator -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant },
                                    contentColor = if (label == "=") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                                Text(label, fontSize = if (label == "AC") 20.sp else 28.sp, fontWeight = FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        if (landscape) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Display(Modifier.weight(1f).fillMaxHeight()); Keypad(Modifier.weight(1f).fillMaxHeight())
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Display(Modifier.weight(1f).fillMaxWidth()); Keypad(Modifier.weight(1.65f).fillMaxWidth())
        }
    }
}
