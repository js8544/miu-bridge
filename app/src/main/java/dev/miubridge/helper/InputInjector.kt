package dev.miubridge.helper

import dev.miubridge.phone.GesturePath
import kotlin.random.Random
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/** Injects touch and key events into one display, as the shell user (which holds INJECT_EVENTS). */
internal class InputInjector {
    private val manager: Any
    private val inject: Method
    private val setDisplayId: Method = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    init {
        manager = try {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (e: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
        }
        inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    /** A tap is held for a short, varying moment, as a finger does. */
    fun tap(display: Int, x: Int, y: Int) = press(display, x, y, 45 + Random.nextLong(70))

    fun longPress(display: Int, x: Int, y: Int, ms: Long) = press(display, x, y, ms.coerceIn(400, 5_000))

    /** Follows a slightly bowed, eased path with uneven timing ([GesturePath]) rather than a straight line. */
    fun swipe(display: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        val down = SystemClock.uptimeMillis()
        touch(display, down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        val path = GesturePath.swipe(x1, y1, x2, y2, ms.coerceIn(50, 5_000))
        for (sample in path) {
            Thread.sleep(sample.delayMs)
            touch(display, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, sample.x, sample.y)
        }
        touch(display, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
    }

    fun key(display: Int, code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            send(event, display)
        }
    }

    private fun press(display: Int, x: Int, y: Int, holdMs: Long) {
        val down = SystemClock.uptimeMillis()
        touch(display, down, down, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        Thread.sleep(holdMs)
        touch(display, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
    }

    private fun touch(display: Int, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val event = MotionEvent.obtain(
            downTime, time, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try { send(event, display) } finally { event.recycle() }
    }

    private fun send(event: InputEvent, display: Int) {
        setDisplayId.invoke(event, display)
        val accepted = inject.invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
        if (!accepted) throw HelperFailure("input injection was refused")
    }

    private companion object {
        const val INJECT_MODE_ASYNC = 0
    }
}
