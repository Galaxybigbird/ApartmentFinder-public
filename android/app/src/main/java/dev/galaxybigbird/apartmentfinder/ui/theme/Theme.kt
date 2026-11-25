package dev.galaxybigbird.apartmentfinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BrandTeal = Color(0xFF14B8A6)
val BrandOrange = Color(0xFFFF9500)
val BrandDeepOrange = Color(0xFFFF6D00)
val BrandGreen = Color(0xFF22C55E)
val BrandPurple = Color(0xFFA855F7)
val BrandRed = Color(0xFFEF4444)
val BrandMutedGray = Color(0xFF8E8E93)

val SurfaceDark = Color(0xFF0B1018)
val SurfaceCard = Color(0xFF141B26)
val SurfaceElevated = Color(0xFF1D2635)
val BorderSubtle = Color(0xFF2A3648)

private val DarkPalette = darkColorScheme(
    primary = BrandTeal,
    onPrimary = Color(0xFF041412),
    primaryContainer = Color(0xFF0F3532),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = BrandOrange,
    onSecondary = Color(0xFF1C0E00),
    secondaryContainer = Color(0xFF3A2506),
    onSecondaryContainer = Color(0xFFFFD8A8),
    tertiary = BrandPurple,
    background = SurfaceDark,
    onBackground = Color(0xFFF3F6FB),
    surface = SurfaceCard,
    onSurface = Color(0xFFF3F6FB),
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = Color(0xFFA7B5C8),
    outline = BorderSubtle,
    error = BrandRed
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun ApartmentFinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkPalette,
        typography = AppTypography,
        content = content
    )
}
