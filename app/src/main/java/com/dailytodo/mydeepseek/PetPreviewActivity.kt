package com.dailytodo.mydeepseek

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A real renderer preview that does not require overlay permission. */
class PetPreviewActivity : Activity() {
    private var scene: PetScene? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(236, 243, 255))
        }
        layout.addView(TextView(this).apply {
            text = "离线预览\n点击 · 拖拽甩抛 · 长按动作菜单\n按返回退出预览"
            textSize = 16f
            setTextColor(Color.rgb(36, 52, 84))
            setPadding(24, 20, 24, 20)
        })
        val canvas = FrameLayout(this)
        layout.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        layout.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        canvas.post {
            if (!isFinishing && !isDestroyed) scene = PetScene(this, 0, canvas)
        }
    }
    override fun onPause() { scene?.paused(true); super.onPause() }
    override fun onResume() { super.onResume(); scene?.paused(false) }
    override fun onDestroy() { scene?.destroy(); scene = null; super.onDestroy() }
}
