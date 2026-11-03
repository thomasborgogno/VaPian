package com.bortho.autovelox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import com.bortho.autovelox.R

/**
 * Nunito (Google Fonts, SIL Open Font License): a sans with softly rounded terminals. The variable font is bundled
 * in the app (`res/font`) instead of downloaded, because the head unit has no network and no Google services.
 */
val AppFont = FontFamily(
    listOf(
        FontWeight.Light to 300,
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
        FontWeight.ExtraBold to 800,
    ).map { (weight, value) -> Font(R.font.nunito, weight, variationSettings = FontVariation.Settings(FontVariation.weight(value))) },
)

/**
 * Horizontal scale of every glyph: Nunito is wide and has no width axis, so the text is condensed a little instead.
 * 1.0 is the font as designed; lower is narrower (below about 0.85 the letters start to look squashed).
 */
const val TEXT_WIDTH_SCALE = 0.9f

private fun TextStyle.rounded() = copy(fontFamily = AppFont, textGeometricTransform = TextGeometricTransform(scaleX = TEXT_WIDTH_SCALE))

/** Material 3's default type scale with every style set in [AppFont] and condensed by [TEXT_WIDTH_SCALE]. */
val AppTypography: Typography = Typography().let {
    it.copy(
        displayLarge = it.displayLarge.rounded(),
        displayMedium = it.displayMedium.rounded(),
        displaySmall = it.displaySmall.rounded(),
        headlineLarge = it.headlineLarge.rounded(),
        headlineMedium = it.headlineMedium.rounded(),
        headlineSmall = it.headlineSmall.rounded(),
        titleLarge = it.titleLarge.rounded(),
        titleMedium = it.titleMedium.rounded(),
        titleSmall = it.titleSmall.rounded(),
        bodyLarge = it.bodyLarge.rounded(),
        bodyMedium = it.bodyMedium.rounded(),
        bodySmall = it.bodySmall.rounded(),
        labelLarge = it.labelLarge.rounded(),
        labelMedium = it.labelMedium.rounded(),
        labelSmall = it.labelSmall.rounded(),
    )
}
