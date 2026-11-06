package com.dailytodo.mydeepseek

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Conversation lives in the app; the desktop pet remains an independent overlay. */
class ChatActivity : ComponentActivity() {
    private val api by lazy { PetApi.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var browser: WebView
    private var destroyed = false
    private val pet by lazy { intent.getStringExtra("petId")?.takeIf(DeepSeekProtocol::validPet) ?: "android-0" }
    private val accountObserver: (JSONObject) -> Unit = { evaluate("window.PetChat?.account($it)") }
    private val chatObserver: (String, JSONObject) -> Unit = { id, value -> if (id == pet) evaluate("window.PetChat?.event($value)") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(251, 246, 237)) }
        browser = WebView(this).apply {
            setBackgroundColor(Color.rgb(251, 246, 237))
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = false
                allowFileAccess = false; allowContentAccess = false
                setSupportZoom(false); textZoom = 100
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(Bridge(), "PetChatNative")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = resource(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme != "https" || request.url.host != PetScene.ASSET_HOST || request.url.path?.startsWith("/chat/") != true
                override fun onPageFinished(view: WebView, url: String) { publish(); evaluate("window.PetChat?.focusComposer()") }
            }
        }
        root.addView(browser, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        api.observeAccount(accountObserver); api.observeChat(chatObserver)
        browser.loadUrl("https://${PetScene.ASSET_HOST}/chat/index.html?surface=app")
        api.refreshBalance(false)
    }
    inner class Bridge {
        @JavascriptInterface fun ready() { handler.post { publish() } }
        @JavascriptInterface fun send(text: String) { handler.post { if (!destroyed) api.send(pet, text) } }
        @JavascriptInterface fun sendSticker(name: String) { handler.post { if (!destroyed) api.sendSticker(pet, name) } }
        @JavascriptInterface fun retry() { handler.post { api.retry(pet) } }
        @JavascriptInterface fun stop() { handler.post { api.stop(pet) } }
        @JavascriptInterface fun clear() { handler.post { api.clear(pet) } }
        @JavascriptInterface fun refreshBalance() { handler.post { api.refreshBalance() } }
        @JavascriptInterface fun close() { handler.post { finish() } }
        @JavascriptInterface fun focusInput() { handler.post {
            if (!destroyed) { browser.isFocusableInTouchMode = true; browser.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(browser, InputMethodManager.SHOW_IMPLICIT) }
        } }
        @JavascriptInterface fun blurInput() { handler.post {
            if (!destroyed) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(browser.windowToken, 0)
        } }
        @JavascriptInterface fun move(dx: Double, dy: Double) {}
        @JavascriptInterface fun connect() { handler.post {
            startActivity(Intent(this@ChatActivity, MainActivity::class.java).putExtra("connectApi", true)
                .putExtra("openPage", "settings").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } }
        @JavascriptInterface fun navigate(page: String) { handler.post { if (page != "chat") AppNavigation.open(this@ChatActivity, page) } }
    }
    private fun publish() { evaluate("window.PetChat?.account(${api.accountState()})"); evaluate("window.PetChat?.event(${api.chatState(pet)})") }
    private fun evaluate(script: String) { if (!destroyed) browser.evaluateJavascript(script, null) }
    override fun onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null)
        api.removeAccount(accountObserver); api.removeChat(chatObserver)
        browser.removeJavascriptInterface("PetChatNative"); browser.destroy(); super.onDestroy()
    }
    private fun resource(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty()
        if (uri.scheme != "https" || uri.host != PetScene.ASSET_HOST || !path.startsWith("/chat/") ||
            path.split('/').any { it == "." || it == ".." } || path.contains('\\')) return missing()
        val file = when {
            path == "/chat/avatar.png" -> "control/hero.png"
            path == "/chat/pet-font.ttf" -> "pet/assets/fonts/上首软糖体.ttf"
            path.startsWith("/chat/memes/") -> "pet/assets/memes/" + path.substringAfterLast('/')
            else -> path.removePrefix("/")
        }
        val mime = when (file.substringAfterLast('.')) { "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"; "png" -> "image/png"; "ttf" -> "font/ttf"; else -> "application/octet-stream" }
        return try { WebResourceResponse(mime, "UTF-8", assets.open(file)) } catch (_: Exception) { missing() }
    }
    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Missing", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
