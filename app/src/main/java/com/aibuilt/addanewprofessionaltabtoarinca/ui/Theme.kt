package com.aibuilt.addanewprofessionaltabtoarinca.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B4BA6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF00696B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF1F2),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF6A4CA0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDDCFF),
    onTertiaryContainer = Color(0xFF260A57),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCFBFF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCFBFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF243BA0),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFF80D5D6),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F50),
    onSecondaryContainer = Color(0xFF9CF1F2),
    tertiary = Color(0xFFD3BBFF),
    onTertiary = Color(0xFF392263),
    tertiaryContainer = Color(0xFF513A7C),
    onTertiaryContainer = Color(0xFFEDDCFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A)
)

@Composable
fun ArinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamic: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = if (useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
