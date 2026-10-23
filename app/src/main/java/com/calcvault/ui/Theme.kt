package com.calcvault.ui

import androidx.compose.material3.*
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFB9D892), onPrimary = Color(0xFF203114), primaryContainer = Color(0xFF34472A),
    background = Color(0xFF111413), surface = Color(0xFF111413), surfaceVariant = Color(0xFF262C28),
    onBackground = Color(0xFFF0F2EB), onSurface = Color(0xFFF0F2EB), onSurfaceVariant = Color(0xFFA5B0A6),
    outline = Color(0xFF505A51), secondaryContainer = Color(0xFF2D3530)
)
private val Light = lightColorScheme(primary = Color(0xFF48642E), background = Color(0xFFF6F8F1), surface = Color(0xFFF6F8F1), surfaceVariant = Color(0xFFE5EADF))
@Composable fun CalcVaultTheme(light: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            // API 26–28 otherwise keep the system-theme navigation scrim while
            // our icons follow the app theme. Draw the app surface underneath.
            if (android.os.Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    MaterialTheme(colorScheme = if (light) Light else Dark, content = content)
}
