package com.example.cardengine.experience

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.cardengine.core.*
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

class FloatingWebExperienceView(context: Context) : FrameLayout(context) {

    var currentTargetId: String? = null
        private set

    var onUserDismissed: ((String?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val card = FrameLayout(context)
    private val webView = WebView(context)
    private val progress = ProgressBar(context)
    private val statusLabel = TextView(context)
    private val pinButton = TextView(context)
    private val closeButton = TextView(context)

    private var isPinnedToScreen = false
    private var isArActivelyTracking = false
    private var hasSmoothedCorners = false
    private val smoothedCorners = FloatArray(8)
    private var lastGeometryUpdateMs = 0L

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22FF88.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(12f * density, 0f, 0f, 0xAA22FF88.toInt())
    }

    private val tetherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x6622FF88
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }

    private val dockWatchdog = Runnable {
        if (isShowing && isArActivelyTracking && (System.currentTimeMillis() - lastGeometryUpdateMs) >= 650L) {
            transitionToDockedPose()
        }
    }

    init {
        visibility = View.GONE
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#22000000"))

        val cardW = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        val cardH = (resources.displayMetrics.heightPixels * 0.56f).toInt()

        val cardLp = LayoutParams(cardW, cardH).apply {
            gravity = Gravity.CENTER
        }

        card.layoutParams = cardLp
        card.cameraDistance = 14000f * density
        card.elevation = 28f * density
        card.background = GradientDrawable().apply {
            setColor(Color.parseColor("#101418"))
            cornerRadius = 18f * density
            setStroke((2f * density).toInt(), 0xFF22FF88.toInt())
        }
        card.clipToOutline = true

        val topBarHeight = (44f * density).toInt()

        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#182228"))
            setPadding((14 * density).toInt(), 0, (10 * density).toInt(), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, topBarHeight).apply {
                gravity = Gravity.TOP
            }
        }

        statusLabel.apply {
            text = "AR TARGET LOCKED"
            setTextColor(0xFF22FF88.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        pinButton.apply {
            text = "PIN HUD"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A3A44"))
                cornerRadius = 12f * density
            }
            setOnClickListener {
                isPinnedToScreen = !isPinnedToScreen
                if (isPinnedToScreen) {
                    pinButton.text = "AR MODE"
                    transitionToDockedPose()
                } else {
                    pinButton.text = "PIN HUD"
                    updateHeaderBadge()
                }
            }
        }

        closeButton.apply {
            text = "X"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            setOnClickListener { hide(userDismissed = true) }
        }

        headerBar.addView(statusLabel)
        headerBar.addView(pinButton)
        headerBar.addView(closeButton)

        webView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply {
            topMargin = topBarHeight
        }
        setupWebView()

        progress.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        card.addView(webView)
        card.addView(progress)
        card.addView(headerBar)
        addView(card)

        setOnClickListener {
            if (!isArActivelyTracking || isPinnedToScreen) {
                hide(userDismissed = true)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#101418"))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
            }
        }
    }

    fun show(experience: Experience, targetId: String = experience.source) {
        if (isShowing && currentTargetId == targetId) {
            return
        }

        currentTargetId = targetId
        isPinnedToScreen = false
        pinButton.text = "PIN HUD"
        updateHeaderBadge()

        when (experience) {
            is WebExperience -> {
                progress.visibility = View.VISIBLE
                webView.loadUrl(experience.source)
            }
            is ImageExperience -> {
                progress.visibility = View.VISIBLE
                val html = "<html><body style='margin:0;background:#101418;display:flex;align-items:center;justify-content:center;height:100vh;'>" +
                        "<img src='${experience.source}' style='max-width:100%;max-height:100%;object-fit:contain;display:block;' />" +
                        "</body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            is VideoExperience -> {
                progress.visibility = View.VISIBLE
                val html = "<html><body style='margin:0;background:#101418;display:flex;align-items:center;justify-content:center;height:100vh;'>" +
                        "<video src='${experience.source}' autoplay loop controls playsinline " +
                        "style='max-width:100%;max-height:100%;display:block;'></video>" +
                        "</body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            is UnknownExperience -> {
                progress.visibility = View.GONE
                val html = "<html><body style='font-family:sans-serif;padding:24px;background:#101418;color:#E5E7EB;'>" +
                        "<h3 style='color:#F87171;margin-top:0;'>Target Not Resolved</h3>" +
                        "<p>Target ID: <code style='color:#22FF88;'>$targetId</code></p>" +
                        "<p style='color:#9CA3AF;font-size:14px;'>No published experience was found on the active server for this target ID.</p>" +
                        "</body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            is Model3DExperience,
            is AudioExperience,
            is DocumentExperience -> {
                progress.visibility = View.GONE
                val html = "<html><body style='font-family:sans-serif;padding:24px;background:#101418;color:#E5E7EB;'>" +
                        "<h3 style='color:#FBBF24;margin-top:0;'>Renderer Pending</h3>" +
                        "<p>Target <code style='color:#22FF88;'>$targetId</code> resolved to <b>${experience.type}</b>, which is queued for the next renderer milestone.</p>" +
                        "</body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
        visibility = View.VISIBLE
    }

    fun updateTargetGeometry(geometry: TargetGeometry?) {
        if (!isShowing || geometry == null) return
        val k = geometry.corners ?: return
        val fw = geometry.frameWidth
        val fh = geometry.frameHeight
        if (k.size != 8 || fw <= 0 || fh <= 0 || width <= 0 || height <= 0) return

        val s = max(width / fw.toFloat(), height / fh.toFloat())
        val ox = (width - fw * s) / 2f
        val oy = (height - fh * s) / 2f

        val alpha = 0.32f
        for (i in 0 until 4) {
            val sx = ox + k[2 * i] * s
            val sy = oy + k[2 * i + 1] * s
            if (!hasSmoothedCorners) {
                smoothedCorners[2 * i] = sx
                smoothedCorners[2 * i + 1] = sy
            } else {
                smoothedCorners[2 * i] += alpha * (sx - smoothedCorners[2 * i])
                smoothedCorners[2 * i + 1] += alpha * (sy - smoothedCorners[2 * i + 1])
            }
        }
        hasSmoothedCorners = true
        lastGeometryUpdateMs = System.currentTimeMillis()
        isArActivelyTracking = true

        removeCallbacks(dockWatchdog)
        postDelayed(dockWatchdog, 680L)

        if (!isPinnedToScreen) {
            card.animate().cancel()

            val x0 = smoothedCorners[0]; val y0 = smoothedCorners[1]
            val x1 = smoothedCorners[2]; val y1 = smoothedCorners[3]
            val x2 = smoothedCorners[4]; val y2 = smoothedCorners[5]
            val x3 = smoothedCorners[6]; val y3 = smoothedCorners[7]

            val cx = (x0 + x1 + x2 + x3) * 0.25f
            val cy = (y0 + y1 + y2 + y3) * 0.25f

            val topW = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
            val botW = hypot((x2 - x3).toDouble(), (y2 - y3).toDouble()).toFloat()
            val leftH = hypot((x3 - x0).toDouble(), (y3 - y0).toDouble()).toFloat()
            val rightH = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
            val qrSpan = max(1f, (topW + botW + leftH + rightH) * 0.25f)

            val vx = width * 0.5f
            val vy = height * 0.5f

            card.translationX = (cx - vx).coerceIn(-vx * 0.55f, vx * 0.55f)
            card.translationY = (cy - vy).coerceIn(-vy * 0.55f, vy * 0.55f)

            val baseW = card.width.coerceAtLeast(1)
            val targetScale = ((qrSpan * 2.5f) / baseW).coerceIn(0.62f, 1.08f)
            card.scaleX = targetScale
            card.scaleY = targetScale

            val rollDeg = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat().coerceIn(-22f, 22f)
            val pitchDeg = (((botW - topW) / qrSpan) * 32f).coerceIn(-18f, 18f)
            val yawDeg = (((rightH - leftH) / qrSpan) * 32f).coerceIn(-18f, 18f)

            card.rotation = rollDeg
            card.rotationX = pitchDeg
            card.rotationY = yawDeg
        }

        updateHeaderBadge()
        postInvalidateOnAnimation()
    }

    private fun transitionToDockedPose() {
        isArActivelyTracking = false
        hasSmoothedCorners = false
        updateHeaderBadge()
        card.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .rotation(0f)
            .rotationX(0f)
            .rotationY(0f)
            .setDuration(240L)
            .start()
        postInvalidateOnAnimation()
    }

    private fun updateHeaderBadge() {
        val idLabel = currentTargetId?.take(22) ?: ""
        if (isPinnedToScreen) {
            statusLabel.text = "PINNED: $idLabel"
            statusLabel.setTextColor(0xFFFBBF24.toInt())
        } else if (isArActivelyTracking) {
            statusLabel.text = "AR TRACKING: $idLabel"
            statusLabel.setTextColor(0xFF22FF88.toInt())
        } else {
            statusLabel.text = "DOCKED: $idLabel"
            statusLabel.setTextColor(0xFF60A5FA.toInt())
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isShowing && isArActivelyTracking && hasSmoothedCorners && (System.currentTimeMillis() - lastGeometryUpdateMs) < 650L) {
            drawArTargetQuad(canvas)
        }
        super.dispatchDraw(canvas)
    }

    private fun drawArTargetQuad(c: Canvas) {
        val xs = floatArrayOf(smoothedCorners[0], smoothedCorners[2], smoothedCorners[4], smoothedCorners[6])
        val ys = floatArrayOf(smoothedCorners[1], smoothedCorners[3], smoothedCorners[5], smoothedCorners[7])

        val cardCx = width * 0.5f + card.translationX
        val cardCy = height * 0.5f + card.translationY
        for (i in 0 until 4) {
            c.drawLine(xs[i], ys[i], cardCx, cardCy, tetherPaint)
        }

        val len = 28f * density
        for (i in 0 until 4) {
            val x = xs[i]; val y = ys[i]
            val j1 = (i + 1) % 4
            val j2 = (i + 3) % 4

            val dx1 = xs[j1] - x; val dy1 = ys[j1] - y
            val l1 = hypot(dx1.toDouble(), dy1.toDouble()).toFloat().coerceAtLeast(1f)
            c.drawLine(x, y, x + dx1 / l1 * len, y + dy1 / l1 * len, bracketPaint)

            val dx2 = xs[j2] - x; val dy2 = ys[j2] - y
            val l2 = hypot(dx2.toDouble(), dy2.toDouble()).toFloat().coerceAtLeast(1f)
            c.drawLine(x, y, x + dx2 / l2 * len, y + dy2 / l2 * len, bracketPaint)
        }
    }

    fun hide(userDismissed: Boolean = false) {
        val dismissedId = currentTargetId
        removeCallbacks(dockWatchdog)
        visibility = View.GONE
        currentTargetId = null
        isArActivelyTracking = false
        hasSmoothedCorners = false
        card.translationX = 0f
        card.translationY = 0f
        card.scaleX = 1f
        card.scaleY = 1f
        card.rotation = 0f
        card.rotationX = 0f
        card.rotationY = 0f
        webView.loadUrl("about:blank")
        if (userDismissed && dismissedId != null) {
            onUserDismissed?.invoke(dismissedId)
        }
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE
}
