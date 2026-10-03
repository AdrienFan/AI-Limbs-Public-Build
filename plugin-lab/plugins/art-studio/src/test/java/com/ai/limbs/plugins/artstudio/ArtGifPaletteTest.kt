package com.ai.limbs.plugins.artstudio

import org.junit.Assert.*
import org.junit.Test

class ArtGifPaletteTest {
    @Test fun nightBlueColoursAreExactWhenTheyFitThePaletteAndTransparencyStaysSeparate() {
        val colors=intArrayOf(0xFF091324.toInt(),0xFF203848.toInt(),0xFF1E3547.toInt(),0xFF243B50.toInt())
        val builder=ArtGifPalette.Builder();builder.addRow(colors,50);builder.addRow(intArrayOf(0,0x7F294357))
        val palette=builder.build();assertTrue(builder.hasTransparency)
        for(color in colors)assertEquals(color and 0xFFFFFF,palette.colors[palette.index(color)-1])
        assertEquals(0,palette.index(0));assertEquals(0,palette.index(0x7F294357))
    }
    @Test fun moreThan255ColoursUseBoundedLearnedPaletteAndSharedMapping() {
        val colors=IntArray(4096){0xFF000000.toInt() or ((it and 15)*17 shl 16) or (((it ushr 4) and 15)*17 shl 8) or (((it ushr 8) and 15)*17)}
        val builder=ArtGifPalette.Builder();builder.addRow(colors)
        val palette=builder.build();assertTrue(palette.colors.size in 1..255)
        assertTrue(colors.all {palette.index(it) in 1..palette.colors.size})
        val averageError=colors.sumOf {color->val mapped=palette.colors[palette.index(color)-1];(0..2).sumOf {channel->val shift=channel*8;val delta=((color ushr shift) and 255)-((mapped ushr shift) and 255);delta*delta}}.toDouble()/colors.size/3
        assertTrue("Excessive colour error: $averageError",averageError<1200)
    }
    @Test fun fullyTransparentAnimationHasAValidPaletteWithoutInventedOpaquePixels() {
        val builder=ArtGifPalette.Builder();builder.addRow(IntArray(100))
        val palette=builder.build();assertTrue(builder.hasTransparency);assertEquals(0,palette.index(0))
    }
}
