package io.github.mangi.eta.hook.hyperos

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HyperOsLongPressGestureTest {
    @Test
    fun `静止长按仅触发一次并取消原始手势`() = withView { view ->
        var calls = 0
        val gesture = HyperOsLongPressGesture(view, { true }) { calls++; true }
        touch(gesture, MotionEvent.ACTION_DOWN)
        waitForLongPress()
        assertEquals(1, calls)
        assertTrue(touch(gesture, MotionEvent.ACTION_UP))
        waitForLongPress()
        assertEquals(1, calls)
    }

    @Test
    fun `滑动抬手取消和多指中断不会触发`() = withView { view ->
        var calls = 0
        val gesture = HyperOsLongPressGesture(view, { true }) { calls++; true }
        for (action in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            touch(gesture, MotionEvent.ACTION_DOWN)
            touch(gesture, action, 1000f)
            waitForLongPress()
            assertFalse(gesture.pending)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `等待期间关闭开关或移除视图会取消`() = withView { view ->
        var enabled = true
        var calls = 0
        val gesture = HyperOsLongPressGesture(view, { enabled }) { calls++; true }
        touch(gesture, MotionEvent.ACTION_DOWN)
        enabled = false
        waitForLongPress()
        assertEquals(0, calls)
        enabled = true
        touch(gesture, MotionEvent.ACTION_DOWN)
        (view.parent as FrameLayout).removeView(view)
        waitForLongPress()
        assertEquals(0, calls)
        assertFalse(gesture.pending)
    }

    @Test
    fun `触发失败保留原始手势且不同视图不串扰`() = withView { first ->
        val second = View(first.context)
        (first.parent as FrameLayout).addView(second)
        var calls = 0
        val firstGesture = HyperOsLongPressGesture(first, { true }) { false }
        val secondGesture = HyperOsLongPressGesture(second, { true }) { calls++; true }
        touch(firstGesture, MotionEvent.ACTION_DOWN)
        touch(secondGesture, MotionEvent.ACTION_DOWN)
        touch(secondGesture, MotionEvent.ACTION_CANCEL)
        waitForLongPress()
        assertFalse(touch(firstGesture, MotionEvent.ACTION_UP))
        assertEquals(0, calls)
    }

    private fun touch(gesture: HyperOsLongPressGesture, action: Int, x: Float = 0f): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, 0f, 0)
        return try { gesture.onTouch(event) } finally { event.recycle() }
    }

    private fun waitForLongPress() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 1))
    }

    private fun withView(block: (View) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val parent = FrameLayout(controller.get())
            val view = View(controller.get())
            parent.addView(view)
            controller.get().setContentView(parent)
            assertTrue(view.isAttachedToWindow)
            block(view)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
