package com.thehhr.form.nativeapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

@Composable
fun FormExpressiveTheme(
    accentSeed: Color,
    darkTheme: Boolean,
    blackTheme: Boolean,
    content: @Composable () -> Unit
) {
    val primary = if (darkTheme) lerp(accentSeed, Color.White, 0.65f) else accentSeed
    val container = lerp(accentSeed, if (darkTheme) Color.Black else Color.White, if (darkTheme) 0.60f else 0.88f)
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.Black,
            primaryContainer = container,
            onPrimaryContainer = Color.White,
            background = if (blackTheme) Color.Black else Color(0xFF141218),
            surface = if (blackTheme) Color.Black else Color(0xFF141218)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = container,
            onPrimaryContainer = Color(0xFF211A24)
        )
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        typography = Typography(),
        content = content
    )
}
