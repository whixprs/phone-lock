package com.whixprs.phonelock

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Draws solid black, non-touchable boxes over recent-apps cards using
 * accessibility overlay windows (no "draw over other apps" permission needed).
 */
class RecentsMasker(private val service: AccessibilityService) {

    data class Mask(val bounds: Rect, val label: String)

    private val wm = service.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<TextView>()
    private var current: List<Mask> = emptyList()
    private val density = service.resources.displayMetrics.density

    fun show(masks: List<Mask>) {
        if (masks == current) return
        current = masks

        while (views.size > masks.size) {
            val v = views.removeAt(views.lastIndex)
            runCatching { wm.removeViewImmediate(v) }
        }
        masks.forEachIndexed { i, mask ->
            val params = paramsFor(mask.bounds)
            if (i < views.size) {
                views[i].text = mask.label
                runCatching { wm.updateViewLayout(views[i], params) }
            } else {
                val v = makeView(mask.label)
                if (runCatching { wm.addView(v, params) }.isSuccess) views += v
            }
        }
    }

    fun clear() = show(emptyList())

    private fun makeView(label: String) = TextView(service).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(service, R.color.masked_text))
        textSize = 14f
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(service, R.color.black))
            cornerRadius = 20 * density
        }
    }

    private fun paramsFor(r: Rect) = WindowManager.LayoutParams(
        r.width(),
        r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
