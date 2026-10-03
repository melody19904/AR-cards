package com.example.cardengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {

    private var result: CardEngine.Result? = null
    private var fps = 0.0
    private var frameW = 1
    private var frameH = 1

    private var render: Bitmap? = null
    private var cardName: String? = null
    private var cardRarity: String? = null
    private var cardDescription: String? = null
    private var vaultUnlocked = false

    private var loadingMessage: String? = null

    private var flashStartMs = 0L
    private val flashDurationMs = 1600L

    private val density = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val reticle = Paint().apply {
        color = 0xFF22FF88.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        setShadowLayer(14f * density, 0f, 0f, 0x8022FF88.toInt())
    }

    private val renderPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    private val badgeBg = Paint().apply {
        color = 0xF00A0A0A.toInt()
        isAntiAlias = true
        setShadowLayer(16f * density, 0f, 6f * density, 0xCC000000.toInt())
    }
    private val badgeBorder = Paint().apply {
        color = 0xFFD4AF37.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        isAntiAlias = true
    }
    private val badgeName = Paint().apply {
        color = Color.WHITE
        textSize = 18f * sp
        isAntiAlias = true
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.01f
    }
    private val badgeRarity = Paint().apply {
        color = 0xFFD4AF37.toInt()
        textSize = 11f * sp
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.22f
    }

    private val panelShadow = Paint().apply {
        color = 0x99000000.toInt()
        isAntiAlias = true
        setShadowLayer(24f * density, 0f, 6f * density, 0xCC000000.toInt())
    }
    private val panelBg = Paint().apply { isAntiAlias = true }

    private val panelName = Paint().apply {
        color = Color.WHITE
        textSize = 30f * sp
        isAntiAlias = true
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }
    private val panelRarity = Paint().apply {
        textSize = 12f * sp
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.22f
    }
    private val panelDesc = TextPaint().apply {
        color = 0xE0FFFFFF.toInt()
        textSize = 16f * sp
        isAntiAlias = true
    }
    private val chipPaint = Paint().apply { isAntiAlias = true }
    private val chipText = Paint().apply {
        color = 0xFF0A1A12.toInt()
        textSize = 12f * sp
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.15f
    }

    private val loadingBg = Paint().apply { color = 0xF2000000.toInt(); isAntiAlias = true }
    private val loadingText = Paint().apply {
        color = Color.WHITE
        textSize = 20f * sp
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val loadingSub = Paint().apply {
        color = 0x99FFFFFF.toInt()
        textSize = 13f * sp
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.25f
    }

    private val flashText = Paint().apply {
        color = Color.WHITE
        textSize = 54f * sp
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val flashSubText = Paint().apply {
        color = 0xFFD4AF37.toInt()
        textSize = 15f * sp
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.4f
    }

    private val mesh = FloatArray(8)

    fun update(r: CardEngine.Result, fps: Double, frameW: Int, frameH: Int) {
        result = r; this.fps = fps; this.frameW = frameW; this.frameH = frameH
        postInvalidateOnAnimation()
    }

    fun setRender(bmp: Bitmap) { render = bmp; postInvalidateOnAnimation() }

    fun setCardInfo(name: String?, rarity: String?, description: String?) {
        cardName = name; cardRarity = rarity; cardDescription = description
        postInvalidateOnAnimation()
    }

    fun setVaultStatus(unlocked: Boolean) {
        if (vaultUnlocked == unlocked) return
        vaultUnlocked = unlocked
        postInvalidateOnAnimation()
    }

    fun showLoading(msg: String) { loadingMessage = msg; postInvalidateOnAnimation() }
    fun hideLoading() { loadingMessage = null; postInvalidateOnAnimation() }

    fun flashNewCard() {
        flashStartMs = System.currentTimeMillis()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        if (loadingMessage != null) { drawLoading(c); return }

        val r = result

        val s = max(width / frameW.toFloat(), height / frameH.toFloat())
        val ox = (width - frameW * s) / 2f
        val oy = (height - frameH * s) / 2f

        if (r != null) {
            val k = r.corners
            if (k != null) {
                val xs = FloatArray(4); val ys = FloatArray(4)
                for (i in 0 until 4) { xs[i] = ox + k[2 * i] * s; ys[i] = oy + k[2 * i + 1] * s }

                val bmp = render
                if (bmp != null) {
                    mesh[0] = xs[0]; mesh[1] = ys[0]
                    mesh[2] = xs[1]; mesh[3] = ys[1]
                    mesh[4] = xs[3]; mesh[5] = ys[3]
                    mesh[6] = xs[2]; mesh[7] = ys[2]
                    c.drawBitmapMesh(bmp, 1, 1, mesh, 0, null, 0, renderPaint)
                }

                drawCornerBrackets(c, xs, ys)

                if (r.cardId != null) drawFloatingBadge(c, xs, ys)
            }
        }

        if (r?.cardId != null) drawInfoPanel(c)

        drawFlash(c)
    }

    private fun drawLoading(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), loadingBg)
        val cy = height / 2f
        c.drawText(loadingMessage ?: "", width / 2f, cy, loadingText)
        c.drawText("CARD VISION", width / 2f, cy + 34f * density, loadingSub)
    }

    private fun drawCornerBrackets(c: Canvas, xs: FloatArray, ys: FloatArray) {
        val len = 38f * density
        for (i in 0 until 4) {
            val x = xs[i]; val y = ys[i]
            val j1 = (i + 1) % 4
            val j2 = (i + 3) % 4

            val dx1 = xs[j1] - x; val dy1 = ys[j1] - y
            val l1 = hypot(dx1, dy1).coerceAtLeast(1f)
            c.drawLine(x, y, x + dx1 / l1 * len, y + dy1 / l1 * len, reticle)

            val dx2 = xs[j2] - x; val dy2 = ys[j2] - y
            val l2 = hypot(dx2, dy2).coerceAtLeast(1f)
            c.drawLine(x, y, x + dx2 / l2 * len, y + dy2 / l2 * len, reticle)
        }
    }

    private fun drawFloatingBadge(c: Canvas, xs: FloatArray, ys: FloatArray) {
        val name = cardName ?: result?.cardId ?: return
        val rarity = cardRarity?.takeIf { it.isNotBlank() }

        val padH = 18f * density
        val padV = 12f * density
        val nameW = badgeName.measureText(name)
        val rarityW = if (rarity != null) badgeRarity.measureText(rarity.uppercase()) else 0f
        val innerW = max(nameW, rarityW)
        val innerH = badgeName.textSize + (if (rarity != null) badgeRarity.textSize + 6f * density else 0f)

        val badgeW = innerW + padH * 2
        val badgeH = innerH + padV * 2

        val mx = (xs[0] + xs[1]) / 2f
        val my = min(ys[0], ys[1])
        val tailLen = 12f * density

        var bl = mx - badgeW / 2f
        var bt = my - badgeH - tailLen - 10f * density
        var br = bl + badgeW
        var bb = bt + badgeH

        val edge = 12f * density
        if (bl < edge) { bl = edge; br = bl + badgeW }
        if (br > width - edge) { br = width - edge; bl = br - badgeW }
        if (bt < edge) { bt = edge; bb = bt + badgeH }

        val rr = RectF(bl, bt, br, bb)
        val radius = 18f * density
        c.drawRoundRect(rr, radius, radius, badgeBg)
        c.drawRoundRect(rr, radius, radius, badgeBorder)

        val tail = Path().apply {
            moveTo(mx - 8f * density, bb - 1f * density)
            lineTo(mx, bb + tailLen)
            lineTo(mx + 8f * density, bb - 1f * density)
            close()
        }
        c.drawPath(tail, badgeBg)
        c.drawPath(tail, badgeBorder)

        val nameX = bl + padH
        val nameY = bt + padV + badgeName.textSize
        c.drawText(name, nameX, nameY, badgeName)

        if (rarity != null) {
            val ry = nameY + badgeRarity.textSize + 4f * density
            c.drawText(rarity.uppercase(), nameX, ry, badgeRarity)
        }
    }

    private fun drawInfoPanel(c: Canvas) {
        val name = cardName ?: result?.cardId ?: return
        val rarity = cardRarity?.takeIf { it.isNotBlank() }
        val desc = cardDescription?.takeIf { it.isNotBlank() }
        val rarityColor = rarityColor(rarity)

        val margin = 14f * density
        val padH = 22f * density
        val padV = 20f * density

        val panelLeft = margin
        val panelRight = width - margin
        val panelW = panelRight - panelLeft
        val textW = panelW - padH * 2 - 6f * density

        val nameLineH = panelName.textSize * 1.15f
        val rarityH = if (rarity != null) panelRarity.textSize * 2.0f else 0f

        var descLayout: StaticLayout? = null
        if (desc != null) {
            descLayout = StaticLayout.Builder
                .obtain(desc, 0, desc.length, panelDesc, textW.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        val descH = descLayout?.height?.toFloat() ?: 0f

        val gapAfterName = if (descLayout != null) 14f * density else 0f
        val panelH = padV * 2 + nameLineH + rarityH + gapAfterName + descH

        val panelTop = height - margin - panelH
        val panelBottom = height - margin

        val rr = RectF(panelLeft, panelTop, panelRight, panelBottom)
        val radius = 22f * density

        panelBg.shader = LinearGradient(
            0f, panelTop, 0f, panelBottom,
            0xF2141414.toInt(), 0xF21F1F1F.toInt(),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rr, radius, radius, panelShadow)
        c.drawRoundRect(rr, radius, radius, panelBg)
        panelBg.shader = null

        val barW = 4f * density
        val barInset = 12f * density
        chipPaint.color = rarityColor
        c.drawRoundRect(
            RectF(panelLeft + barInset, panelTop + padV, panelLeft + barInset + barW, panelBottom - padV),
            barW / 2f, barW / 2f, chipPaint
        )

        val contentLeft = panelLeft + padH

        val nameBaseline = panelTop + padV + panelName.textSize
        c.drawText(name, contentLeft, nameBaseline, panelName)

        if (rarity != null) {
            val chipLabel = rarity.uppercase()
            val chipPadH = 12f * density
            val chipPadV = 6f * density
            val chipW = panelRarity.measureText(chipLabel) + chipPadH * 2
            val chipH = panelRarity.textSize + chipPadV * 2

            val chipLeft = panelRight - padH - chipW
            val chipTop = panelTop + padV + (panelName.textSize - chipH) / 2f - 2f * density

            chipPaint.color = withAlpha(rarityColor, 0x33)
            c.drawRoundRect(RectF(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH), chipH / 2f, chipH / 2f, chipPaint)

            panelRarity.color = rarityColor
            val ty = chipTop + chipH / 2f - (panelRarity.descent() + panelRarity.ascent()) / 2f
            c.drawText(chipLabel, chipLeft + chipPadH, ty, panelRarity)
        }

        var y = nameBaseline + rarityH + gapAfterName
        if (descLayout != null) {
            c.save()
            c.translate(contentLeft, y)
            descLayout.draw(c)
            c.restore()
        }

        if (vaultUnlocked) {
            val badgeLabel = if (vaultUnlocked) "OWNED" else "UNCLAIMED - SCAN QR"
            val badgeBgColor = if (vaultUnlocked) 0xFFD4AF37.toInt() else 0x99000000.toInt()
            val badgeBorderColor = if (vaultUnlocked) 0xFFD4AF37.toInt() else 0xFF888888.toInt()
            val badgeTextColor = if (vaultUnlocked) 0xFF0A1A12.toInt() else 0xFFFFFFFF.toInt()

            val bp = 10f * density
            val bw = chipText.measureText(badgeLabel) + bp * 2
            val bh = chipText.textSize + bp * 2
            val bl = panelRight - padH - bw
            val bt = panelBottom - padV - bh

            chipPaint.color = badgeBgColor
            chipPaint.style = Paint.Style.FILL
            c.drawRoundRect(RectF(bl, bt, bl + bw, bt + bh), bh / 2f, bh / 2f, chipPaint)

            if (!vaultUnlocked) {
                chipPaint.color = badgeBorderColor
                chipPaint.style = Paint.Style.STROKE
                chipPaint.strokeWidth = 2f * density
                c.drawRoundRect(RectF(bl, bt, bl + bw, bt + bh), bh / 2f, bh / 2f, chipPaint)
                chipPaint.style = Paint.Style.FILL
            }

            val oldColor = chipText.color
            chipText.color = badgeTextColor
            val ty = bt + bh / 2f - (chipText.descent() + chipText.ascent()) / 2f
            c.drawText(badgeLabel, bl + bp, ty, chipText)
            chipText.color = oldColor
        }
    }

    private fun drawFlash(c: Canvas) {
        if (flashStartMs == 0L) return
        val elapsed = System.currentTimeMillis() - flashStartMs
        if (elapsed >= flashDurationMs) { flashStartMs = 0L; return }

        val t = elapsed.toFloat() / flashDurationMs
        val alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        val scale = 1f + t * 0.35f

        flashText.alpha = alpha
        flashSubText.alpha = alpha

        c.save()
        c.translate(width / 2f, height / 2f)
        c.scale(scale, scale)
        c.drawText("NEW CARD", 0f, 0f, flashText)
        c.drawText("ADDED TO VAULT", 0f, flashSubText.textSize + 14f * density, flashSubText)
        c.restore()

        postInvalidateOnAnimation()
    }

    private fun rarityColor(rarity: String?): Int = when (rarity?.lowercase()) {
        "common"     -> 0xFF9CA3AF.toInt()
        "uncommon"   -> 0xFF22FF88.toInt()
        "rare"       -> 0xFF60A5FA.toInt()
        "epic"       -> 0xFFA78BFA.toInt()
        "legendary"  -> 0xFFFBBF24.toInt()
        "prototype"  -> 0xFFF97316.toInt()
        else         -> 0xFF22FF88.toInt()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}