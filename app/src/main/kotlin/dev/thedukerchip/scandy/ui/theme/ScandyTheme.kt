package dev.thedukerchip.scandy.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Light only. Theme.Scandy has a values-night variant, but the palette below is the day one and
// there is no designed dark counterpart to carry over — see issue #54.
private val ScandyColorScheme = lightColorScheme(
    primary = PrimaryColor,
    onPrimary = PrimaryTextColor,
    primaryContainer = PrimaryLightColor,
    onPrimaryContainer = PrimaryTextColor,
    secondary = SecondaryColor,
    onSecondary = SecondaryTextColor,
    secondaryContainer = SecondaryLightColor,
    onSecondaryContainer = SecondaryTextColor,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
)

@Composable
fun ScandyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScandyColorScheme,
        typography = ScandyTypography,
        content = content,
    )
}

@Preview(showBackground = true)
@Composable
private fun PalettePreview() {
    ScandyTheme {
        Surface {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Swatch("primary", ScandyColorScheme.primary)
                Swatch("onPrimary", ScandyColorScheme.onPrimary)
                Swatch("primaryContainer", ScandyColorScheme.primaryContainer)
                Swatch("secondary", ScandyColorScheme.secondary)
                Swatch("secondaryContainer", ScandyColorScheme.secondaryContainer)
                Swatch("background", ScandyColorScheme.background)
                Swatch("surface", ScandyColorScheme.surface)
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        ) {}
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun TypeScalePreview() {
    ScandyTheme {
        Surface {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val type = MaterialTheme.typography
                Specimen("displaySmall", type.displaySmall)
                Specimen("headlineMedium", type.headlineMedium)
                Specimen("titleLarge", type.titleLarge)
                Specimen("titleMedium", type.titleMedium)
                Specimen("bodyLarge", type.bodyLarge)
                Specimen("bodyMedium", type.bodyMedium)
                Specimen("labelLarge", type.labelLarge)
                Specimen("labelSmall", type.labelSmall)
            }
        }
    }
}

@Composable
private fun Specimen(name: String, style: TextStyle) {
    Text(text = "Scandy — $name", style = style)
}
