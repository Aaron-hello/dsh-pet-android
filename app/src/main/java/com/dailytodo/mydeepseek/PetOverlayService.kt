package com.dailytodo.mydeepseek

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONObject

/** The foreground service owns user-started pet windows, never the DSH backend. */
class PetOverlayService : Service() {
    private val scenes = mutableListOf<PetScene>()
    private val states = JSONObject()
    private val handler = Handler(Looper.getMainLooper())
    private var broadcastPending = false
    private val broadcastFlight = Runnable {
        broadcastPending = false
        if (scenes.size > 1) scenes.forEach { it.flightStates(states) }
    }
    private var receiverRegistered = false
    private val api by lazy { PetApi.get(this) }
    private var companion: PetCompanion? = null
    private val replyObserver: (String, String) -> Unit = { id, _ ->
        // Chat content stays inside the app; an optional original action may still play.
        scenes.firstOrNull { it.petId == id }?.apiReply("", api.lastReply(id))
    }
    private val todoReminderObserver: (String) -> Unit = { text -> scenes.firstOrNull()?.todoReminder(text) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val paused = intent.action == Intent.ACTION_SCREEN_OFF ||
                getSystemService(KeyguardManager::class.java).isKeyguardLocked
            scenes.forEach { it.paused(paused) }
            companion?.screen(!paused)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); api.observeReplies(replyObserver); PetTodos.get(this).observeReminders(todoReminderObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) {
            PetRuntime.update("error", "还需要允许悬浮窗", "请允许显示在其他应用上层，再放它出来。")
            stopSelf()
            return START_NOT_STICKY
        }
        PetRuntime.update("starting", "小家伙正在出门…")
        PetRuntime.activeSize = getSharedPreferences("pet", MODE_PRIVATE).getInt("size", 200)
        PetRuntime.activeCount = getSharedPreferences("pet", MODE_PRIVATE).getInt("count", 1).coerceIn(1, 3)
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_pet_notification)
                .setContentTitle("dsh-pet 正在陪你")
                .setContentText("长按宠物打开菜单 · 点此设置")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "停止桌宠", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            companion?.close()
            companion = PetCompanion(api) { id, reply -> scenes.firstOrNull { it.petId == id }?.companionReply(reply) }
            scenes.forEach { it.destroy() }; scenes.clear()
            val names = states.keys().asSequence().toList()
            names.forEach { states.remove(it) }
            val readyPets = mutableSetOf<String>()
            repeat(PetRuntime.activeCount) { i ->
                // A Service is not a visual Context. API 30+ requires a window
                // Context associated with the display for overlay views/metrics.
                scenes.add(PetScene(overlayContext(), i, onFlight = { scene, state ->
                    states.put(scene.petId, state)
                    if (!broadcastPending) {
                        broadcastPending = true
                        handler.post(broadcastFlight)
                    }
                }, onHit = { id, vx, vy -> scenes.firstOrNull { it.petId == id }?.hit(vx, vy) },
                    onReady = { scene ->
                        companion?.ready(scene.petId)
                        if (PetRuntime.state.phase == "starting") {
                            readyPets.add(scene.petId)
                            if (readyPets.size == PetRuntime.activeCount)
                                PetRuntime.update("running", "它正在陪着你")
                        }
                    },
                    onFailure = { reason -> fail(reason) }, onChatRequested = { scene -> openChat(scene) },
                    trackCollisions = PetRuntime.activeCount > 1,
                    onInteraction = { id, sleeping, busy -> companion?.interaction(id, sleeping, busy) },
                    onWhisperRequested = { scene, image ->
                        api.generateLine(scene.petId, false, image) { result, error ->
                            if (result != null) scene.companionReply(result)
                            else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        }
                    }))
            }
        } catch (error: RuntimeException) {
            Log.e("DSHPet", "Overlay startup failed", error)
            fail(error.localizedMessage ?: error.javaClass.simpleName)
            return START_NOT_STICKY
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, filter)
            receiverRegistered = true
        }
        return START_NOT_STICKY
    }

    private fun fail(reason: String) {
        PetRuntime.update("error", "这次没有成功出来", reason)
        stopSelf()
    }
    private fun overlayContext(): Context {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("No default display")
        val base = createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= 30)
            base.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else base
    }
    private fun openChat(scene: PetScene) {
        try {
            AppNavigation.open(this, "chat", scene.petId)
        } catch (error: RuntimeException) {
            android.util.Log.e("DSHPet", "Chat window could not open", error)
            Toast.makeText(this, "聊天页面没有打开，请从应用里进入。", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scenes.forEach { it.updateGeometry() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        companion?.close(); companion = null
        api.removeReplies(replyObserver)
        PetTodos.get(this).removeReminders(todoReminderObserver)
        scenes.forEach { it.destroy() }; scenes.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (PetRuntime.state.phase != "error") PetRuntime.update("idle")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "offline-pet"
        private const val STOP = "com.dailytodo.mydeepseek.STOP_PET"
    }
}
