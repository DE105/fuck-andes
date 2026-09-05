package io.github.mangi.eta.hook.hyperos

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.lang.ref.WeakReference
import kotlin.math.abs

/** 每个导航视图独占一次手势；延迟任务不持有 Hook Chain，也不延长视图生命周期。 */
internal class HyperOsLongPressGesture(
    view: View,
    private val enabled: () -> Boolean,
    private val trigger: (View) -> Boolean,
) : View.OnAttachStateChangeListener {
    private val owner = WeakReference(view)
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    var pending = false
        private set
    private var triggered = false

    private val longPress = Runnable {
        val current = owner.get()
        if (pending && current != null && current.isAttachedToWindow && enabled()) {
            pending = false
            triggered = trigger(current)
        } else {
            cancel()
        }
    }

    init {
        view.addOnAttachStateChangeListener(this)
    }

    /** 返回 true 时将本次原始事件改为 CANCEL，结束桌面的原生手势流。 */
    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            triggered = false
            if (enabled() && event.pointerCount == 1) {
                startX = event.rawX
                startY = event.rawY
                pending = true
                if (owner.get()?.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) != true) cancel()
            }
        } else if (triggered) {
            triggered = false
            cancel()
            return true
        } else if (!enabled() || event.pointerCount != 1 || event.actionMasked != MotionEvent.ACTION_MOVE ||
            abs(event.rawX - startX) > slop || abs(event.rawY - startY) > slop
        ) {
            cancel()
        }
        return false
    }

    fun cancel() {
        pending = false
        owner.get()?.removeCallbacks(longPress)
    }

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) {
        cancel()
        triggered = false
    }
}
