package com.cutvideo.editor.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.cutvideo.editor.domain.model.FontCatalog

/**
 * Maps [FontCatalog] ids to real typefaces.
 *
 * Both the Compose preview and [com.cutvideo.editor.editor.TextRasterizer] resolve fonts here, so the
 * text style a user picks is the style that is rasterised into the export.
 */
object FontFamilies {

    fun typefaceFor(fontId: String): Typeface = when (fontId) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        "sans_bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> Typeface.SANS_SERIF
    }

    fun composeFamily(fontId: String): FontFamily = when (fontId) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

    val supported: List<Pair<String, String>> get() = FontCatalog.fonts
}
