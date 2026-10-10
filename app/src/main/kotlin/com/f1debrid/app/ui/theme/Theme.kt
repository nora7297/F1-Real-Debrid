package com.f1debrid.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val F1Red = Color(0xFFE10600)
val ReadyGreen = Color(0xFF2ECC71)
val BusyAmber = Color(0xFFF1C40F)
val FailedRed = Color(0xFFFF6B6B)

@Composable
fun F1DebridTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = F1Red,
            onPrimary = Color.White,
            secondary = Color(0xFF3A86FF),
            onSecondary = Color.White,
            background = Color(0xFF0E0E10),
            onBackground = Color(0xFFEDEDED),
            surface = Color(0xFF17171B),
            onSurface = Color(0xFFEDEDED),
            surfaceVariant = Color(0xFF26262C),
            onSurfaceVariant = Color(0xFFB8B8C0),
            error = FailedRed,
            onError = Color.Black,
        ),
        content = content,
    )
}
