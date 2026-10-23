package com.calcvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ordinary calculator first. Only the explicitly entered PIN setup/change screen masks digits. */
@Composable fun CalculatorScreen(state: AppState, key: (String) -> Unit, setup: () -> Unit, cancel: () -> Unit, biometric: () -> Unit) {
    val light = state.preferences.light
    val background = if (light) Color(0xFFF7F7F7) else Color(0xFF101010)
    val foreground = if (light) Color(0xFF202124) else Color(0xFFF2F2F2)
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var setupInfo by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(background).padding(horizontal = 20.dp, vertical = 8.dp)) {
        val landscape = maxWidth > maxHeight
        @Composable fun Display(modifier: Modifier) {
            Column(modifier) {
                Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (state.pinStage > 0) {
                        Text(if (state.pinStage == 1) "Choose PIN" else "Confirm PIN", modifier = Modifier.weight(1f), color = foreground)
                        TextButton(onClick = cancel, enabled = !state.busy) { Text("Cancel") }
                    } else Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options", tint = foreground) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (!state.configured) DropdownMenuItem(text = { Text("Set up private storage") }, onClick = { menu = false; setupInfo = true })
                            if (state.preferences.biometric) DropdownMenuItem(text = { Text("Use biometrics") }, onClick = { menu = false; biometric() }, enabled = !state.busy)
                            DropdownMenuItem(text = { Text("About calculator") }, onClick = { menu = false; about = true })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val display = when {
                    state.pinStage > 0 -> if (state.input.isEmpty()) "0" else "•".repeat(state.input.length)
                    state.input.isNotEmpty() -> state.input
                    else -> state.result
                }
                if (state.pinStage > 0) Text("6–12 digits · press = to continue", Modifier.align(Alignment.End), color = foreground.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
                val scroll = rememberScrollState()
                LaunchedEffect(display) { scroll.scrollTo(scroll.maxValue) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp).horizontalScroll(scroll), horizontalArrangement = Arrangement.End) {
                    Text(display, Modifier.fillMaxWidth().testTag("calculator_display"), textAlign = TextAlign.End,
                        maxLines = 1, color = foreground, fontWeight = FontWeight.Light,
                        fontSize = if (display.length > 14) 38.sp else if (display.length > 9) 46.sp else 64.sp)
                }
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.pinStage == 0) {
                        TextButton(onClick = { key("(") }, enabled = !state.busy) { Text("(", color = foreground.copy(alpha = .65f), fontSize = 22.sp) }
                        TextButton(onClick = { key(")") }, enabled = !state.busy) { Text(")", color = foreground.copy(alpha = .65f), fontSize = 22.sp) }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { key("⌫") }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace", tint = foreground.copy(alpha = .65f)) }
                }
                HorizontalDivider(color = foreground.copy(alpha = .12f))
            }
        }
        @Composable fun Keypad(modifier: Modifier) {
            val rows = listOf(listOf("AC", "±", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("0", ".", "="))
            val accent = if (light) Color(0xFF237A45) else Color(0xFF8DD6A4)
            Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { label ->
                            val operator = label in listOf("÷", "×", "−", "+")
                            val function = label in listOf("AC", "±", "%")
                            val disabled = state.busy || (state.pinStage > 0 && label !in listOf("AC", "=") && label.any { !it.isDigit() })
                            Button(onClick = { key(label) }, modifier = Modifier.weight(if (row.size == 3 && label == "0") 2f else 1f).fillMaxHeight(), enabled = !disabled,
                                shape = RoundedCornerShape(percent = 50), contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        label == "=" -> accent
                                        operator -> if (light) Color(0xFFE4F1E8) else Color(0xFF1E3024)
                                        function -> if (light) Color(0xFFE2E3E5) else Color(0xFF363638)
                                        else -> if (light) Color(0xFFEBECEE) else Color(0xFF242426)
                                    },
                                    contentColor = when { label == "=" -> if (light) Color.White else Color(0xFF102719); operator -> accent; else -> foreground })) {
                                Text(label, fontSize = if (label == "AC") 22.sp else 30.sp, fontWeight = FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        if (landscape) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Display(Modifier.weight(1f).fillMaxHeight()); Keypad(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp))
        } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Display(Modifier.weight(1f).fillMaxWidth()); Keypad(Modifier.weight(1.8f).fillMaxWidth().padding(bottom = 8.dp))
        }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Calculator") },
        text = { Text("Version 1.0.2\n\nAddition, subtraction, multiplication, division, parentheses and percentages. Percent means divide by 100.\n\nCalculations stay on this device. No calculation history is saved.") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } })
    if (setupInfo) AlertDialog(onDismissRequest = { setupInfo = false }, title = { Text("Set up private storage") },
        text = { Text("Choose a 6–12 digit PIN and confirm it. Later, type it on the calculator and press = to unlock. Calculator digits are visible, including your PIN as you enter it. Only this setup screen masks digits. There is no PIN recovery; keep separate originals of your files.") },
        confirmButton = { TextButton(onClick = { setupInfo = false; setup() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { setupInfo = false }) { Text("Cancel") } })
}
