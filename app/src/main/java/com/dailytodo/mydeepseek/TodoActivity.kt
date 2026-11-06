package com.dailytodo.mydeepseek

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.AlarmClock
import android.speech.*
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Local todo UI. Microphone is used only while this foreground activity is visible. */
class TodoActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { PetTodos.get(this) }
    private val api by lazy { PetApi.get(this) }
    private lateinit var browser: WebView
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var voiceGeneration = 0
    private var destroyed = false
    private val preferences by lazy { getSharedPreferences("todo-ui", MODE_PRIVATE) }
    private val todoObserver: (JSONObject) -> Unit = { publish() }
    private val apiObserver: (JSONObject) -> Unit = { publish() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        repository.reschedule(); publish()
        if (!it) notice("待办已保留；开启通知后才能显示到期提醒。")
        else if (!TodoAlarms.exactAllowed(this)) offerReminderSetup()
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) beginVoice() else voiceStatus(false, "没有麦克风权限，可以继续打字。")
    }
    private val systemVoice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        voiceStatus(false, "")
        if (result.resultCode == RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.let { transcript(it, true) }
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
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
            addJavascriptInterface(Bridge(), "TodoNative")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = resource(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https" || request.url.host != PetScene.ASSET_HOST || request.url.path?.startsWith("/todo/") != true
                override fun onPageFinished(view: WebView, url: String) { publish() }
            }
        }
        root.addView(browser, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION") view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        repository.observe(todoObserver); api.observeAccount(apiObserver)
        browser.loadUrl("https://${PetScene.ASSET_HOST}/todo/index.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { browser.evaluateJavascript("window.TodoUI?.back() || false") { if (it != "true") finish() } }
        })
    }
    inner class Bridge {
        @JavascriptInterface fun ready() { handler.post { publish() } }
        @JavascriptInterface fun close() { handler.post { finish() } }
        @JavascriptInterface fun navigate(page: String) { handler.post { if (page != "todo") AppNavigation.open(this@TodoActivity, page) } }
        @JavascriptInterface fun save(json: String) { handler.post {
            try {
                require(json.length <= 30000)
                val value = JSONObject(json)
                val wantsReminder = if (value.has("id")) !value.isNull("dueAt") else {
                    val items = value.optJSONArray("items") ?: JSONArray()
                    (0 until items.length()).any { !items.getJSONObject(it).isNull("dueAt") }
                }
                val callback: (Boolean, String) -> Unit = { ok, message ->
                    evaluate("window.TodoUI?.saved($ok,${JSONObject.quote(message)})")
                    if (ok && wantsReminder) offerReminderSetup()
                }
                if (value.optString("id").isNotBlank()) repository.update(value.getString("id"), value, callback)
                else repository.add(value.getJSONArray("items"), callback)
            } catch (failure: Exception) { evaluate("window.TodoUI?.saved(false,${JSONObject.quote(failure.message ?: "待办格式不正确。")})") }
        } }
        @JavascriptInterface fun toggle(id: String, done: Boolean) { handler.post {
            repository.update(id, JSONObject().put("done", done)) { ok, message -> if (!ok) notice(message) }
        } }
        @JavascriptInterface fun remove(id: String) { handler.post {
            AlertDialog.Builder(this@TodoActivity).setTitle("删除这件小事？").setMessage("删除后不能恢复，同时取消对应提醒。")
                .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ ->
                    repository.delete(id) { ok, message -> if (!ok) notice(message) }
                }.show()
        } }
        @JavascriptInterface fun analyze(text: String) { handler.post {
            api.analyzeTodos(text) { drafts, error ->
                evaluate("window.TodoUI?.analysis(${drafts ?: "null"},${JSONObject.quote(error)})")
            }
        } }
        @JavascriptInterface fun cancelAnalysis() { handler.post { api.cancelTodoAnalysis(); notice("已停止整理，已产生用量以官方账单为准。") } }
        @JavascriptInterface fun connectApi() { handler.post {
            startActivity(Intent(this@TodoActivity, MainActivity::class.java).putExtra("connectApi", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } }
        @JavascriptInterface fun notifications() { handler.post { enableNotifications() } }
        @JavascriptInterface fun exact() { handler.post { enableExact() } }
        @JavascriptInterface fun batterySettings() { handler.post { openBatterySettings() } }
        @JavascriptInterface fun systemAlarm(id: String) { handler.post { openSystemAlarm(id) } }
        @JavascriptInterface fun voice() { handler.post { startVoice() } }
        @JavascriptInterface fun stopVoice() { handler.post { recognizer?.stopListening() } }
        @JavascriptInterface fun cancelVoice() { handler.post { endVoice() } }
    }
    private fun publish() {
        if (!::browser.isInitialized || destroyed) return
        val state = repository.state().put("notifications", TodoAlarms.notificationsAllowed(this))
            .put("exact", TodoAlarms.exactAllowed(this)).put("api", api.accountState().optBoolean("configured"))
            .put("analyzing", api.accountState().optBoolean("todoBusy"))
        evaluate("window.TodoUI?.update($state)")
    }
    private fun evaluate(script: String) { if (!destroyed && ::browser.isInitialized) browser.evaluateJavascript(script, null) }
    private fun notice(text: String) = evaluate("window.TodoUI?.notice(${JSONObject.quote(text)})")
    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!preferences.getBoolean("notificationAsked", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            .onFailure { notice("无法打开系统通知设置，请在手机的应用设置中开启通知。") }
    }
    private fun enableExact() {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }.onFailure { notice("系统未提供该入口，将使用可能延迟的普通提醒。") }
    }
    private fun offerReminderSetup() {
        if (isFinishing || isDestroyed) return
        if (!TodoAlarms.notificationsAllowed(this)) {
            AlertDialog.Builder(this).setTitle("允许待办到期通知")
                .setMessage("待办已保存在手机。开启通知后，即使退出页面也能收到系统提醒；可能还需要允许准时提醒。")
                .setNegativeButton("稍后设置", null).setPositiveButton("去开启") { _, _ -> enableNotifications() }.show()
        } else if (!TodoAlarms.exactAllowed(this)) {
            AlertDialog.Builder(this).setTitle("允许准时提醒")
                .setMessage("待办已保存。允许“闹钟和提醒”可使用系统精确提醒；不允许时仍会尝试普通提醒，但可能延迟。")
                .setNegativeButton("稍后设置", null).setPositiveButton("去设置") { _, _ -> enableExact() }.show()
        }
    }
    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }.recoverCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.onFailure { notice("请在手机系统设置中找到本应用的电池和后台运行设置。") }
    }
    private fun openSystemAlarm(id: String) {
        val rows = repository.state().getJSONArray("items")
        val item = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        val due = item?.let(TodoRules::due)
        if (item == null || item.optBoolean("done") || due == null || due <= System.currentTimeMillis()) {
            notice("请先为未完成的待办设置未来的提醒时间。") ; return
        }
        val zone = java.time.ZoneId.systemDefault()
        val prefill = TodoRules.clockCanPrefill(due, System.currentTimeMillis(), zone)
        val time = java.time.Instant.ofEpochMilli(due).atZone(zone)
        val detail = if (prefill) "将把事项和 ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')} 交给手机时钟，请在时钟中确认。"
            else "手机时钟接口不能可靠传入该日期或秒数，将打开时钟供你手动设置，请仔细核对日期和时间。应用内通知仍按原时间安排。"
        AlertDialog.Builder(this).setTitle("用手机时钟设置闹钟")
            .setMessage(detail + "\n\n时钟闹钟由手机时钟单独管理；修改或删除待办不会同步更改它。事项内容会交给你选择的时钟应用。")
            .setNegativeButton("取消", null).setPositiveButton("打开时钟") { _, _ ->
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                if (prefill) intent.putExtra(AlarmClock.EXTRA_HOUR, time.hour).putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, item.getString("text"))
                runCatching { startActivity(intent) }.onFailure { notice("没有找到可用的手机时钟应用，请手动打开时钟设置。") }
            }.show()
    }
    private fun startVoice() {
        if (listening) return
        if (!preferences.getBoolean("voiceAcknowledged", false)) {
            AlertDialog.Builder(this).setTitle("用语音记一件事")
                .setMessage("语音由手机系统识别服务处理，可能联网发送音频。应用不保存录音；识别文字只有在你点“AI 整理”后才发送给 DeepSeek 并按 API 规则计费。")
                .setNegativeButton("先打字", null).setPositiveButton("开始语音") { _, _ ->
                    preferences.edit().putBoolean("voiceAcknowledged", true).apply(); startVoice()
                }.show(); return
        }
        if (SpeechRecognizer.isRecognitionAvailable(this) || (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            else beginVoice()
        } else {
            try {
                systemVoice.launch(voiceIntent().putExtra(RecognizerIntent.EXTRA_PROMPT, "说出事项和时间，识别后可修改"))
            } catch (_: ActivityNotFoundException) { voiceStatus(false, "手机没有可用的系统语音识别服务。可使用输入法的麦克风，或直接打字。") }
        }
    }
    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    private fun beginVoice() {
        endVoice()
        val generation = ++voiceGeneration
        try {
            val local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            recognizer = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (generation == voiceGeneration) voiceStatus(true, "正在听，说完可点结束…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (generation == voiceGeneration) voiceStatus(true, "正在识别…") }
                override fun onError(error: Int) {
                    if (generation != voiceGeneration) return
                    endVoice()
                    voiceStatus(false, when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，可以再说一次或打字。"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务连接失败，可使用输入法语音或打字。"
                        else -> "系统语音服务暂不可用，可能需要安装语言包。可使用输入法语音或打字。"
                    })
                }
                override fun onResults(results: Bundle?) {
                    if (generation != voiceGeneration) return
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { transcript(it, true) }
                    endVoice()
                }
                override fun onPartialResults(results: Bundle?) {
                    if (generation != voiceGeneration) return
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { transcript(it, false) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            voiceStatus(true, "准备听你说…"); recognizer?.startListening(voiceIntent())
        } catch (_: Exception) { endVoice(); voiceStatus(false, "语音服务没有启动，可继续打字。") }
    }
    private fun transcript(text: String, final: Boolean) = evaluate("window.TodoUI?.transcript(${JSONObject.quote(text.take(4000))},$final)")
    private fun voiceStatus(active: Boolean, message: String) {
        listening = active; evaluate("window.TodoUI?.voiceStatus($active,${JSONObject.quote(message)})")
    }
    private fun endVoice() {
        voiceGeneration++
        val previous = recognizer; recognizer = null
        previous?.cancel(); previous?.destroy()
        voiceStatus(false, "")
    }
    override fun onResume() { super.onResume(); if (::browser.isInitialized) { repository.reschedule(); publish() } }
    override fun onStop() { endVoice(); super.onStop() }
    override fun onDestroy() {
        endVoice(); api.cancelTodoAnalysis(); repository.remove(todoObserver); api.removeAccount(apiObserver)
        destroyed = true; handler.removeCallbacksAndMessages(null)
        browser.removeJavascriptInterface("TodoNative"); browser.destroy(); super.onDestroy()
    }
    private fun resource(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty()
        if (uri.scheme != "https" || uri.host != PetScene.ASSET_HOST || !path.startsWith("/todo/") ||
            path.split('/').any { it == "." || it == ".." } || path.contains('\\'))
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        val file = when (path) {
            "/todo/pet-font.ttf" -> "pet/assets/fonts/上首软糖体.ttf"
            "/todo/hero.png" -> "control/hero.png"
            else -> path.removePrefix("/")
        }
        val mime = when (file.substringAfterLast('.')) {
            "html" -> "text/html"; "js" -> "text/javascript"; "css" -> "text/css"; "ttf" -> "font/ttf"; else -> "image/png"
        }
        return try { WebResourceResponse(mime, "UTF-8", assets.open(file)) }
        catch (_: Exception) { WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) }
    }
}
