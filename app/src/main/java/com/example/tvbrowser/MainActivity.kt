package com.example.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

// آدرس صفحه اول را اینجا عوض کنید
private const val START_URL = "https://xhamster.com"

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** مکان‌نمای موس مجازی */
class CursorView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    var px = 0f
    var py = 0f

    override fun onDraw(c: Canvas) {
        c.drawCircle(px, py, 14f, fill)
        c.drawCircle(px, py, 14f, ring)
    }
}

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private var cx = 0f
    private var cy = 0f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        web = WebView(this)
        cursor = CursorView(this)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(web, FrameLayout.LayoutParams(match, match))
        root.addView(cursor, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_UA
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.isFocusable = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view
                customCb = cb
                root.addView(view, FrameLayout.LayoutParams(match, match))
                web.visibility = View.GONE
                cursor.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden()
                customCb = null
                web.visibility = View.VISIBLE
                cursor.visibility = View.VISIBLE
            }
        }

        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            updateCursor()
        }

        web.loadUrl(START_URL)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun updateCursor() {
        cursor.px = cx
        cursor.py = cy
        cursor.invalidate()
    }

    private fun move(dx: Float, dy: Float) {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val edge = 30f
        val scrollStep = 250

        // اگر مکان‌نما به لبه بالا/پایین رسید، صفحه اسکرول شود
        if (dy < 0 && cy <= edge) web.scrollBy(0, -scrollStep)
        if (dy > 0 && cy >= h - edge) web.scrollBy(0, scrollStep)

        cx = (cx + dx).coerceIn(0f, w - 1)
        cy = (cy + dy).coerceIn(0f, h - 1)
        updateCursor()
    }

    private fun tap() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0)
        web.dispatchTouchEvent(down)
        web.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // داخل ویدیوی تمام‌صفحه، کلیدها به خود پلیر برسد
        if (customView != null) return super.dispatchKeyEvent(e)

        val isDown = e.action == KeyEvent.ACTION_DOWN
        // با نگه داشتن دکمه، سرعت بیشتر می‌شود
        val s = 30f * (1 + minOf(e.repeatCount, 12) / 3f)

        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (isDown) move(-s, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isDown) move(s, 0f); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (isDown) move(0f, -s); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (isDown) move(0f, s); return true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isDown && e.repeatCount == 0) tap()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
