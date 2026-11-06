package com.dailytodo.mydeepseek

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.text.InputType
import android.webkit.*
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Offline control page; only the foreground service owns the floating pet. */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("pet", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var browser: WebView
    private var pendingPermissionStart = false
    private var returnHomeWhenReady = false
    private val api by lazy { PetApi.get(this) }
    private var keyDialog: AlertDialog? = null
    private var billingDialog: AlertDialog? = null
    private var companionDialog: AlertDialog? = null
    private val accountObserver: (JSONObject) -> Unit = { publishAccount(it) }
    private val runtimeObserver: (PetRuntime.State) -> Unit = { state ->
        if (state.phase == "idle" || state.phase == "error") returnHomeWhenReady = false
        publishState()
        if (state.phase == "running" && returnHomeWhenReady) {
            returnHomeWhenReady = false
            openHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPermissionStart = savedInstanceState?.getBoolean("pendingStart") ?: false
        returnHomeWhenReady = savedInstanceState?.getBoolean("returnHome") ?: false
        val cream = Color.rgb(251, 246, 237)
        val root = FrameLayout(this).apply { setBackgroundColor(cream) }
        browser = WebView(this).apply {
            setBackgroundColor(cream)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                textZoom = 100
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(Controller(), "PetControl")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    controlResource(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https" || request.url.host != PetScene.ASSET_HOST ||
                        request.url.path?.startsWith("/control/") != true
                override fun onPageFinished(view: WebView, url: String) { publishState(); route(intent) }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                        Log.e("DSHPetControl", message.message())
                    return true
                }
            }
        }
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        browser.loadUrl("https://${PetScene.ASSET_HOST}/control/index.html")
        if (intent.getBooleanExtra("connectApi", false)) handler.post { showBillingNotice() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
    }

    inner class Controller {
        @JavascriptInterface fun ready() { handler.post { publishState(); publishAccount(api.accountState()) } }
        @JavascriptInterface fun addApiKey() { handler.post { showBillingNotice() } }
        @JavascriptInterface fun refreshBalance() { handler.post { api.refreshBalance() } }
        @JavascriptInterface fun deleteApiKey() {
            handler.post {
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle("移除这把密钥？")
                    .setMessage("会停止正在生成的回复。本机聊天记录会保留，离线桌宠可以继续陪你。")
                    .setNegativeButton("先留着", null).setPositiveButton("移除密钥") { _, _ ->
                        api.deleteKey { _, message -> browser.evaluateJavascript(
                            "window.PetUI?.toast(${JSONObject.quote(message)})", null) }
                    }.create()
                showWarmDialog(dialog)
            }
        }
        @JavascriptInterface fun openPricing() { handler.post { openPricingPage() } }
        @JavascriptInterface fun companionSettings() { handler.post { showCompanionSettings() } }
        @JavascriptInterface fun editPersona() { handler.post { showPersona() } }
        @JavascriptInterface fun editEmotion() { handler.post { showEmotion() } }
        @JavascriptInterface fun manageMemory() { handler.post { showMemories() } }
        @JavascriptInterface fun navigate(page: String) { handler.post { routePage(page, "android-0") } }
        @JavascriptInterface fun openTodos() { handler.post {
            returnHomeWhenReady = false
            startActivity(Intent(this@MainActivity, TodoActivity::class.java))
        } }
        @JavascriptInterface fun start() { handler.post { beginLaunch() } }
        @JavascriptInterface fun stop() {
            handler.post {
                returnHomeWhenReady = false
                stopService(Intent(this@MainActivity, PetOverlayService::class.java))
                if (PetRuntime.state.phase != "error") PetRuntime.update("idle")
            }
        }
        @JavascriptInterface fun requestOverlayPermission() {
            handler.post {
                pendingPermissionStart = true
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (error: RuntimeException) {
                    pendingPermissionStart = false
                    PetRuntime.update("error", "没有找到悬浮窗设置", error.localizedMessage.orEmpty())
                }
            }
        }
        @JavascriptInterface fun saveSettings(size: Int, count: Int) {
            handler.post {
                preferences.edit().putInt("size", size.coerceIn(120, 300))
                    .putInt("count", count.coerceIn(1, 3)).apply()
                publishState()
            }
        }
        @JavascriptInterface fun openHome() { handler.post { this@MainActivity.openHome() } }
        @JavascriptInterface fun openSource() {
            handler.post {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/PC2005-cloud/dsh-pet"))) }
            }
        }
    }

    private fun showPersona() {
        if (!api.accountState().optBoolean("ready")) { android.widget.Toast.makeText(this, "正在读取本机人设，请稍候。", android.widget.Toast.LENGTH_SHORT).show(); return }
        val value = api.accountState().optJSONObject("profile") ?: return
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12)) }
        val fields = linkedMapOf<String, EditText>()
        fun field(label: String, text: String, lines: Int, hint: String = ""): EditText {
            wrap.addView(TextView(this).apply { this.text = label; textSize = 14f; setPadding(0, dp(12), 0, 0) })
            return EditText(this).apply {
                setText(text); this.hint = hint; minLines = lines; maxLines = if (lines == 1) 1 else 8
                inputType = if (lines == 1) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                wrap.addView(this)
            }
        }
        wrap.addView(TextView(this).apply { text = "保留你原来的人设；分类可以按需填写。预设只填入空白分类，填完仍可修改。"; textSize = 12f })
        val name = field("名字（最多 40 字）", value.optString("name"), 1, "宠物的名字")
        val categories = value.optJSONObject("categories") ?: JSONObject()
        val presets = android.widget.Button(this).apply { text = "选择性格预设（只填空白项）"; isAllCaps = false }
        wrap.addView(presets)
        val sections = listOf(
            Triple("identity", "身份与背景", "例如：职业、兴趣、生活背景"),
            Triple("personality", "性格与喜好", "例如：温柔活泼、喜欢分享"),
            Triple("relationship", "与你的关系", "例如：如何称呼你、相处方式"),
            Triple("speech", "说话习惯", "例如：语气、口头禅、回复长短"),
            Triple("boundaries", "原则与禁忌", "例如：不喜欢的称呼、需要避开的内容"),
            Triple("examples", "示例对话", "例如：你说什么，它会怎样回答")
        )
        sections.forEach { (key, title, hint) -> fields[key] = field(title, categories.optString(key), 2, hint) }
        val persona = field("原有人设正文", value.optString("persona"), 4, "保留原来的人设，也可以继续编辑")
        val counter = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(121, 105, 94)) }
        wrap.addView(counter)
        fun updateCounter() { val count = PersonaRules.length(persona.text.toString()) + fields.values.sumOf { PersonaRules.length(it.text.toString()) }; counter.text = "$count / 10000 字 · 人设会随 API 请求发送，内容越长用量可能越高。" }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateCounter() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        persona.addTextChangedListener(watcher); fields.values.forEach { it.addTextChangedListener(watcher) }
        updateCounter()
        val style = field("表达方式（最多 500 字，可留空）", value.optString("style"), 2)
        presets.setOnClickListener {
            val names = arrayOf("温柔陪伴", "活泼元气", "冷静可靠")
            val examples = arrayOf(
                "温柔耐心，善于倾听，关心对方的感受。" to "自然亲切，语气柔和，不强行安慰。",
                "开朗好奇，喜欢分享，有时调皮，但尊重对方。" to "轻快自然，偶尔用简短口头禅和合适的表情。",
                "沉稳细心，尊重边界，愿意认真帮助对方。" to "清楚简洁，先回应重点，再按需补充。"
            )
            AlertDialog.Builder(this).setTitle("选择性格预设").setItems(names) { _, which ->
                if (fields.getValue("personality").text.isBlank()) fields.getValue("personality").setText(examples[which].first)
                if (fields.getValue("speech").text.isBlank()) fields.getValue("speech").setText(examples[which].second)
                android.widget.Toast.makeText(this, "预设已填入空白项，可继续修改后保存。", android.widget.Toast.LENGTH_SHORT).show()
            }.show()
        }
        val scroll = android.widget.ScrollView(this).apply { addView(wrap) }
        val dialog = AlertDialog.Builder(this).setTitle("给小家伙写人设").setView(scroll)
            .setNegativeButton("取消", null).setNeutralButton("恢复原设", null)
            .setPositiveButton("保存", null).create()
        showWarmDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.isEnabled = false
            val nextCategories = JSONObject(); fields.forEach { (key, input) -> nextCategories.put(key, input.text.toString()) }
            api.saveProfile(JSONObject().put("name", name.text.toString()).put("persona", persona.text.toString()).put("style", style.text.toString()).put("categories", nextCategories)) { ok, message ->
                saveButton.isEnabled = true
                if (ok) { dialog.dismiss(); browser.evaluateJavascript("window.PetUI?.toast(${JSONObject.quote(message)})", null) }
                else { persona.error = message; android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(this).setTitle("恢复原始人设？").setMessage("会替换当前编辑内容；保存后生效。")
                .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                    val original = api.defaultProfile(); name.setText(original.optString("name")); persona.setText(original.optString("persona")); style.setText(""); fields.values.forEach { it.setText("") }
                }.show()
        }
    }

    private fun showEmotion() {
        if (!api.accountState().optBoolean("ready")) { android.widget.Toast.makeText(this, "正在读取本机状态，请稍候。", android.widget.Toast.LENGTH_SHORT).show(); return }
        val state = api.accountState().optJSONObject("emotion") ?: return
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        wrap.addView(TextView(this).apply { text = "情绪和好感度保存在本机，会影响后续回复。自动变化使用本次聊天的回复，不额外发起请求，但可能增加少量输入和输出用量。" })
        api.accountState().optString("emotionError").takeIf { it.isNotBlank() }?.let { problem -> wrap.addView(TextView(this).apply { text = problem; setTextColor(Color.rgb(150, 75, 50)) }) }
        val moods = arrayOf("平静", "开心", "难过", "生气", "惊讶", "害羞", "委屈")
        val spinner = android.widget.Spinner(this).apply { adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, moods); setSelection(moods.indexOf(state.optString("mood")).coerceAtLeast(0)) }
        wrap.addView(TextView(this).apply { text = "当前情绪"; setPadding(0, dp(12), 0, 0) }); wrap.addView(spinner)
        val label = TextView(this); wrap.addView(label)
        val seek = android.widget.SeekBar(this).apply { max = 100; progress = state.optInt("affinity", 50).coerceIn(0, 100) }
        fun count() { label.text = "好感度：${seek.progress} / 100" }; count()
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { count() }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        }); wrap.addView(seek)
        val automatic = android.widget.Switch(this).apply { text = "根据聊天自动变化"; isChecked = state.optBoolean("auto") }; wrap.addView(automatic)
        val dialog = AlertDialog.Builder(this).setTitle("情绪与好感度").setView(wrap).setNegativeButton("取消", null)
            .setNeutralButton("重置", null).setPositiveButton("保存", null).create()
        showWarmDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE); button.isEnabled = false
            api.saveEmotion(JSONObject().put("mood", moods[spinner.selectedItemPosition]).put("affinity", seek.progress).put("auto", automatic.isChecked)) { ok, message ->
                button.isEnabled = true; android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); if (ok) dialog.dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(this).setTitle("重置情绪与好感度？").setMessage("会恢复初始数值，不会删除人设和聊天记录。")
                .setNegativeButton("取消", null).setPositiveButton("重置") { _, _ -> api.resetEmotion { ok, message ->
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); if (ok) { dialog.dismiss(); showEmotion() }
                } }.show()
        }
    }

    private fun showMemories() {
        val memory = PetMemories.get(this)
        memory.snapshot { state ->
            if (isFinishing || isDestroyed) return@snapshot
            val enabled = state.optBoolean("enabled")
            val rows = state.optJSONArray("items") ?: org.json.JSONArray()
            val status = if (state.optString("error").isNotEmpty()) state.optString("error") else
                (if (enabled) "自动整理已开启" else "自动整理未开启") + " · 待整理 ${state.optJSONArray("pending")?.length() ?: 0} 轮 · 最近一小时 ${state.optInt("used")}/3 次" +
                    if (state.optString("paused").isNotEmpty()) "\n已暂停：${state.optString("paused")}" else ""
            val labels = mutableListOf(if (enabled) "关闭自动整理" else "开启自动整理（会计费）")
            if (enabled && state.optString("paused").isNotEmpty()) labels.add("恢复后台整理")
            for (i in 0 until rows.length()) labels.add("记忆 ${i + 1} · " + rows.getJSONObject(i).optString("text"))
            val dialog = AlertDialog.Builder(this).setTitle("长期记忆 · ${rows.length()} 条\n$status\n记忆会加入后续聊天请求，可能增加用量；自动提取可能出错，请定期检查。")
                .setItems(labels.toTypedArray()) { _, index ->
                    val offset = if (enabled && state.optString("paused").isNotEmpty()) 2 else 1
                    when {
                        index == 0 && enabled -> AlertDialog.Builder(this).setTitle("关闭自动整理？")
                            .setMessage("将停止后续请求并清除未整理的对话片段，已保存记忆保留。已经发出的请求仍可能计费。")
                            .setNegativeButton("取消", null).setPositiveButton("关闭") { _, _ -> memory.enable(false) { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); showMemories() } }.show()
                        index == 0 -> AlertDialog.Builder(this).setTitle("开启自动整理会产生费用")
                            .setMessage("每完成 10 轮聊天，会额外向 DeepSeek 发送一次对话片段进行整理，每小时最多 3 次，超出后等待。生成的记忆加密保存在本机，可查看、修改、删除，后续聊天会携带部分记忆并可能增加用量。敏感内容请勿记录。是否开启？")
                            .setNegativeButton("暂不开启", null).setPositiveButton("确认开启") { _, _ -> memory.enable(true) { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); showMemories() } }.show()
                        offset == 2 && index == 1 -> memory.resume { message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); showMemories() }
                        index >= offset -> editMemory(rows.getJSONObject(index - offset))
                    }
                }.setNegativeButton("完成", null).create()
            showWarmDialog(dialog)
        }
    }
    private fun editMemory(row: JSONObject) {
        val memory = PetMemories.get(this)
        val input = EditText(this).apply { setText(row.optString("text")); minLines = 2; maxLines = 5 }
        AlertDialog.Builder(this).setTitle("查看和修改记忆").setView(input)
            .setNegativeButton("取消") { _, _ -> showMemories() }
            .setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(this).setTitle("删除这条记忆？").setNegativeButton("取消") { _, _ -> showMemories() }
                    .setPositiveButton("删除") { _, _ -> memory.update(row.getString("id"), null) { showMemories() } }.show()
            }
            .setPositiveButton("保存") { _, _ -> memory.update(row.getString("id"), input.text.toString()) { message ->
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show(); showMemories()
            } }.show()
    }

    private fun showBillingNotice() {
        if (isFinishing || isDestroyed || keyDialog?.isShowing == true || billingDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("使用 API 会产生费用")
            .setMessage(DeepSeekProtocol.BILLING_NOTICE + "\n\n不会自动充值。自动陪伴默认关闭，可在陪伴设置中单独开启；如已开启，更换密钥后会继续使用新密钥。")
            .setNegativeButton("暂不接入", null)
            .setNeutralButton("查看官方价格") { _, _ -> openPricingPage() }
            .setPositiveButton("我已知晓，继续") { _, _ -> showKeyEntry() }.create()
        billingDialog = dialog
        dialog.setOnDismissListener { billingDialog = null }
        showWarmDialog(dialog)
    }
    private fun showKeyEntry() {
        if (isFinishing || isDestroyed || keyDialog?.isShowing == true) return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val description = TextView(this).apply {
            text = "密钥加密保存在本机，仅用于连接 DeepSeek。验证成功后保存。"
            textSize = 13f; setTextColor(Color.rgb(121, 105, 94))
        }
        val input = EditText(this).apply {
            hint = "粘贴 DeepSeek API 密钥"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setAutofillHints()
        }
        val feedback = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(144, 74, 52)) }
        layout.addView(description); layout.addView(input); layout.addView(feedback)
        var saved = false
        val dialog = AlertDialog.Builder(this).setTitle("连接 DeepSeek").setView(layout)
            .setNegativeButton("取消", null).setPositiveButton("验证并保存", null).create()
        keyDialog = dialog
        dialog.setOnDismissListener {
            input.text?.clear()
            if (!saved) api.cancelConnect()
            keyDialog = null
        }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = input.text.toString()
                if (!DeepSeekProtocol.validKey(candidate.trim())) {
                    feedback.text = "请检查密钥是否完整，且不含空格或换行。"
                } else {
                    feedback.text = "正在验证连接与余额…"
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    api.connect(candidate, billingAccepted = true) { success, message ->
                        if (!isDestroyed && dialog.isShowing) {
                            if (success) { saved = true; input.text?.clear(); dialog.dismiss() }
                            else { feedback.text = message; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                        }
                    }
                }
            }
        }
        showWarmDialog(dialog)
    }
    private fun showWarmDialog(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.rgb(251, 246, 237)); cornerRadius = dp(24).toFloat()
        })
    }
    private fun showCompanionSettings() {
        if (isFinishing || isDestroyed || companionDialog?.isShowing == true) return
        val state = api.accountState()
        if (!state.optBoolean("configured")) { showBillingNotice(); return }
        val options = state.getJSONObject("companion")
        val values = booleanArrayOf(options.optBoolean("whisper"), options.optBoolean("dream"), options.optBoolean("decorations"))
        val choices = arrayOf("自动碎碎念（清醒时约 ${options.optLong("whisperSec", 300) / 60} 分钟一次）",
            "睡眠梦话（睡觉时约 ${options.optLong("dreamSec", 60) / 60} 分钟一次）", "聊天配图与动作联动（会增加输入用量）")
        val dialog = AlertDialog.Builder(this).setTitle("陪伴设置 · API 会计费")
            .setMultiChoiceItems(choices, values) { _, which, checked -> values[which] = checked }
            .setNegativeButton("取消", null)
            .setPositiveButton("确认保存") { _, _ -> api.companionOptions(values[0], values[1], values[2], true) }.create()
        val footer = TextView(this).apply {
            text = "勾选自动项后，桌宠运行且亮屏时会定时调用 DeepSeek，持续产生费用。全部宠物合计每小时最多 12 次自动请求；这不是金额上限。锁屏、拖动、聊天时暂停，失败后暂停自动调用，不补发漏过的请求。\n\n配图使用内置原版表情包，不是 AI 生成图片。取消勾选即可关闭；保存后解除之前的错误暂停。"
            textSize = 12f; setTextColor(Color.rgb(121, 105, 94)); setPadding(dp(24), dp(8), dp(24), dp(12))
        }
        dialog.setView(footer)
        companionDialog = dialog
        dialog.setOnDismissListener { companionDialog = null }
        showWarmDialog(dialog)
    }
    private fun openPricingPage() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api-docs.deepseek.com/quick_start/pricing/"))) }
    }
    private fun publishAccount(value: JSONObject) {
        if (::browser.isInitialized && !isDestroyed)
            browser.evaluateJavascript("window.PetUI?.updateApi($value)", null)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun beginLaunch() {
        if (!Settings.canDrawOverlays(this)) {
            browser.evaluateJavascript("window.PetUI?.showPermission()", null)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notificationPrompted", false)) {
            preferences.edit().putBoolean("notificationPrompted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
            return
        }
        startPet()
    }

    private fun startPet() {
        returnHomeWhenReady = true
        PetRuntime.update("starting", "小家伙正在出门…")
        try { startForegroundService(Intent(this, PetOverlayService::class.java)) }
        catch (error: RuntimeException) {
            returnHomeWhenReady = false
            PetRuntime.update("error", "这次没有成功出来", error.localizedMessage.orEmpty())
        }
    }

    private fun openHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun publishState() {
        if (!::browser.isInitialized || isDestroyed) return
        val state = PetRuntime.state
        val size = preferences.getInt("size", 200)
        val count = preferences.getInt("count", 1)
        val data = JSONObject().put("phase", state.phase).put("message", state.message)
            .put("detail", state.detail).put("overlayAllowed", Settings.canDrawOverlays(this))
            .put("size", size).put("count", count)
            .put("settingsPending", state.phase == "running" &&
                (size != PetRuntime.activeSize || count != PetRuntime.activeCount))
        browser.evaluateJavascript("window.PetUI?.update($data)", null)
    }

    override fun onStart() { super.onStart(); PetRuntime.observe(runtimeObserver); api.observeAccount(accountObserver) }
    override fun onStop() { PetRuntime.remove(runtimeObserver); api.removeAccount(accountObserver); super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
        if (intent.getBooleanExtra("connectApi", false)) showBillingNotice()
    }
    private fun route(intent: Intent?) {
        val page = intent?.getStringExtra("openPage") ?: return
        val pet = intent.getStringExtra("chatPetId") ?: "android-0"
        intent.removeExtra("openPage")
        routePage(page, pet)
    }
    private fun routePage(page: String, pet: String) {
        when (page) {
            "pet", "settings" -> browser.evaluateJavascript("window.PetUI?.navigate(${JSONObject.quote(page)})", null)
            "chat" -> startActivity(Intent(this, ChatActivity::class.java).putExtra("petId", pet))
            "todo" -> startActivity(Intent(this, TodoActivity::class.java))
        }
    }
    override fun onResume() {
        super.onResume()
        publishState()
        if (pendingPermissionStart) {
            pendingPermissionStart = false
            if (Settings.canDrawOverlays(this)) beginLaunch()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingStart", pendingPermissionStart)
        outState.putBoolean("returnHome", returnHomeWhenReady)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 41 && Settings.canDrawOverlays(this)) startPet()
    }
    private fun handleBack() {
        browser.evaluateJavascript("window.PetUI?.back() === true") { consumed ->
            if (consumed != "true") moveTaskToBack(true)
        }
    }
    override fun onDestroy() {
        PetRuntime.remove(runtimeObserver)
        api.removeAccount(accountObserver)
        keyDialog?.dismiss()
        billingDialog?.dismiss()
        companionDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        browser.removeJavascriptInterface("PetControl")
        (browser.parent as? ViewGroup)?.removeView(browser)
        browser.destroy()
        super.onDestroy()
    }

    private fun controlResource(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty()
        if (uri.scheme != "https" || uri.host != PetScene.ASSET_HOST ||
            !path.startsWith("/control/") || path.split('/').any { it == ".." || it == "." })
            return missingResource()
        val asset = if (path == "/control/pet-font.ttf") "pet/assets/fonts/上首软糖体.ttf"
            else path.removePrefix("/")
        val mime = when (asset.substringAfterLast('.')) {
            "html" -> "text/html"; "css" -> "text/css"; "js" -> "text/javascript"
            "png" -> "image/png"; "ttf" -> "font/ttf"; else -> "application/octet-stream"
        }
        return try { WebResourceResponse(mime, "UTF-8", assets.open(asset)) }
        catch (_: java.io.IOException) { missingResource() }
    }
    private fun missingResource() = WebResourceResponse("text/plain", "UTF-8", 404, "Missing",
        emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
