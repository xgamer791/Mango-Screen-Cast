package com.mangoscreencast.tv.games

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The games' pictures, read and made ready to draw crisply at the size they're shown. */
object GameArt {
    /** A game's screenshot, as it is. */
    fun read(file: File?): Bitmap? = file?.takeIf { it.isFile }?.let {
        runCatching { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inScaled = false }) }.getOrNull()
    }

    /**
     * `picture` at exactly `w` x `h` pixels, a card's size. Pixel art is first scaled up by a whole
     * number with its pixels kept square, then brought down smoothly, halving at most at each step,
     * so every pixel stays sharp and none come out twice the width of their neighbours (the
     * sharp-bilinear way). Anything sharper is just brought down smoothly.
     */
    fun card(picture: Bitmap, w: Int, h: Int): Bitmap {
        var image = picture
        if (picture.height <= HeroView.PIXEL_ART_ROWS) {
            val k = ceil(maxOf(w.toFloat() / picture.width, h.toFloat() / picture.height) * SUPERSAMPLE).toInt().coerceAtLeast(1)
            if (k > 1) image = Bitmap.createScaledBitmap(picture, picture.width * k, picture.height * k, false)
        }
        while (image.width > w * 2 || image.height > h * 2) {
            image = Bitmap.createScaledBitmap(image, (image.width / 2f).roundToInt(), (image.height / 2f).roundToInt(), true)
        }
        return Bitmap.createScaledBitmap(image, w, h, true)
    }

    /** How far past the card's size pixel art is first scaled up: enough for clean pixel edges. */
    private const val SUPERSAMPLE = 3f
}
