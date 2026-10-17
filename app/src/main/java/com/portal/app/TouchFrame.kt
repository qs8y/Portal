package com.portal.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class TouchFrame @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    var onTouch: (() -> Unit)? = null
    var onVSwipe: ((Boolean, Float) -> Unit)? = null // (leftSide, upwardFractionOfHeight)
    private var sx = 0f
    private var sy = 0f
    private var ly = 0f
    private var swiping = false
    private val slop = ViewConfiguration.get(c).scaledTouchSlop * 2

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = ev.x; sy = ev.y; ly = ev.y; swiping = false
                onTouch?.invoke()
            }
            MotionEvent.ACTION_MOVE -> if (onVSwipe != null && height > 0) {
                if (!swiping && abs(ev.y - sy) > slop && abs(ev.y - sy) > abs(ev.x - sx) * 1.5f) {
                    swiping = true
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (swiping) {
                    onVSwipe?.invoke(sx < width / 2f, (ly - ev.y) / height)
                    ly = ev.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) { swiping = false; return true }
        }
        return super.dispatchTouchEvent(ev)
    }
}
