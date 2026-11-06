package com.dailytodo.mydeepseek

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlin.math.*

/** Implements the existing Electron window bridge using a clipped Android window.
 * CSS coordinates stay in dp (scale=1). The original renderer keeps its full canvas;
 * only the native touch window is cropped to the visible pet and its controls. */
@SuppressLint("SetJavaScriptEnabled")
class PetScene(
    private val context: Context,
    private val index: Int,
    private val preview: FrameLayout? = null,
    private val onFlight: (PetScene, JSONObject) -> Unit = { _, _ -> },
    private val onHit: (String, Double, Double) -> Unit = { _, _, _ -> },
    private val onReady: (PetScene) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onChatRequested: (PetScene) -> Unit = {},
    private val trackCollisions: Boolean = false,
    private val onInteraction: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val onWhisperRequested: (PetScene, String) -> Unit = { _, _ -> }
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density.toDouble()
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
    private val browser = WebView(context)
    private var attached = false
    private var destroyed = false
    private var rendererReady = false
    private var hasBounds = false
    private var readySent = false
    private var failed = false
    private val startupTimeout = Runnable {
        if (!destroyed && !readySent) fail("等待动画加载超时，请重新放出宠物；如仍失败，请更新系统 WebView。")
    }
    private var bounds: PetFrame? = null
    private val pendingBounds = FrameMailbox<PetFrame>()
    private val pendingRegions = FrameMailbox<JSONArray>()
    private var balanceObserver: ((JSONObject) -> Unit)? = null
    private val balanceTimeout = Runnable {
        if (balanceObserver != null) {
            clearBalanceObserver()
            val state = PetApi.get(context).accountState()
            state.put("balanceBusy", false).put("error", "余额查询超时，请稍后手动再试。")
            showBalanceReply(state)
        }
    }
    private var regions = JSONArray()
    private var geometry = screenGeometry()
    var petId = "android-$index"
        private set
    private val params = WindowManager.LayoutParams(
        (120 * density).toInt(), (120 * density).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }

    init {
        browser.setBackgroundColor(Color.TRANSPARENT)
        browser.resumeTimers()
        browser.setOnLongClickListener { true }
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        browser.isVerticalScrollBarEnabled = false
        browser.isHorizontalScrollBarEnabled = false
        browser.addJavascriptInterface(Bridge(), "AndroidPet")
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = localResponse(request)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host != ASSET_HOST
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("宠物页面没有成功加载：${error.description}")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("系统回收了桌宠画面，请重新放它出来。")
                return true
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                    Log.e("DSHPet", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        // Give Chromium a real viewport before the first JS/native bounds handshake.
        root.addView(browser, FrameLayout.LayoutParams(
            (geometry.getDouble("width") * density).toInt(),
            (geometry.getDouble("height") * density).toInt()))
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) evaluate("window.PetAndroid?.closeMenus()")
            false
        }
        if (preview != null) {
            preview.addView(root, FrameLayout.LayoutParams(1, 1))
        } else {
            wm.addView(root, params)
        }
        attached = true
        val settings = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
        val size = settings.getInt("size", 200).coerceIn(120, 300)
        val width = geometry.optDouble("width")
        val height = geometry.optDouble("height")
        val url = Uri.Builder().scheme("https").authority(ASSET_HOST).path("/pet/index.html")
            .appendQueryParameter("configUrl", "https://$ASSET_HOST/dsh-pet-7340/config")
            .appendQueryParameter("workAreaW", width.toString())
            .appendQueryParameter("workAreaH", height.toString())
            .appendQueryParameter("scale", "1")
            .appendQueryParameter("petSize", size.toString())
            .appendQueryParameter("instanceIndex", index.toString())
            .appendQueryParameter("openTarget", "standalone").build()
        browser.loadUrl(url.toString())
        handler.postDelayed(startupTimeout, 12000)
    }

    private inner class Bridge {
        @JavascriptInterface fun setBounds(x: Double, y: Double, width: Double, height: Double,
                                         boxX: Double, boxY: Double, size: Double, bottomPad: Double,
                                         vx: Double, vy: Double) {
            val value = PetFrame(x, y, width, height, boxX, boxY, size, bottomPad, vx, vy)
            if (!value.valid()) {
                fail("没有取得有效的宠物窗口尺寸，请重新放它出来。")
                return
            }
            if (pendingBounds.offer(value)) handler.post { if (!destroyed) scheduleLayout() }
        }
        @JavascriptInterface fun setInputRegions(json: String) {
            val value = runCatching { JSONObject(json).optJSONArray("rects") }.getOrNull() ?: JSONArray()
            if (pendingRegions.offer(value)) handler.post { if (!destroyed) scheduleLayout() }
        }
        @JavascriptInterface fun ready(id: String) {
            if (id.length <= 100) handler.post {
                if (!destroyed) { petId = id; rendererReady = true; notifyReady() }
            }
        }
        @JavascriptInterface fun error(reason: String) { handler.post { fail(reason.take(600)) } }
        @JavascriptInterface fun reportFlight(json: String) {
            val state = runCatching { JSONObject(json) }.getOrNull() ?: return
            handler.post { if (!destroyed) onFlight(this@PetScene, state) }
        }
        @JavascriptInterface fun reportCollide(id: String, vx: Double, vy: Double) {
            if (vx.isFinite() && vy.isFinite()) handler.post { if (!destroyed) onHit(id, vx, vy) }
        }
        @JavascriptInterface fun openSettings() {
            handler.post {
                if (!destroyed) context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        @JavascriptInterface fun toast(text: String) { handler.post { if (!destroyed) message(text.take(200)) } }
        @JavascriptInterface fun openChat() { handler.post { if (!destroyed) onChatRequested(this@PetScene) } }
        @JavascriptInterface fun openTodos() { handler.post {
            if (!destroyed) context.startActivity(Intent(context, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
        @JavascriptInterface fun whisper(image: String) {
            handler.post { if (!destroyed) onWhisperRequested(this@PetScene, image.take(150)) }
        }
        @JavascriptInterface fun interaction(sleeping: Boolean, busy: Boolean) {
            handler.post { if (!destroyed) onInteraction(petId, sleeping, busy) }
        }
        @JavascriptInterface fun showBalance() {
            handler.post { if (!destroyed) queryBalance() }
        }
    }

    private fun queryBalance() {
        clearBalanceObserver()
        val api = PetApi.get(context)
        val state = api.accountState()
        if (!state.optBoolean("ready") || !state.optBoolean("configured") || state.optBoolean("connecting")) {
            showBalanceReply(state)
            return
        }
        // Share an in-flight balance refresh, never open the chat or request IME focus.
        api.refreshBalance()
        val observer: (JSONObject) -> Unit = { value ->
            if (!destroyed) {
                showBalanceReply(value)
                if (!value.optBoolean("balanceBusy")) clearBalanceObserver()
            }
        }
        balanceObserver = observer
        handler.postDelayed(balanceTimeout, 60000)
        api.observeAccount(observer)
    }
    private fun showBalanceReply(value: JSONObject) = evaluate("window.PetAndroid?.balanceReply($value)")
    private fun clearBalanceObserver() {
        balanceObserver?.let { PetApi.get(context).removeAccount(it) }
        balanceObserver = null
        handler.removeCallbacks(balanceTimeout)
    }

    private var layoutPending = false
    private fun scheduleLayout() {
        if (layoutPending || destroyed) return
        layoutPending = true
        root.postOnAnimation {
            layoutPending = false
            if (!destroyed) {
                val frame = pendingBounds.take()
                if (frame != null) bounds = frame
                pendingRegions.take()?.let { regions = it }
                applyBounds()
                if (frame != null && trackCollisions) onFlight(this, JSONObject()
                    .put("x", frame.boxX).put("y", frame.boxY).put("size", frame.size)
                    .put("bottomPad", frame.bottomPad).put("vx", frame.vx).put("vy", frame.vy))
            }
        }
    }

    private fun applyBounds() {
        val frame = bounds ?: return
        val x = frame.x
        val y = frame.y
        val size = frame.size
        val safeW = geometry.getDouble("width")
        val safeH = geometry.getDouble("height")
        val padding = size * 0.16
        var left = frame.boxX - padding
        var top = frame.boxY - padding
        var right = left + size + padding * 2
        var bottom = top + size * 9 / 16 + frame.bottomPad + padding * 2
        for (i in 0 until regions.length()) {
            val r = regions.optJSONObject(i) ?: continue
            val rx = r.optDouble("x", Double.NaN) + x
            val ry = r.optDouble("y", Double.NaN) + y
            val rw = r.optDouble("width", 0.0)
            val rh = r.optDouble("height", 0.0)
            if (listOf(rx, ry, rw, rh).any { !it.isFinite() } || rw <= 0 || rh <= 0) continue
            left = min(left, rx); top = min(top, ry)
            right = max(right, rx + rw); bottom = max(bottom, ry + rh)
        }
        left = left.coerceIn(0.0, safeW - 1)
        top = top.coerceIn(0.0, safeH - 1)
        right = right.coerceIn(left + 1, safeW)
        bottom = bottom.coerceIn(top + 1, safeH)
        val nativeX = floor(left * density).toInt()
        val nativeY = floor(top * density).toInt()
        val width = PetFrame.extent((right - left) * density) + 1
        val height = PetFrame.extent((bottom - top) * density) + 1
        val canvasW = (frame.width.coerceIn(1.0, safeW * 4) * density).roundToInt()
        val canvasH = (frame.height.coerceIn(1.0, safeH * 4) * density).roundToInt()
        val lp = browser.layoutParams as FrameLayout.LayoutParams
        if (lp.width != canvasW || lp.height != canvasH) {
            lp.width = canvasW; lp.height = canvasH; browser.layoutParams = lp
        }
        val tx = (x * density - nativeX).toFloat()
        val ty = (y * density - nativeY).toFloat()
        if (browser.translationX != tx) browser.translationX = tx
        if (browser.translationY != ty) browser.translationY = ty
        if (preview != null) {
            val rootParams = root.layoutParams as FrameLayout.LayoutParams
            if (rootParams.width != width || rootParams.height != height) {
                rootParams.width = width; rootParams.height = height; root.layoutParams = rootParams
            }
            root.translationX = nativeX.toFloat()
            root.translationY = nativeY.toFloat()
        } else {
            val actualX = nativeX + geometry.getInt("leftInsetPx")
            val actualY = nativeY + geometry.getInt("topInsetPx")
            if (params.x != actualX || params.y != actualY || params.width != width || params.height != height) {
                params.x = actualX; params.y = actualY; params.width = width; params.height = height
                try { wm.updateViewLayout(root, params) }
                catch (error: RuntimeException) { fail(error.localizedMessage ?: "悬浮窗口更新失败") }
            }
        }
        hasBounds = true
        notifyReady()
    }

    private fun notifyReady() {
        if (destroyed || failed || readySent || !hasBounds || !rendererReady) return
        readySent = true
        handler.removeCallbacks(startupTimeout)
        onReady(this)
    }

    private fun fail(reason: String) {
        handler.post {
            if (!destroyed && !failed) {
                failed = true
                handler.removeCallbacks(startupTimeout)
                Log.e("DSHPet", reason)
                if (preview != null) message(reason)
                onFailure(reason)
            }
        }
    }

    private fun screenGeometry(): JSONObject {
        val metrics = context.resources.displayMetrics
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        var top = 0
        var left = 0
        if (preview != null && preview.width > 0 && preview.height > 0) {
            width = preview.width; height = preview.height
        } else if (Build.VERSION.SDK_INT >= 30) {
            val window = wm.maximumWindowMetrics
            val bars = window.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            width = window.bounds.width() - bars.left - bars.right
            height = window.bounds.height() - bars.top - bars.bottom
            top = bars.top
            left = bars.left
        }
        return JSONObject().put("width", max(1, width) / density)
            .put("height", max(1, height) / density).put("topInsetPx", top).put("leftInsetPx", left)
    }

    fun updateGeometry() {
        if (destroyed) return
        geometry = screenGeometry()
        val area = JSONObject().put("x", 0).put("y", 0)
            .put("width", geometry.getDouble("width")).put("height", geometry.getDouble("height"))
        val value = JSONObject().put("hull", area).put("areas", JSONArray().put(area))
            .put("panels", JSONArray().put(area)).put("primaryIndex", 0)
        evaluate("window.AndroidPetHost?.displays($value)")
        scheduleLayout()
    }

    fun flightStates(value: JSONObject) = evaluate("window.AndroidPetHost?.flightStates($value)")
    fun anchorRect() = Rect(params.x, params.y, params.x + params.width, params.y + params.height)
    fun chatVisibility(visible: Boolean) = evaluate("window.PetAndroid?.chatVisibility($visible)")
    fun apiReply(text: String, record: JSONObject? = null) = evaluate("window.PetAndroid?.apiReply(${JSONObject.quote(text)},${record ?: JSONObject()})")
    fun companionReply(value: JSONObject) = evaluate("window.PetAndroid?.companionReply($value)")
    fun todoReminder(text: String) = evaluate("window.PetAndroid?.todoReminder(${JSONObject.quote(text.take(120))})")
    fun hit(vx: Double, vy: Double) =
        evaluate("window.AndroidPetHost?.hit({vx:$vx,vy:$vy})")
    fun paused(value: Boolean) {
        if (destroyed) return
        if (value) {
            evaluate("window.PetAndroid?.setPaused(true)")
            browser.onPause()
            root.visibility = View.INVISIBLE
            if (preview == null) browser.pauseTimers()
        } else {
            root.visibility = View.VISIBLE
            browser.onResume()
            if (preview == null) browser.resumeTimers()
            evaluate("window.PetAndroid?.setPaused(false)")
        }
    }
    private fun evaluate(js: String) { if (!destroyed) browser.evaluateJavascript(js, null) }

    fun destroy() {
        if (destroyed) return
        clearBalanceObserver()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (attached) {
            if (preview != null) preview.removeView(root)
            else runCatching { wm.removeViewImmediate(root) }
            attached = false
        }
        browser.stopLoading()
        browser.removeJavascriptInterface("AndroidPet")
        root.removeAllViews()
        browser.destroy()
    }

    private fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun localResponse(request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        if (uri.scheme != "https" || uri.host != ASSET_HOST) return response(403, "Offline")
        val path = uri.path ?: return response(404, "Missing")
        if (path.split('/').any { it == ".." || it == "." } || path.contains('\\')) return response(403, "Invalid")
        val file = when {
            path == "/dsh-pet-7340/config" -> "pet/config.json"
            path.startsWith("/pet/") -> path.removePrefix("/")
            path.startsWith("/dsh-pet-7340/thumb/main/") ->
                "pet/assets/webm/" + path.substringAfterLast('/')
            path.startsWith("/dsh-pet-7340/font/") -> "pet/assets/fonts/" + path.substringAfterLast('/')
            path.startsWith("/dsh-pet-7340/sound/") -> "pet/assets/sound/" + path.substringAfterLast('/')
            path.startsWith("/dsh-pet-7340/pic/memes/") -> "pet/assets/memes/" + path.substringAfterLast('/')
            path.startsWith("/dsh-pet-7340/pic/") -> "pet/assets/pic/" + path.substringAfterLast('/')
            else -> return response(503, "Offline")
        }
        val mime = when (file.substringAfterLast('.')) {
            "html" -> "text/html"; "js" -> "text/javascript"; "json" -> "application/json"
            "css" -> "text/css"; "webm" -> "video/webm"; "wav" -> "audio/wav"
            "ttf" -> "font/ttf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"; else -> "application/octet-stream"
        }
        return try {
            val stream = context.assets.open(file)
            val total = stream.available().toLong()
            val range = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
            val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
            if (start >= total || start > end) { stream.close(); return response(416, "Range") }
            var remainingSkip = start
            while (remainingSkip > 0) {
                val skipped = stream.skip(remainingSkip)
                if (skipped <= 0) throw java.io.IOException("Cannot seek asset")
                remainingSkip -= skipped
            }
            var remaining = end - start + 1
            val limited = object : FilterInputStream(stream) {
                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val result = super.read()
                    if (result >= 0) remaining--
                    return result
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val result = `in`.read(buffer, offset, min(length.toLong(), remaining).toInt())
                    if (result > 0) remaining -= result
                    return result
                }
            }
            val headers = mutableMapOf("Content-Length" to (end - start + 1).toString(),
                "Accept-Ranges" to "bytes", "Cache-Control" to "no-cache")
            if (match != null) headers["Content-Range"] = "bytes $start-$end/$total"
            WebResourceResponse(mime, "UTF-8", if (match == null) 200 else 206,
                if (match == null) "OK" else "Partial Content", headers, limited)
        } catch (_: java.io.IOException) { response(404, "Missing") }
    }

    private fun response(code: Int, reason: String) = WebResourceResponse(
        "application/json", "UTF-8", code, reason, emptyMap(),
        ByteArrayInputStream("""{"ok":false,"reason":"offline-host-unavailable"}""".toByteArray()))

    companion object { const val ASSET_HOST = "appassets.androidplatform.net" }
}
