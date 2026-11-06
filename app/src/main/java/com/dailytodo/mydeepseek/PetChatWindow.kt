package com.dailytodo.mydeepseek

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.*

/** A separate bounded overlay: input focus is requested only while typing. */
@SuppressLint("SetJavaScriptEnabled")
class PetChatWindow(
    private val context: Context,
    private val pet: String,
    anchor: Rect,
    private val onClose: () -> Unit
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val api = PetApi.get(context)
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val browser = WebView(context)
    private var destroyed = false
    private var typing = false
    private var attached = false
    private var area = screenArea()
    private var normalHeight = min(dp(460), (area.height() * .72).roundToInt())
    private val width = min(dp(370), area.width() - dp(24))
    private val params = WindowManager.LayoutParams(
        width, normalHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = (anchor.centerX() - width / 2).coerceIn(area.left + dp(12), area.right - width - dp(12))
        y = if (anchor.bottom + dp(8) + height <= area.bottom) anchor.bottom + dp(8)
            else (anchor.top - height - dp(8)).coerceIn(area.top + dp(8), area.bottom - height - dp(8))
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    private val accountObserver: (JSONObject) -> Unit = { value -> evaluate("window.PetChat?.account($value)") }
    private val chatObserver: (String, JSONObject) -> Unit = { id, value ->
        if (id == pet) evaluate("window.PetChat?.event($value)")
    }

    init {
        browser.setBackgroundColor(Color.TRANSPARENT)
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            setSupportZoom(false); textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        browser.isVerticalScrollBarEnabled = false
        browser.addJavascriptInterface(Bridge(), "PetChatNative")
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = resource(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host != PetScene.ASSET_HOST ||
                    request.url.path?.startsWith("/chat/") != true
            override fun onPageFinished(view: WebView, url: String) {
                publish()
                evaluate("window.PetChat?.focusComposer()")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                close(); return true
            }
        }
        browser.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) keyboard(false)
            false
        }
        wm.addView(browser, params); attached = true
        api.observeAccount(accountObserver); api.observeChat(chatObserver)
        browser.loadUrl("https://${PetScene.ASSET_HOST}/chat/index.html")
        api.refreshBalance(false)
    }

    inner class Bridge {
        @JavascriptInterface fun ready() { handler.post { publish() } }
        @JavascriptInterface fun send(text: String) { handler.post { if (!destroyed) api.send(pet, text) } }
        @JavascriptInterface fun retry() { handler.post { if (!destroyed) api.retry(pet) } }
        @JavascriptInterface fun stop() { handler.post { if (!destroyed) api.stop(pet) } }
        @JavascriptInterface fun clear() { handler.post { if (!destroyed) api.clear(pet) } }
        @JavascriptInterface fun refreshBalance() { handler.post { if (!destroyed) api.refreshBalance() } }
        @JavascriptInterface fun close() { handler.post { this@PetChatWindow.close(true) } }
        @JavascriptInterface fun focusInput() { handler.post { if (!destroyed) keyboard(true) } }
        @JavascriptInterface fun blurInput() { handler.post { if (!destroyed) keyboard(false) } }
        @JavascriptInterface fun move(dx: Double, dy: Double) {
            if (!dx.isFinite() || !dy.isFinite()) return
            handler.post {
                if (!destroyed) {
                    params.x = (params.x + (dx * density).roundToInt()).coerceIn(area.left, area.right - params.width)
                    params.y = (params.y + (dy * density).roundToInt()).coerceIn(area.top, area.bottom - params.height)
                    updateWindow()
                }
            }
        }
        @JavascriptInterface fun connect() {
            handler.post {
                if (!destroyed) {
                    keyboard(false)
                    context.startActivity(Intent(context, MainActivity::class.java).putExtra("connectApi", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    close()
                }
            }
        }
    }
    private fun publish() {
        if (destroyed) return
        evaluate("window.PetChat?.account(${api.accountState()})")
        evaluate("window.PetChat?.event(${api.chatState(pet)})")
    }
    private fun keyboard(focus: Boolean) {
        if (destroyed || typing == focus) return
        typing = focus
        val input = context.getSystemService(InputMethodManager::class.java)
        if (focus) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.height = min(normalHeight, max(dp(240), (area.height() * .46).roundToInt()))
            params.y = area.top + dp(8)
            updateWindow()
            browser.isFocusableInTouchMode = true; browser.requestFocus()
            handler.postDelayed({ if (!destroyed && typing) input.showSoftInput(browser, InputMethodManager.SHOW_IMPLICIT) }, 100)
        } else {
            input.hideSoftInputFromWindow(browser.windowToken, 0)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.height = normalHeight
            params.y = params.y.coerceIn(area.top, max(area.top, area.bottom - normalHeight))
            updateWindow()
        }
    }
    private fun updateWindow() {
        try { if (attached && !destroyed) wm.updateViewLayout(browser, params) }
        catch (_: RuntimeException) { close() }
    }
    fun paused(value: Boolean) {
        if (destroyed) return
        if (value) keyboard(false)
        browser.visibility = if (value) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }
    fun relayout() {
        if (destroyed) return
        keyboard(false); area = screenArea()
        normalHeight = min(dp(460), (area.height() * .72).roundToInt())
        params.width = min(dp(370), area.width() - dp(24)); params.height = normalHeight
        params.x = params.x.coerceIn(area.left, area.right - params.width)
        params.y = params.y.coerceIn(area.top, area.bottom - params.height)
        updateWindow()
    }
    fun close(userInitiated: Boolean = false) {
        if (destroyed) return
        if (userInitiated && api.accountState().optString("activePet") == pet)
            Toast.makeText(context, "已收起，回复仍在生成。需要中断时可重新打开并点“停止”。", Toast.LENGTH_LONG).show()
        keyboard(false)
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        api.removeAccount(accountObserver); api.removeChat(chatObserver)
        if (attached) { runCatching { wm.removeViewImmediate(browser) }; attached = false }
        browser.removeJavascriptInterface("PetChatNative")
        browser.destroy()
        onClose()
    }
    private fun evaluate(script: String) { if (!destroyed) browser.evaluateJavascript(script, null) }
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun screenArea(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.maximumWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(metrics.bounds.left + bars.left, metrics.bounds.top + bars.top,
                metrics.bounds.right - bars.right, metrics.bounds.bottom - bars.bottom)
        }
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
    private fun resource(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty()
        if (uri.scheme != "https" || uri.host != PetScene.ASSET_HOST || !path.startsWith("/chat/") ||
            path.split('/').any { it == ".." || it == "." }) return missing()
        val file = when (path) {
            "/chat/avatar.png" -> "control/hero.png"
            "/chat/pet-font.ttf" -> "pet/assets/fonts/上首软糖体.ttf"
            else -> if (path.startsWith("/chat/memes/")) "pet/assets/memes/" + path.substringAfterLast('/')
                else path.removePrefix("/")
        }
        val mime = when (file.substringAfterLast('.')) {
            "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"
            "png" -> "image/png"; "ttf" -> "font/ttf"; else -> "application/octet-stream"
        }
        return try { WebResourceResponse(mime, "UTF-8", context.assets.open(file)) }
        catch (_: java.io.IOException) { missing() }
    }
    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Missing", emptyMap(),
        ByteArrayInputStream(ByteArray(0)))
}
