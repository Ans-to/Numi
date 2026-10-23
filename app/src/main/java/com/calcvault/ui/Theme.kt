package com.calcvault.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFB9D892), onPrimary = Color(0xFF203114), primaryContainer = Color(0xFF34472A),
    background = Color(0xFF111413), surface = Color(0xFF111413), surfaceVariant = Color(0xFF262C28),
    onBackground = Color(0xFFF0F2EB), onSurface = Color(0xFFF0F2EB), onSurfaceVariant = Color(0xFFA5B0A6),
    outline = Color(0xFF505A51), secondaryContainer = Color(0xFF2D3530)
)
private val Light = lightColorScheme(primary = Color(0xFF48642E), background = Color(0xFFF6F8F1), surface = Color(0xFFF6F8F1), surfaceVariant = Color(0xFFE5EADF))
@Composable fun CalcVaultTheme(light: Boolean, content: @Composable () -> Unit) { MaterialTheme(colorScheme = if (light) Light else Dark, content = content) }
