package dev.thedukerchip.scandy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import scandy.R

val Lato = FontFamily(Font(R.font.lato_regular))
val Lobster = FontFamily(Font(R.font.lobster_regular))

private val Default = Typography()

// Lobster is the display face, Lato carries everything that is read rather than looked at.
val ScandyTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = Lobster),
    displayMedium = Default.displayMedium.copy(fontFamily = Lobster),
    displaySmall = Default.displaySmall.copy(fontFamily = Lobster),
    headlineLarge = Default.headlineLarge.copy(fontFamily = Lato),
    headlineMedium = Default.headlineMedium.copy(fontFamily = Lato),
    headlineSmall = Default.headlineSmall.copy(fontFamily = Lato),
    titleLarge = Default.titleLarge.copy(fontFamily = Lato),
    titleMedium = Default.titleMedium.copy(fontFamily = Lato),
    titleSmall = Default.titleSmall.copy(fontFamily = Lato),
    bodyLarge = Default.bodyLarge.copy(fontFamily = Lato),
    bodyMedium = Default.bodyMedium.copy(fontFamily = Lato),
    bodySmall = Default.bodySmall.copy(fontFamily = Lato),
    labelLarge = Default.labelLarge.copy(fontFamily = Lato),
    labelMedium = Default.labelMedium.copy(fontFamily = Lato),
    labelSmall = Default.labelSmall.copy(fontFamily = Lato),
)
