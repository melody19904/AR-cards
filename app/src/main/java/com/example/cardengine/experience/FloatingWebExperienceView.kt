package com.example.cardengine.experience

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.view.setPadding

/**
 * A floating in-app panel that shows a resolved Experience without ever
 * handing the user's browser off to a bare URL ("quishing" is a named
 * phishing pattern now — this avoids it entirely: the page renders inside
 * our own WebView, inside our own app, with no address bar to spoof).
 *
 * Usage from MainActivity:
 *   floatingExperience.show(experience)
 *   floatingExperience.hide()
 */
class FloatingWebExperienceView(context: Context) : FrameLayout(context) {

    private val card = FrameLayout(context)
    private val webView = WebView(context)
    private val progress = ProgressBar(context)
    private val closeButton = ImageView(context)

    init {
        visibility = View.GONE
        setBackgroundColor(Color.parseColor("#88000000"))

        val cardLp = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            (resources.displayMetrics.heightPixels * 0.62f).toInt()
        ).apply { gravity = Gravity.CENTER }

        card.layoutParams = cardLp
        card.setBackgroundColor(Color.WHITE)
        card.elevation = 24f

        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setupWebView()

        progress.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        closeButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        closeButton.setPadding(24)
        closeButton.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        closeButton.setOnClickListener { hide() }

        card.addView(webView)
        card.addView(progress)
        card.addView(closeButton)
        addView(card)

        // Tap outside the card dismisses the panel.
        setOnClickListener { hide() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
            }
        }
    }

    fun show(experience: Experience) {
        when (experience) {
            is Experience.Web -> {
                progress.visibility = View.VISIBLE
                webView.loadUrl(experience.url)
            }
            is Experience.Image -> {
                progress.visibility = View.VISIBLE
                val html = "<html><body style='margin:0;background:#000'>" +
                        "<img src='${experience.url}' style='width:100%;height:auto;display:block' />" +
                        "</body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            is Experience.Video -> {
                progress.visibility = View.VISIBLE
                val html = "<html><body style='margin:0;background:#000'>" +
                        "<video src='${experience.url}' autoplay controls playsinline " +
                        "style='width:100%;height:auto;display:block'></video>" +
                        "</body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            is Experience.Unknown -> {
                progress.visibility = View.GONE
                webView.loadData(
                    "<html><body style='font-family:sans-serif;padding:24px'>" +
                            "Nothing published for this target yet.</body></html>",
                    "text/html", "UTF-8"
                )
            }
        }
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
        webView.loadUrl("about:blank")
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE
}
