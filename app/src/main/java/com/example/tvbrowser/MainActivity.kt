package com.example.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.security.MessageDigest

// آدرس صفحه اول را اینجا عوض کنید
private const val START_URL = "https://xhamster.com"

// تعداد رقم‌های رمز
private const val PIN_LEN = 4

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// دامنه‌های تبلیغاتی مسدودشده. هر دامنه جدید را اینجا اضافه کنید
private val BLOCKED = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com", "magsrv.com",
    "trafficjunky.net", "trafficjunky.com", "trafficfactory.biz",
    "juicyads.com", "adsterra.com", "popads.net", "propellerads.com",
    "onclickads.net", "clickadu.com", "tsyndicate.com"
)

private fun isBlocked(host: String?): Boolean {
    if (host == null) return false
    return BLOCKED.any { host == it || host.endsWith(".$it") }
}

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

    // ---- قفل رمز ----
    private lateinit var lock: FrameLayout
    private lateinit var lockTitle: TextView
    private lateinit var lockDots: TextView
    private lateinit var firstKey: Button
    private val entered = StringBuilder()
    private var locked = true
    private var lockMode = 0 // 0 = ورود، 1 = ساخت رمز، 2 = تکرار رمز
    private var firstPin = ""
    private var loaded = false
    private val prefs by lazy { getSharedPreferences("lock", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        web = WebView(this)
        cursor = CursorView(this)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(web, FrameLayout.LayoutParams(match, match))
        root.addView(cursor, FrameLayout.LayoutParams(match, match))
        lock = buildLock()
        root.addView(lock, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_UA
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        web.isFocusable = false
        web.webViewClient = object : WebViewClient() {
            // درخواست‌های تبلیغاتی را خالی برمی‌گرداند
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return if (isBlocked(request.url.host)) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            // جلوگیری از رفتن به دامنه تبلیغاتی یا برنامه‌های بیرونی
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme
                if (scheme != "http" && scheme != "https") return true
                return isBlocked(request.url.host)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // پنجره‌های پاپ‌آپ باز نشوند
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view
                customCb = cb
                root.addView(view, root.childCount - 1, FrameLayout.LayoutParams(match, match))
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

        showLock()
    }

    // ---------- قفل رمز ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLock(): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(Color.BLACK)
        f.isClickable = true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        lockTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        lockDots = TextView(this).apply {
            setTextColor(Color.parseColor("#FF5722"))
            textSize = 34f
            gravity = Gravity.CENTER
        }
        col.addView(lockTitle)
        col.addView(lockDots)

        val grid = GridLayout(this).apply { columnCount = 3 }
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "پاک", "0", "")
        for (l in labels) {
            val lp = GridLayout.LayoutParams().apply {
                width = dp(90)
                height = dp(60)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            if (l.isEmpty()) {
                grid.addView(Space(this), lp)
            } else {
                val b = Button(this).apply {
                    text = l
                    textSize = 20f
                    setOnClickListener { onKey(l) }
                }
                if (l == "1") firstKey = b
                grid.addView(b, lp)
            }
        }
        col.addView(
            grid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )

        f.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return f
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("tvb:$s").toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun refreshDots() {
        lockDots.text = List(PIN_LEN) { i -> if (i < entered.length) "●" else "○" }
            .joinToString(" ")
    }

    private fun showLock() {
        locked = true
        entered.setLength(0)
        lockMode = if (prefs.getString("pin", null) == null) 1 else 0
        lockTitle.text = if (lockMode == 1) "یک رمز ۴ رقمی بسازید" else "رمز را وارد کنید"
        refreshDots()
        lock.visibility = View.VISIBLE
        lock.bringToFront()
        firstKey.requestFocus()
    }

    private fun unlock() {
        locked = false
        lock.visibility = View.GONE
        if (!loaded) {
            loaded = true
            web.loadUrl(START_URL)
        }
    }

    private fun onKey(l: String) {
        if (l == "پاک") {
            if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
        } else if (entered.length < PIN_LEN) {
            entered.append(l)
        }
        refreshDots()
        if (entered.length == PIN_LEN) submit()
    }

    private fun submit() {
        val pin = entered.toString()
        entered.setLength(0)
        when (lockMode) {
            0 -> if (hash(pin) == prefs.getString("pin", null)) {
                unlock()
            } else {
                lockTitle.text = "رمز اشتباه است"
            }
            1 -> {
                firstPin = pin
                lockMode = 2
                lockTitle.text = "رمز را دوباره وارد کنید"
            }
            2 -> if (pin == firstPin) {
                prefs.edit().putString("pin", hash(pin)).apply()
                unlock()
            } else {
                lockMode = 1
                lockTitle.text = "رمزها یکی نبود. رمز جدید بسازید"
            }
        }
        refreshDots()
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
    }

    // هر بار برنامه از جلوی صفحه برود، دوباره قفل می‌شود
    override fun onStop() {
        super.onStop()
        web.onPause()
        showLock()
    }

    // ---------- بقیه برنامه ----------

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
        // وقتی قفل است: ارقام ریموت کار کنند، جهت‌ها بین دکمه‌ها بروند
        if (locked) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                val k = e.keyCode
                if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                    onKey((k - KeyEvent.KEYCODE_0).toString())
                    return true
                }
                if (k == KeyEvent.KEYCODE_DEL) {
                    onKey("پاک")
                    return true
                }
            }
            return super.dispatchKeyEvent(e)
        }

        if (customView != null) return super.dispatchKeyEvent(e)

        val isDown = e.action == KeyEvent.ACTION_DOWN
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
            locked -> finish()
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
