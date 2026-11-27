package com.hyperlyrics.mm.xposed.expanded

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.hyperlyrics.mm.common.AccentColorMode
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.ModuleConfig
import kotlin.math.max
import kotlin.math.min

/**
 * Extracts YouTube Music-style velvet-dark gradient palettes from album artwork
 * with zero heavy library dependencies (safe and fast inside SystemUI).
 */
data class YtmCardPalette(
    val bgStart: Int,
    val bgEnd: Int,
    val radialGlow: Int,
    val accent: Int,
    val progressActive: Int,
    val progressTrack: Int
) {
    companion object {
        val PURE_DARK = YtmCardPalette(
            bgStart = 0xFF161619.toInt(),
            bgEnd = 0xFF070709.toInt(),
            radialGlow = 0x26FF0033,
            accent = ModuleConfig.YTM_CRIMSON,
            progressActive = ModuleConfig.YTM_CRIMSON,
            progressTrack = 0x38FFFFFF
        )

        val DEFAULT_DYNAMIC = YtmCardPalette(
            bgStart = 0xFF231824.toInt(),
            bgEnd = 0xFF0B0A0F.toInt(),
            radialGlow = 0x30D83A56,
            accent = 0xFFFF4D6D.toInt(),
            progressActive = Color.WHITE,
            progressTrack = 0x38FFFFFF
        )
    }
}

object YtmPaletteExtractor {

    fun extract(
        drawable: Drawable?,
        themeStyle: ExpandedThemeStyle,
        accentMode: AccentColorMode
    ): YtmCardPalette {
        if (themeStyle == ExpandedThemeStyle.YTM_PURE_DARK) {
            val active = when (accentMode) {
                AccentColorMode.YTM_WHITE -> Color.WHITE
                AccentColorMode.YTM_CRIMSON -> ModuleConfig.YTM_CRIMSON
                AccentColorMode.ALBUM_ADAPTIVE -> extractVibrantAccent(drawable) ?: ModuleConfig.YTM_CRIMSON
            }
            return YtmCardPalette.PURE_DARK.copy(
                accent = active,
                progressActive = active
            )
        }

        val sampled = sampleArtworkColors(drawable)
        val vibrant = sampled?.vibrant ?: 0xFFE53955.toInt()
        val dominant = sampled?.dominant ?: 0xFF2A1B2E.toInt()

        // YouTube Music Now Playing signature: rich dark tinted top-left -> deep obsidian bottom-right
        val hsv = FloatArray(3)
        Color.colorToHSV(dominant, hsv)
        val bgStart = Color.HSVToColor(
            255,
            floatArrayOf(
                hsv[0],
                (hsv[1] * 0.78f).coerceIn(0.18f, 0.68f),
                0.22f
            )
        )
        val bgEnd = Color.HSVToColor(
            255,
            floatArrayOf(
                hsv[0],
                (hsv[1] * 0.55f).coerceIn(0.10f, 0.42f),
                0.065f
            )
        )

        val glowHsv = FloatArray(3)
        Color.colorToHSV(vibrant, glowHsv)
        val glowBase = Color.HSVToColor(
            54,
            floatArrayOf(
                glowHsv[0],
                glowHsv[1].coerceIn(0.35f, 0.85f),
                0.68f
            )
        )
        val adaptiveAccent = Color.HSVToColor(
            255,
            floatArrayOf(
                glowHsv[0],
                glowHsv[1].coerceIn(0.32f, 0.78f),
                0.96f
            )
        )

        val resolvedAccent = when (accentMode) {
            AccentColorMode.YTM_WHITE -> Color.WHITE
            AccentColorMode.YTM_CRIMSON -> ModuleConfig.YTM_CRIMSON
            AccentColorMode.ALBUM_ADAPTIVE -> adaptiveAccent
        }

        return YtmCardPalette(
            bgStart = bgStart,
            bgEnd = bgEnd,
            radialGlow = glowBase,
            accent = resolvedAccent,
            progressActive = resolvedAccent,
            progressTrack = 0x3AFFFFFF
        )
    }

    private fun extractVibrantAccent(drawable: Drawable?): Int? {
        val sampled = sampleArtworkColors(drawable) ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(sampled.vibrant, hsv)
        return Color.HSVToColor(
            255,
            floatArrayOf(hsv[0], hsv[1].coerceIn(0.38f, 0.82f), 0.96f)
        )
    }

    private data class SampledColors(val dominant: Int, val vibrant: Int)

    private fun sampleArtworkColors(drawable: Drawable?): SampledColors? {
        drawable ?: return null
        val bitmap = toSmallBitmap(drawable) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            var weightSum = 0L

            var bestVibrant = 0xFFE53955.toInt()
            var bestVibrantScore = -1f
            val hsv = FloatArray(3)

            for (pixel in pixels) {
                val a = Color.alpha(pixel)
                if (a < 180) continue
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                Color.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val value = hsv[2]

                val weight = (1 + (sat * 4f).toInt()).toLong()
                rSum += r * weight
                gSum += g * weight
                bSum += b * weight
                weightSum += weight

                // Favor saturated mid-bright hues for YouTube Music accent glow
                val brightFit = 1f - kotlin.math.abs(value - 0.65f)
                val score = sat * 1.4f + brightFit * 0.6f
                if (score > bestVibrantScore && value > 0.22f) {
                    bestVibrantScore = score
                    bestVibrant = pixel
                }
            }

            if (weightSum <= 0L) return null
            val dominant = Color.rgb(
                (rSum / weightSum).toInt().coerceIn(0, 255),
                (gSum / weightSum).toInt().coerceIn(0, 255),
                (bSum / weightSum).toInt().coerceIn(0, 255)
            )
            SampledColors(dominant = dominant, vibrant = bestVibrant)
        } catch (_: Throwable) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun toSmallBitmap(drawable: Drawable): Bitmap? {
        return runCatching {
            val size = 24
            if (drawable is BitmapDrawable) {
                val src = drawable.bitmap
                if (src != null && !src.isRecycled) {
                    return Bitmap.createScaledBitmap(src, size, size, true)
                }
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val prevBounds = drawable.copyBounds()
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            drawable.bounds = prevBounds
            bmp
        }.getOrNull()
    }
}

/**
 * Smoothly crossfading YouTube Music-themed ambient backdrop view injected behind
 * the Expanded Hyper Island player's controls and lyrics.
 */
class YtmAmbientBackdropView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val evaluator = ArgbEvaluator()

    private var currentPalette: YtmCardPalette = YtmCardPalette.DEFAULT_DYNAMIC
    private var animator: ValueAnimator? = null
    private var lastW = 0
    private var lastH = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPalette(target: YtmCardPalette, animate: Boolean = true) {
        if (target == currentPalette && animator == null) return
        animator?.cancel()
        if (!animate || width == 0 || height == 0) {
            currentPalette = target
            rebuildShaders()
            invalidate()
            return
        }
        val start = currentPalette
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                currentPalette = YtmCardPalette(
                    bgStart = evaluator.evaluate(f, start.bgStart, target.bgStart) as Int,
                    bgEnd = evaluator.evaluate(f, start.bgEnd, target.bgEnd) as Int,
                    radialGlow = evaluator.evaluate(f, start.radialGlow, target.radialGlow) as Int,
                    accent = evaluator.evaluate(f, start.accent, target.accent) as Int,
                    progressActive = evaluator.evaluate(f, start.progressActive, target.progressActive) as Int,
                    progressTrack = target.progressTrack
                )
                rebuildShaders()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastW = w
        lastH = h
        rebuildShaders()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun rebuildShaders() {
        if (lastW <= 0 || lastH <= 0) return
        val w = lastW.toFloat()
        val h = lastH.toFloat()
        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(currentPalette.bgStart, blendMid(currentPalette.bgStart, currentPalette.bgEnd), currentPalette.bgEnd),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        val radius = max(w * 0.55f, h * 0.95f).coerceAtLeast(1f)
        glowPaint.shader = RadialGradient(
            w * 0.22f, h * 0.18f, radius,
            intArrayOf(currentPalette.radialGlow, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (lastW <= 0 || lastH <= 0) return
        canvas.drawRect(0f, 0f, lastW.toFloat(), lastH.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, lastW.toFloat(), lastH.toFloat(), glowPaint)
    }

    private fun blendMid(c1: Int, c2: Int): Int {
        val r = (Color.red(c1) * 0.45f + Color.red(c2) * 0.55f).toInt()
        val g = (Color.green(c1) * 0.45f + Color.green(c2) * 0.55f).toInt()
        val b = (Color.blue(c1) * 0.45f + Color.blue(c2) * 0.55f).toInt()
        return Color.rgb(min(255, r), min(255, g), min(255, b))
    }
}
