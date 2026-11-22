package dev.miubridge.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplayConfig
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The background virtual display, owned by the shell user and rendered into an [ImageReader] so we
 * can take screenshots. Display calls run on the main looper; frames arrive on their own thread.
 */
internal class VirtualScreen(private val main: Handler) {
    class Shot(val jpeg: ByteArray, val width: Int, val height: Int)

    private val frames = Handler(HandlerThread("pb-frames").apply { start() }.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null // touched only on the frames thread

    @Volatile var displayId = -1
        private set

    @Volatile var trusted = false
        private set

    /** The size the display was made with; a later [ensure] with other numbers does not change it. */
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var dpi = 0
        private set

    /** True when the display may take input focus and hold the keyboard; see [flagCandidates]. */
    @Volatile var takesKeyboard = false
        private set

    fun ensure(width: Int, height: Int, dpi: Int, takesKeyboard: Boolean): Int = main.await {
        if (display == null) make(width, height, dpi, takesKeyboard)
        displayId
    }

    /**
     * Makes the display again with the other answer to [takesKeyboard], which Android fixes when a
     * display is created. [move] is given the new display's id while the old one still exists, to
     * carry over what is open on it.
     */
    fun remake(takesKeyboard: Boolean, move: (Int) -> Unit): Int = main.await {
        val oldDisplay = display ?: throw HelperFailure("background display not created")
        val oldReader = reader
        make(width, height, dpi, takesKeyboard)
        runCatching { move(displayId) }.onFailure { Log.w(HelperMain.TAG, "could not carry apps over to the new display", it) }
        oldDisplay.release()
        frames.await { if (latestFrom === oldReader) latest = null }
        oldReader?.close()
        off = false
        displayId
    }

    private var latestFrom: ImageReader? = null // touched only on the frames thread

    private fun make(width: Int, height: Int, dpi: Int, takesKeyboard: Boolean) {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(::onFrame, frames)
        var created: VirtualDisplay? = null
        var refusal: Throwable? = null
        for ((flags, isTrusted) in flagCandidates(takesKeyboard)) {
            try {
                created = createDisplay(VirtualDisplayConfig.Builder("miubridge", width, height, dpi).setSurface(r.surface).setFlags(flags).build())
                if (created != null) {
                    trusted = isTrusted
                    break
                }
            } catch (e: SecurityException) {
                Log.i(HelperMain.TAG, "display flags 0x${flags.toString(16)} refused, retrying with fewer")
                refusal = e
            } catch (e: IllegalArgumentException) {
                // A flag combination the platform rejects outright; the plainer set may still work.
                Log.i(HelperMain.TAG, "display flags 0x${flags.toString(16)} rejected, retrying with fewer")
                refusal = e
            }
        }
        if (created == null) {
            r.close()
            throw HelperFailure("could not create the virtual display (${refusal?.javaClass?.simpleName ?: "null"})")
        }
        reader = r
        display = created
        displayId = created.display.displayId
        this.width = width
        this.height = height
        this.dpi = dpi
        this.takesKeyboard = takesKeyboard
        if (trusted) setImePolicy(displayId, if (takesKeyboard) DISPLAY_IME_POLICY_LOCAL else DISPLAY_IME_POLICY_HIDE)
        Log.i(HelperMain.TAG, "virtual display $displayId ${width}x$height@$dpi trusted=$trusted keyboard=$takesKeyboard")
    }

    fun screenshot(maxWidth: Int, quality: Int): Shot = frames.await {
        val image = latest ?: throw HelperFailure("the background display has not drawn anything yet")
        encode(image, maxWidth, quality)
    }

    /**
     * Turns the display off by taking its surface away, or back on by returning it. Off, the app on
     * it stops being in front, as when the owner leaves an app.
     */
    fun turn(on: Boolean): Boolean = main.await {
        val d = display ?: return@await false
        if (on == !off) return@await true
        d.surface = if (on) reader?.surface else null
        off = !on
        true
    }

    private var off = false // touched only on the main looper

    fun release() {
        runCatching {
            main.await {
                display?.release()
                display = null
                off = false
                reader?.close()
                reader = null
                displayId = -1
            }
        }
    }

    private fun onFrame(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        latest?.close()
        latest = image
        latestFrom = r
    }

    private fun encode(image: Image, maxWidth: Int, quality: Int): Shot {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val src = plane.buffer.duplicate()
        val rowBytes = w * 4
        val tight = ByteBuffer.allocateDirect(rowBytes * h)
        for (row in 0 until h) {
            val start = row * plane.rowStride
            src.limit(start + rowBytes).position(start)
            tight.put(src)
            src.limit(src.capacity())
        }
        tight.rewind()
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(tight) }
        val scaled = if (w > maxWidth) {
            Bitmap.createScaledBitmap(full, maxWidth, (h.toLong() * maxWidth / w).toInt(), true).also { full.recycle() }
        } else full
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), out)
        val shot = Shot(out.toByteArray(), scaled.width, scaled.height)
        scaled.recycle()
        return shot
    }

    /**
     * Calls DisplayManagerGlobal.createVirtualDisplay directly: the DisplayManager wrapper needs more
     * of a real Context on newer releases. The overload differs per release, so we pick it by
     * parameter types and pass null for the optional projection, callback, handler/executor and window context.
     */
    private fun createDisplay(config: VirtualDisplayConfig): VirtualDisplay? {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val method = global.javaClass.methods.firstOrNull { m ->
            m.name == "createVirtualDisplay" && m.parameterTypes.size >= 3 &&
                m.parameterTypes[0] == Context::class.java && m.parameterTypes[2] == VirtualDisplayConfig::class.java
        } ?: throw HelperFailure("no usable createVirtualDisplay on this Android version")
        val args = method.parameterTypes.mapIndexed { i, type ->
            when {
                i == 0 -> ShellContext()
                type == VirtualDisplayConfig::class.java -> config
                else -> null
            }
        }
        return try {
            method.invoke(global, *args.toTypedArray()) as VirtualDisplay?
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * Candidate flag sets, richest first; trusted-only flags are dropped if the platform refuses them.
     *
     * Android gives the keyboard, and with it the way to write into a field as a keyboard does, only
     * to the display that has top focus. While the owner uses the phone that must stay their screen,
     * or their keyboard would close whenever the agent taps: so the display is barred from taking it.
     * While the phone is locked nobody is typing, and [takesKeyboard] lifts the bar.
     */
    private fun flagCandidates(takesKeyboard: Boolean): List<Pair<Int, Boolean>> {
        val sdk = Build.VERSION.SDK_INT
        var base = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        if (sdk >= 34) base = base or OWN_FOCUS
        if (sdk >= 34 && !takesKeyboard) base = base or STEAL_TOP_FOCUS_DISABLED
        // Not CAN_SHOW_WITH_INSECURE_KEYGUARD: the platform refuses it on a public display
        // ("Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE"), so no display is created at all.
        var trustedFlags = TRUSTED
        if (sdk >= 31) trustedFlags = trustedFlags or OWN_DISPLAY_GROUP
        if (sdk >= 33) trustedFlags = trustedFlags or ALWAYS_UNLOCKED
        return listOf(base or trustedFlags to true, base to false)
    }

    /** Where the soft keyboard goes for fields on this display: nowhere, or onto the display itself. */
    private fun setImePolicy(id: Int, policy: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, id, policy)
        } catch (e: Throwable) {
            Log.w(HelperMain.TAG, "could not set IME policy: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        // android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden).
        const val PUBLIC = 1 shl 0
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val OWN_FOCUS = 1 shl 14
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
        const val DISPLAY_IME_POLICY_LOCAL = 0
        const val DISPLAY_IME_POLICY_HIDE = 2
    }
}

/** Runs [block] on this handler's thread and returns its result (or rethrows its failure). */
internal fun <T> Handler.await(timeoutMs: Long = 10_000, block: () -> T): T {
    if (Looper.myLooper() == looper) return block()
    val done = CountDownLatch(1)
    var result: Result<T>? = null
    post {
        result = runCatching(block)
        done.countDown()
    }
    if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw HelperFailure("helper thread timed out")
    return result!!.getOrThrow()
}
