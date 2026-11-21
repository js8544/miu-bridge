package dev.miubridge.helper

import android.os.Handler
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** A failure whose message is safe to send back to the app. */
class HelperFailure(message: String) : Exception(message)

/** Executes the app's requests inside the shell-user helper. One request at a time. */
internal class HelperHost(main: Handler, private val apk: String) {
    private val screen = VirtualScreen(main)
    private val input by lazy { InputInjector() }
    private val tasks by lazy { Tasks() }

    @Synchronized
    fun handle(request: JSONObject): Pair<JSONObject, ByteArray?> {
        val op = request.optString("op")
        return try {
            dispatch(op, request)
        } catch (e: HelperFailure) {
            error(e.message ?: "failed") to null
        } catch (e: Throwable) {
            Log.w(HelperMain.TAG, "op $op failed", e)
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            error("$op failed: ${cause.javaClass.simpleName}") to null
        }
    }

    fun shutdown() = screen.release()

    /** The task last given to the owner by "handoff", or -1. */
    private var handedOff = -1

    private fun dispatch(op: String, r: JSONObject): Pair<JSONObject, ByteArray?> = when (op) {
        "hello" -> ok().put("apk", apk).put("pid", android.os.Process.myPid()).put("display", screen.displayId) to null
        "display" -> {
            val id = screen.ensure(r.getInt("width"), r.getInt("height"), r.getInt("dpi"))
            ok().put("display", id).put("trusted", screen.trusted).put("width", screen.width).put("height", screen.height).put("dpi", screen.dpi) to null
        }
        "wake" -> ok().put("woke", wake(display())) to null
        "screenshot" -> {
            val shot = screen.screenshot(r.optInt("maxWidth", 720), r.optInt("quality", 70))
            ok().put("bin", true).put("width", shot.width).put("height", shot.height) to shot.jpeg
        }
        "tap" -> { input.tap(display(), r.getInt("x"), r.getInt("y")); ok() to null }
        "long_press" -> { input.longPress(display(), r.getInt("x"), r.getInt("y"), r.optLong("ms", 800)); ok() to null }
        "swipe" -> {
            input.swipe(display(), r.getInt("x1"), r.getInt("y1"), r.getInt("x2"), r.getInt("y2"), r.optLong("ms", 300))
            ok() to null
        }
        "key" -> { input.key(display(), r.getInt("code")); ok() to null }
        "paste" -> {
            ShellClipboard.set(r.getString("text"))
            input.key(display(), android.view.KeyEvent.KEYCODE_PASTE)
            ok() to null
        }
        "launch" -> start(r.optString("package").ifEmpty { null }, stringList(r.getJSONArray("intent"))) to null
        "handoff" -> {
            val top = tasks.list().firstOrNull { it.displayId == display() } ?: throw HelperFailure("nothing is open on the background display")
            tasks.moveToDisplay(top.id, 0)
            handedOff = top.id
            ok().put("package", top.packages.firstOrNull()) to null
        }
        // The reverse of handoff: the very task that was given to the owner comes back as they left
        // it. Only that task: nothing else is ever pulled off the owner's screen.
        "takeback" -> {
            val task = tasks.list().firstOrNull { it.id == handedOff && it.displayId != display() }
                ?: throw HelperFailure("no handed-off app is waiting on the owner's screen")
            tasks.moveToDisplay(task.id, display())
            handedOff = -1
            ok().put("package", task.packages.firstOrNull()).put("task", task.id) to null
        }
        // The app the owner still holds from "handoff", if it is still open on their screen.
        "handed_off" -> {
            val task = tasks.list().firstOrNull { it.id == handedOff && it.displayId != display() }
            ok().put("package", task?.packages?.firstOrNull() ?: JSONObject.NULL) to null
        }
        "return_task" -> {
            tasks.moveToDisplay(r.getInt("task"), 0)
            handedOff = r.getInt("task")
            ok() to null
        }
        "clear" -> {
            val d = display()
            val onDisplay = tasks.list().filter { it.displayId == d }
            onDisplay.forEach { tasks.remove(it.id) }
            ok().put("removed", onDisplay.size) to null
        }
        "exec" -> {
            val res = Exec.run(stringList(r.getJSONArray("argv")), r.optLong("timeoutMs", 10_000))
            ok().put("code", res.code).put("out", res.out) to null
        }
        "quit" -> ok() to null
        else -> throw HelperFailure("unknown op $op")
    }

    /**
     * Starts an intent on the background display. When the package already has a task on another
     * display (the owner's), we ask for a new task so that task is not pulled off the owner's screen.
     */
    private fun start(pkg: String?, intentArgs: List<String>): JSONObject {
        val d = display()
        val before = tasks.list()
        val onOurs = pkg != null && before.any { it.displayId == d && pkg in it.packages }
        val elsewhere = pkg != null && before.any { it.displayId != d && pkg in it.packages }
        var flags = FLAG_NEW_TASK
        if (elsewhere && !onOurs) flags = flags or FLAG_MULTIPLE_TASK
        val res = Exec.run(listOf("am", "start", "--display", d.toString(), "-f", flags.toString()) + intentArgs, 15_000)
        val failure = res.out.lineSequence().firstOrNull { it.startsWith("Error") || it.contains("Exception") }
        if (res.code != 0 || failure != null) throw HelperFailure("am start failed: ${(failure ?: res.out).take(200).trim()}")
        val after = tasks.list()
        val top = after.firstOrNull { it.displayId == d }
        val strayed = pkg != null && top?.packages?.contains(pkg) != true &&
            after.firstOrNull { it.displayId == 0 }?.packages?.contains(pkg) == true
        return ok().put("package", top?.packages?.firstOrNull()).put("onMainDisplay", strayed)
    }

    /**
     * Wakes the background display's own power group, leaving the phone's screen as it is. Android puts
     * that group to sleep along with the phone; asleep, it draws nothing: screenshots are black and a
     * launched app never shows. False where the system has no per-display wake (before Android 14).
     */
    private fun wake(displayId: Int): Boolean {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
        val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        val wakeUp = runCatching {
            power.javaClass.getMethod("wakeUpWithDisplayId", Long::class.java, Int::class.java, String::class.java, String::class.java, Int::class.java)
        }.getOrNull() ?: return false
        wakeUp.invoke(power, android.os.SystemClock.uptimeMillis(), WAKE_REASON_APPLICATION, "miubridge", "com.android.shell", displayId)
        return true
    }

    private fun display(): Int = screen.displayId.takeIf { it > 0 } ?: throw HelperFailure("background display not created")

    private fun ok() = JSONObject().put("ok", true)
    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)
    private fun stringList(a: JSONArray) = List(a.length()) { a.getString(it) }

    private companion object {
        const val FLAG_NEW_TASK = 0x10000000
        const val FLAG_MULTIPLE_TASK = 0x08000000
        const val WAKE_REASON_APPLICATION = 2
    }
}
