package com.example.cardengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/**
 * Draws ONLY the render.png warped onto the tracked card quad. No HUD, no badges,
 * no text — everything else lives in the WebView (index.html).
 *
 * The WebView above this view has a transparent background on the Scan screen,
 * so this Canvas is visible through it.
 */
class RenderOverlayView(context: Context) : View(context) {

    private var render: Bitmap? = null
    private var corners: FloatArray? = null   // 8 floats x0,y0..x3,y3 in frame pixels
    private var frameW = 1
    private var frameH = 1

    private val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val mesh = FloatArray(8)

    fun setRender(bmp: Bitmap?) {
        render = bmp
        postInvalidateOnAnimation()
    }

    fun setQuad(c: FloatArray?, fw: Int, fh: Int) {
        corners = c
        frameW = fw
        frameH = fh
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = render ?: return
        val k = corners ?: return

        // Same math as PreviewView.ScaleType.FILL_CENTER: scale-to-cover, centered.
        val s = max(width / frameW.toFloat(), height / frameH.toFloat())
        val ox = (width - frameW * s) / 2f
        val oy = (height - frameH * s) / 2f

        val xs = FloatArray(4); val ys = FloatArray(4)
        for (i in 0 until 4) {
            xs[i] = ox + k[2 * i] * s
            ys[i] = oy + k[2 * i + 1] * s
        }

        // drawBitmapMesh wants 1x1 grid vertices in source order TL, TR, BL, BR.
        // Engine corners are TL(0), TR(1), BR(2), BL(3).
        mesh[0] = xs[0]; mesh[1] = ys[0]
        mesh[2] = xs[1]; mesh[3] = ys[1]
        mesh[4] = xs[3]; mesh[5] = ys[3]
        mesh[6] = xs[2]; mesh[7] = ys[2]

        canvas.drawBitmapMesh(bmp, 1, 1, mesh, 0, null, 0, paint)
    }
}