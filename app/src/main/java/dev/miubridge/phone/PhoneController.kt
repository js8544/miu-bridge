package dev.miubridge.phone

import dev.miubridge.core.CallTiming
import dev.miubridge.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.miubridge.core.CommandHandler
import dev.miubridge.core.CommandResult
import dev.miubridge.core.CommandSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

data class PhoneState(
    val shellReady: Boolean = false,
    val a11yReady: Boolean = false,
    val displayId: Int = -1,
    val message: String = "",
    /** When an agent last acted on the background display (elapsedRealtime), 0 if never. */
    val lastUsedAt: Long = 0,
)

/**
 * Drives apps on a background virtual display: starts the shell-user helper through local adb,
 * keeps one authenticated connection to it, and reads the display through [ScreenReaderService].
 */
class PhoneController(private val context: Context) : CommandHandler {
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupLock = Mutex()
    private val sessionFile = File(context.filesDir, "phone_helper.json")

    private val screenKeeper = ScreenKeeper(context)
    @Volatile private var helper: HelperClient? = null
    @Volatile private var screen: Screen? = null
    @Volatile private var lastSnapshot: UiSnapshot? = null

    private class Screen(val id: Int, val width: Int, val height: Int, val dpi: Int)
    private class BadRequest(message: String, val code: String = "bad_request") : Exception(message)

    init {
        scope.launch {
            ScreenReaderService.current.collect { svc -> _state.update { it.copy(a11yReady = svc != null) } }
        }
    }

    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)

    /** The owner's choice to keep the screen dimly on (never auto-lock) while the service runs. */
    var standby: Boolean
        get() = prefs.getBoolean("standby", false)
        set(value) { prefs.edit().putBoolean("standby", value).apply() }

    /** Applies [standby] while the service runs; always off when it stops. */
    fun applyStandby(serviceRunning: Boolean) = screenKeeper.setStandby(serviceRunning && standby)

    /** Connects to (or starts) the shell helper and the background display. Safe to call repeatedly. */
    suspend fun ensureShell(): Boolean = setupLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                setUp()
                true
            } catch (e: ShellUnavailableException) {
                fail(context.getString(R.string.phone_err_no_shell))
            } catch (e: ShellNotAuthorizedException) {
                fail(context.getString(R.string.phone_err_not_authorized))
            } catch (e: HelperError) {
                fail(context.getString(R.string.phone_err_helper, e.message.orEmpty()))
            } catch (e: Exception) {
                Log.w(TAG, "phone setup failed", e)
                fail(context.getString(R.string.phone_err_start, e.javaClass.simpleName))
            }
        }
    }

    /** Drops the helper (its virtual display and the apps on it close). [ensureShell] starts a new one. */
    fun close() {
        val h = helper
        helper = null
        screen = null
        lastSnapshot = null
        ScreenReaderService.targetDisplay = -1
        screenKeeper.release()
        _state.update { it.copy(shellReady = false, displayId = -1, message = context.getString(R.string.phone_closed)) }
        if (h != null) thread(name = "phone-close") {
            runCatching { h.call("quit", timeoutMs = 2_000) }
            h.close()
        }
    }

    override fun specs(): Map<String, CommandSpec> = PhoneSpecs.all

    override fun guideMarkdown(): String = PhoneSpecs.guide

    override suspend fun invoke(name: String, params: JSONObject): CommandResult = try {
        screenKeeper.touch()
        // Listing apps and asking for status touch nothing on the display, so they work in any state.
        val passive = name == "phone_apps" || name == "phone_status"
        if (!passive && screenKeeper.blocksBackground()) {
            throw BadRequest("the phone is locked; on this Android version the background display cannot be used behind the lock screen. Ask the owner to unlock the phone, then retry.", "phone_locked")
        }
        if (!passive && name in PhoneSpecs.all && screen == null && !ensureShell()) {
            throw BadRequest("the background display is not ready: ${state.value.message}", "display_not_ready")
        }
        // Android drops our accessibility service when the app is updated; turn it back on.
        if (!passive && ScreenReaderService.current.value == null) {
            val h = helper
            val s = screen
            if (h != null && s != null) runCatching { setupLock.withLock { ensureAccessibility(h, s.id) } }
        }
        if (!passive && name in PhoneSpecs.all) _state.update { it.copy(lastUsedAt = SystemClock.elapsedRealtime()) }
        var result = if (name in PACED) paced(name, params) { run(name, params) } else run(name, params)
        if (result is CommandResult.Ok && name in RETURNS_SCREEN) result = withPicture(result, params.optBoolean("screenshot"))
        if (restarted && result is CommandResult.Ok && !passive) {
            restarted = false
            result.payload.put("restarted", true).put(
                "notice",
                "The background display was restarted a moment ago (the app was updated or its service restarted). " +
                    "Apps that were open on it were closed by that, not by a crash. Launch what you need again.",
            )
        }
        result
    } catch (e: BadRequest) {
        CommandResult.Error(e.message ?: "bad request", e.code)
    } catch (e: HelperError) {
        CommandResult.Error(e.message ?: "phone helper failed", "helper_failed")
    } catch (e: IOException) {
        CommandResult.Error("phone helper unavailable: ${state.value.message.ifBlank { e.javaClass.simpleName }}", "display_not_ready")
    }

    /**
     * Adds a picture of the screen to a result that carries its element tree: when the caller asked
     * for one, and always when the app in front exposes no tree, since the picture is then the only
     * way to see it. Saves the second round trip a separate phone_screenshot would cost.
     */
    private suspend fun withPicture(result: CommandResult.Ok, asked: Boolean): CommandResult.Ok {
        if (!asked && lastSnapshot?.unreadable != true) return result
        val shot = runCatching { screenshot() }.getOrNull() as? CommandResult.Ok ?: return result
        for (key in listOf("image_width", "image_height", "scale")) result.payload.put(key, shot.payload.get(key))
        return CommandResult.Ok(result.payload, shot.images)
    }

    /** What state the phone is in, read without touching the display. */
    private fun status(): JSONObject {
        val s = screen
        val svc = ScreenReaderService.current.value
        val out = JSONObject()
            .put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("locked", screenKeeper.blocksBackground())
            .put("ready", s != null && helper != null)
            .put("reader", svc != null)
            .put("restarted", restarted)
        if (s != null) {
            out.put("display", JSONObject().put("id", s.id).put("width", s.width).put("height", s.height).put("dpi", s.dpi))
            out.put("package", svc?.topPackage(s.id) ?: JSONObject.NULL)
        } else out.put("message", state.value.message)
        return out
    }

    private suspend fun run(name: String, params: JSONObject): CommandResult = when (name) {
        "phone_apps" -> CommandResult.Ok(JSONObject().put("apps", apps(params.optString("query"))))
        "phone_status" -> CommandResult.Ok(status())
        "phone_launch" -> launch(params.str("package"))
        "phone_snapshot" -> CommandResult.Ok(snapshot(null))
        "phone_screenshot" -> screenshot()
        "phone_tap" -> point(params).let { (x, y) -> act("tapped $x,$y") { it.call("tap", xy(x, y)) } }
        "phone_long_press" -> point(params).let { (x, y) ->
            act("long-pressed $x,$y") { it.call("long_press", xy(x, y).put("ms", 800)) }
        }
        "phone_swipe" -> swipe(params)
        "phone_type" -> type(params.str("text", allowEmpty = true), params.optString("ref").ifBlank { null })
        "phone_key" -> key(params.str("key"))
        "phone_open_url" -> openUrl(params.str("url"))
        "phone_handoff" -> handoff()
        "phone_wait" -> waitFor(params)
        else -> CommandResult.Error("unknown command $name", "unknown_tool")
    }

    // --- pacing ---

    /** The owner's choice: "auto" paces by app and screen, "fast" never waits. */
    var pace: String
        get() = prefs.getString("pace", "auto").orEmpty()
        set(value) { prefs.edit().putString("pace", value).apply() }

    /** Called when an app shows a rate-limit or verification screen: (package, the phrase seen). */
    var onFriction: ((String, String) -> Unit)? = null

    private val pacing = Pacing()
    @Volatile private var restarted = false
    @Volatile private var currentPackage: String? = null

    /**
     * No app is named: the phone's own apps and apps without internet access have no account to
     * protect; an app that showed a rate-limit or verification screen here stays slow; the rest
     * get a human tempo.
     */
    private fun tempoFor(pkg: String?): Pacing.Tempo {
        if (pace == "fast") return Pacing.Tempo.FAST
        if (pkg == null) return Pacing.Tempo.HUMAN
        if (pkg in prefs.getStringSet("slow_apps", emptySet()).orEmpty()) return Pacing.Tempo.SLOW
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return Pacing.Tempo.HUMAN
        val online = pm.checkPermission(android.Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED
        val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        return if (system || !online) Pacing.Tempo.FAST else Pacing.Tempo.HUMAN
    }

    /** Holds [action] back to a human tempo, limits long runs of screens, and watches for friction. */
    private suspend fun paced(name: String, params: JSONObject, action: suspend () -> CommandResult): CommandResult {
        val target = if (name == "phone_launch") params.optString("package").ifBlank { null } else currentPackage
        val tempo = tempoFor(target)
        if (name in CHANGES_SCREEN) pacing.limitFor(target, tempo, SystemClock.elapsedRealtime())?.let { ms ->
            return CommandResult.Error(
                "You have gone through many screens of this app in a row. To keep the owner's account safe, stop here: " +
                    "answer with what you have read, or ask the owner whether to continue. Moving to another screen in this " +
                    "app is possible again in about ${(ms / 60_000) + 1} min; scrolling and typing still work.",
                "paced",
            )
        }
        delay(pacing.waitBefore(SystemClock.elapsedRealtime()))

        val before = lastSnapshot?.text
        val result = action()
        if (result !is CommandResult.Ok) return result
        val pkg = result.payload.optString("package").takeIf { it.isNotEmpty() && it != "null" } ?: target
        currentPackage = pkg
        val after = result.payload.optString("snapshot")
        val readable = after.lines().size > 3
        // Without a readable tree (some apps hide it) go by the kind of action.
        val (newScreen, newChars) = when {
            name == "phone_swipe" || name == "phone_type" -> false to 0
            readable -> Pacing.change(before, after)
            else -> (name in CHANGES_SCREEN) to 0
        }
        pacing.noteAction(pkg, tempoFor(pkg), newScreen, newChars, SystemClock.elapsedRealtime())

        val phrase = if (pkg != null && pace != "fast") Pacing.friction(after) else null
        if (phrase != null && pkg != null) {
            val slow = prefs.getStringSet("slow_apps", emptySet()).orEmpty()
            if (pkg !in slow) {
                prefs.edit().putStringSet("slow_apps", slow + pkg).apply()
                runCatching { onFriction?.invoke(pkg, phrase) }
            }
            result.payload.put(
                "warning",
                "This app is showing a rate-limit or verification screen (\"$phrase\"). Stop acting in it and do not retry: " +
                    "call phone_handoff so the owner can clear it, and tell them what happened. From now on the phone paces this app more slowly.",
            )
        }
        return result
    }

    // --- commands ---

    private fun apps(query: String = ""): JSONArray {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val out = JSONArray()
        launchable.map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { it.loadLabel(pm).toString() to it.packageName }
            .filter { (label, pkg) -> query.isBlank() || label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true) }
            .sortedBy { it.first }
            .forEach { (label, pkg) ->
                val app = JSONObject().put("label", label).put("package", pkg)
                if (AppPolicy.isBlocked(pkg)) app.put("blocked", true)
                out.put(app)
            }
        return out
    }

    private suspend fun launch(pkg: String): CommandResult {
        if (AppPolicy.isBlocked(pkg)) return blocked(pkg)
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return CommandResult.Error("$pkg is not installed or has no launcher activity", "not_installed")
        return startOnScreen(pkg, listOf("-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-n", component.flattenToString()), "launched $pkg")
    }

    private suspend fun openUrl(url: String): CommandResult {
        val uri = Uri.parse(url)
        if (uri.scheme.isNullOrBlank()) throw BadRequest("url needs a scheme, e.g. https://")
        val target = context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, uri), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
        if (target != null && AppPolicy.isBlocked(target)) return blocked(target)
        return startOnScreen(target, listOf("-a", Intent.ACTION_VIEW, "-d", url), "opened url")
    }

    private suspend fun startOnScreen(pkg: String?, intent: List<String>, done: String): CommandResult {
        val before = lastSnapshot
        val reply = withHelper { h ->
            h.call("launch", JSONObject().put("package", pkg ?: "").put("intent", JSONArray(intent)), 20_000)
        }
        if (reply.json.optBoolean("onMainDisplay")) {
            return CommandResult.Error("the app opened on the owner's main screen instead of the background display (it reuses its existing task); ask the owner to close it there first", "on_main_display")
        }
        return CommandResult.Ok(awaitFirstScreen(done, settled(before)))
    }

    /**
     * After a launch the window can be up before the app has drawn anything, so the first snapshot
     * would be empty and an agent would act on nothing. Waits until the screen has elements, or it is
     * clear that this app shows none (its tree is hidden), for at most [timeoutMs].
     */
    private suspend fun awaitFirstScreen(done: String, first: UiSnapshot?, timeoutMs: Long = 5_000): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var result = snapshot(done, first)
        var treelessSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val snap = lastSnapshot ?: break // no accessibility service: nothing to wait on
            if (snap.refs.size >= 2) break
            val now = SystemClock.elapsedRealtime()
            // A window that stays without elements for a while is a tree-less app, not a slow one.
            if (snap.packageName != null) {
                if (treelessSince == 0L) treelessSince = now else if (now - treelessSince > 1_500) break
            } else treelessSince = 0L
            delay(250)
            result = snapshot(done)
        }
        return result
    }

    private suspend fun screenshot(): CommandResult {
        val s = requireScreen()
        val reply = withHelper { it.call("screenshot", JSONObject().put("maxWidth", 720).put("quality", 70)) }
        val w = reply.json.getInt("width")
        val payload = JSONObject()
            .put("display", s.id)
            .put("width", s.width).put("height", s.height)
            .put("image_width", w).put("image_height", reply.json.getInt("height"))
            .put("scale", s.width.toDouble() / w)
        return CommandResult.Ok(payload, listOfNotNull(reply.bytes))
    }

    private suspend fun swipe(p: JSONObject): CommandResult {
        val s = requireScreen()
        val ms = p.optInt("duration_ms", 300).coerceIn(50, 5_000)
        val dir = p.optString("direction").lowercase()
        val (from, to) = if (dir.isNotEmpty()) {
            val cx = s.width / 2
            val cy = s.height / 2
            val dy = s.height * 3 / 10
            val dx = s.width * 3 / 10
            when (dir) {
                "up" -> (cx to cy + dy) to (cx to cy - dy)
                "down" -> (cx to cy - dy) to (cx to cy + dy)
                "left" -> (cx + dx to cy) to (cx - dx to cy)
                "right" -> (cx - dx to cy) to (cx + dx to cy)
                else -> throw BadRequest("direction must be up, down, left or right")
            }
        } else {
            if (!listOf("x1", "y1", "x2", "y2").all(p::has)) throw BadRequest("give direction, or x1, y1, x2, y2")
            checked(s, p.getInt("x1"), p.getInt("y1")) to checked(s, p.getInt("x2"), p.getInt("y2"))
        }
        val args = JSONObject().put("x1", from.first).put("y1", from.second).put("x2", to.first).put("y2", to.second).put("ms", ms)
        return act("swiped ${dir.ifEmpty { "${from.first},${from.second} → ${to.first},${to.second}" }}") {
            it.call("swipe", args, ms + 10_000L)
        }
    }

    private suspend fun type(text: String, ref: String?): CommandResult {
        val s = requireScreen()
        val svc = ScreenReaderService.current.value
        val node = if (ref != null) {
            if (svc == null) throw BadRequest("the accessibility service is not connected yet; try again shortly", "reader_unavailable")
            locate(ref).node ?: throw BadRequest("$ref has no node")
        } else {
            svc?.focusedInput(s.id)
        }
        if (node == null) {
            // Some apps (WeChat) hide their fields from accessibility: paste into whatever has focus.
            return act("pasted ${text.length} characters into the focused field; check with phone_screenshot") {
                it.call("paste", JSONObject().put("text", text))
            }
        }
        val ok = withContext(Dispatchers.IO) { ScreenReaderService.setText(node, text) }
        if (!ok) throw BadRequest("could not set text (the element is not an editable field, or the app refused)")
        settle()
        return CommandResult.Ok(snapshot("typed ${text.length} characters"))
    }

    private suspend fun key(key: String): CommandResult = when (key.lowercase()) {
        "back" -> act("pressed back") { it.call("key", JSONObject().put("code", KeyEvent.KEYCODE_BACK)) }
        "enter" -> act("pressed enter") { it.call("key", JSONObject().put("code", KeyEvent.KEYCODE_ENTER)) }
        "home" -> act("closed the apps on the background display") { it.call("clear") }
        else -> throw BadRequest("key must be back, enter or home")
    }

    private suspend fun handoff(): CommandResult {
        val reply = withHelper { it.call("handoff") }
        val pkg = reply.json.optString("package")
        _state.update { it.copy(message = context.getString(R.string.phone_handed_off, pkg.ifBlank { context.getString(R.string.phone_the_app) })) }
        lastSnapshot = null
        return CommandResult.Ok(
            JSONObject().put("moved", pkg).put("note", "The app is now on the owner's main screen; tell the owner what needs their attention."),
        )
    }

    private fun blocked(pkg: String) = CommandResult.Error(
        "$pkg is a payment or banking app and cannot be opened by agents. Ask the owner, or use phone_handoff when a payment step is reached.",
        "blocked_app",
    )

    // --- shared steps ---

    /** Runs a helper action, waits for the display to settle, and returns the new snapshot. */
    private suspend fun act(done: String, action: (HelperClient) -> Unit): CommandResult {
        requireScreen()
        val before = lastSnapshot
        CallTiming.measure("action_ms") { withHelper(action) }
        val after = CallTiming.measure("settle_ms") { settled(before) }
        return CommandResult.Ok(CallTiming.measure("read_ms") { snapshot(done, after) })
    }

    /**
     * The screen once an action has taken effect, read once and within one time limit. First the
     * display goes quiet ([settle]). Then two things can mean the result is not on the screen yet:
     * nothing at all has changed (the app accepted the action but still shows the old screen, as a
     * browser does before it starts drawing the next page), which gets a moment longer; or a
     * progress indicator that was not there before is showing, which is waited out. Null when the
     * element tree cannot be read.
     */
    private suspend fun settled(before: UiSnapshot?): UiSnapshot? {
        settle()
        val s = screen ?: return null
        val svc = ScreenReaderService.current.value ?: return null
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val snap = withContext(Dispatchers.IO) { svc.snapshot(s.id, s.width, s.height) }
            val waited = SystemClock.elapsedRealtime() - start
            val loading = snap.busy && before?.busy != true && waited < LOADING_WAIT_MS
            val unchanged = before != null && snap.text == before.text && waited < UNCHANGED_WAIT_MS
            if (!loading && !unchanged) return snap.also { stillLoading = it.busy && before?.busy != true }
            delay(250)
        }
    }

    /** Set by [settled]: the screen it returned was still showing a progress indicator when time ran out. */
    @Volatile private var stillLoading = false

    private suspend fun snapshot(done: String?, taken: UiSnapshot? = null): JSONObject {
        val s = requireScreen()
        val json = JSONObject().put("display", s.id)
        if (done != null) json.put("done", done)
        val svc = ScreenReaderService.current.value
        if (svc == null) {
            lastSnapshot = null
            return json.put("snapshot", "(accessibility service not connected; use phone_screenshot)")
        }
        val snap = taken ?: withContext(Dispatchers.IO) { svc.snapshot(s.id, s.width, s.height) }
        if (taken != null && stillLoading) json.put("loading", true)
        lastSnapshot = snap
        return json.put("package", snap.packageName ?: JSONObject.NULL).put("snapshot", snap.text)
    }

    /**
     * Waits for the action to show up on the background display (first event within 1 s), then for
     * 300 ms without events, and through window transitions that briefly leave it with no window;
     * never longer than ~3 s.
     */
    private suspend fun settle() {
        val svc = ScreenReaderService.current.value ?: return delay(800)
        val display = screen?.id ?: return
        val hadWindows = svc.hasWindows(display)
        val start = SystemClock.uptimeMillis()
        delay(100)
        while (true) {
            val now = SystemClock.uptimeMillis()
            val last = svc.lastEventAt
            val changed = last >= start
            if (now - start >= 3_000) return
            val quiet = (changed && now - last >= 300) || (!changed && now - start >= 1_000)
            if (quiet && (!hadWindows || svc.hasWindows(display))) return
            delay(50)
        }
    }

    private suspend fun point(p: JSONObject): Pair<Int, Int> {
        val s = requireScreen()
        val target = p.optString("ref")
        if (target.isNotBlank()) return locate(target).let { l ->
            // Land a few pixels off the centre, never on the same dot twice.
            if (pace == "fast") l.x to l.y
            else (l.x + (-3..3).random()).coerceIn(0, s.width - 1) to (l.y + (-3..3).random()).coerceIn(0, s.height - 1)
        }
        if (!p.has("x") || !p.has("y")) throw BadRequest("give ref (a ref or a selector), or x and y")
        return checked(s, p.getInt("x"), p.getInt("y"))
    }

    private class Located(val x: Int, val y: Int, val node: AccessibilityNodeInfo?, val editable: Boolean)

    /**
     * Where the element named by [target] is right now. A ref is an element, not a spot: its live
     * node is asked for its current position, and if the app has replaced that node it is found again
     * by role, name and id. A selector is matched against a fresh snapshot, waiting up to [timeoutMs]
     * for it to appear; it must name exactly one element. Either way the position must hold still
     * before it is used, so a page that is still loading does not shift the tap onto something else.
     */
    private suspend fun locate(target: String, timeoutMs: Long = 3_000): Located {
        val s = requireScreen()
        val svc = ScreenReaderService.current.value
            ?: throw BadRequest("the accessibility service is not connected, so elements cannot be found; use x and y from phone_screenshot", "reader_unavailable")
        suspend fun fresh() = withContext(Dispatchers.IO) { svc.snapshot(s.id, s.width, s.height) }.also { lastSnapshot = it }
        fun describe(key: String, r: UiRef) = "[$key] ${r.role}" + (r.name?.let { " \"$it\"" } ?: "") + (r.id?.let { " #$it" } ?: "")

        val key = Selector.refKey(target)
        val entry: UiRef = if (key != null) {
            val before = lastSnapshot ?: throw BadRequest("unknown ref $target; refs come from the latest snapshot, call phone_snapshot", "not_found")
            val known = before.refs[key] ?: throw BadRequest("unknown ref $target; refs come from the latest snapshot, call phone_snapshot", "not_found")
            val node = known.handle as? AccessibilityNodeInfo
            if (node != null && withContext(Dispatchers.IO) { node.refresh() } && node.isVisibleToUser) known
            else {
                // The app replaced that node. Its role, name and id are what is left to recognise it by.
                val wasOneOf = before.refs.values.count { it.sameElementAs(known) }
                val same = fresh().refs.values.filter { it.sameElementAs(known) }
                when {
                    same.size == 1 && wasOneOf == 1 -> same.single()
                    same.size == wasOneOf && known.nth < same.size -> same[known.nth]
                    same.isEmpty() -> throw BadRequest("${describe(key, known)} is no longer on the screen; take a new phone_snapshot", "not_found")
                    else -> throw BadRequest("the screen changed and ${describe(key, known)} can no longer be told apart from ${same.size} similar elements; take a new phone_snapshot", "ambiguous")
                }
            }
        } else {
            val selector = try { Selector.parse(target) } catch (e: IllegalArgumentException) { throw BadRequest(e.message ?: "bad selector") }
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var found: UiRef? = null
            while (found == null) {
                val hits = selector.matches(fresh())
                when {
                    hits.size == 1 -> found = hits.single().value
                    hits.size > 1 -> throw BadRequest(
                        "$target matches ${hits.size} elements: " + hits.take(5).joinToString("; ") { describe(it.key, it.value) } +
                            ". Add \" >> nth=N\" (0-based) or use a ref",
                        "ambiguous",
                    )
                    SystemClock.elapsedRealtime() > deadline -> throw BadRequest("no element matches $target on the current screen", "not_found")
                    else -> delay(300)
                }
            }
            found
        }
        if (!entry.enabled) throw BadRequest("that element is disabled", "not_actionable")

        val node = entry.handle as? AccessibilityNodeInfo ?: return Located(entry.x, entry.y, null, entry.editable)
        fun box() = Rect().also(node::getBoundsInScreen)
        var rect = box()
        // Wait for it to stop moving (a list still loading, an animation), up to about a second.
        for (i in 0 until 8) {
            delay(120)
            if (!withContext(Dispatchers.IO) { node.refresh() }) break
            val next = box()
            if (next == rect) break
            rect = next
        }
        val x = (maxOf(rect.left, 0) + minOf(rect.right, s.width)) / 2
        val y = (maxOf(rect.top, 0) + minOf(rect.bottom, s.height)) / 2
        if (rect.isEmpty || x !in 0 until s.width || y !in 0 until s.height) {
            throw BadRequest("that element is not on the visible part of the screen; scroll to it and take a new phone_snapshot", "not_actionable")
        }
        return Located(x, y, node, entry.editable)
    }

    /** Waits until [target] is on the screen, or (state "gone") until it no longer is. */
    private suspend fun waitFor(p: JSONObject): CommandResult {
        val s = requireScreen()
        val svc = ScreenReaderService.current.value ?: throw BadRequest("the accessibility service is not connected", "reader_unavailable")
        val target = p.str("target")
        val gone = when (p.optString("state").ifBlank { "visible" }) {
            "visible" -> false
            "gone" -> true
            else -> throw BadRequest("state must be visible or gone")
        }
        val timeout = p.optLong("timeout_ms", 5_000).coerceIn(200, 20_000)
        val selector = try { Selector.parse(target) } catch (e: IllegalArgumentException) { throw BadRequest(e.message ?: "bad selector") }
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            val snap = withContext(Dispatchers.IO) { svc.snapshot(s.id, s.width, s.height) }.also { lastSnapshot = it }
            val present = selector.matches(snap).isNotEmpty()
            if (present != gone) {
                return CommandResult.Ok(JSONObject().put("display", s.id).put("done", if (gone) "$target is gone" else "$target is on the screen")
                    .put("package", snap.packageName ?: JSONObject.NULL).put("snapshot", snap.text))
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                return CommandResult.Error("after ${timeout} ms $target is ${if (gone) "still on the screen" else "not on the screen"}", "wait_timeout")
            }
            delay(300)
        }
    }

    private fun checked(s: Screen, x: Int, y: Int): Pair<Int, Int> {
        if (x !in 0 until s.width || y !in 0 until s.height) throw BadRequest("point $x,$y is outside the ${s.width}x${s.height} display")
        return x to y
    }

    private fun requireScreen(): Screen = screen ?: throw IOException("background display not ready")

    /** Calls the helper, (re)starting it if needed; retries once if the helper died mid-call. */
    private suspend fun <T> withHelper(block: (HelperClient) -> T): T {
        repeat(2) { attempt ->
            val h = helper ?: run {
                if (!ensureShell()) throw IOException(state.value.message)
                helper ?: throw IOException("helper missing")
            }
            try {
                return withContext(Dispatchers.IO) { block(h) }
            } catch (e: IOException) {
                Log.w(TAG, "helper call failed: ${e.javaClass.simpleName}")
                if (helper === h) {
                    helper = null
                    h.close()
                    _state.update { it.copy(shellReady = false) }
                }
                if (attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    // --- setup ---

    private suspend fun setUp() {
        val existing = helper
        if (existing != null && screen != null) {
            try {
                existing.call("hello", timeoutMs = 3_000)
                ensureAccessibility(existing, screen!!.id)
                return
            } catch (e: IOException) {
                helper = null
                existing.close()
            }
        }
        val hadDisplay = sessionFile.isFile
        val reused = reuseHelper()
        val client = reused ?: startHelper()
        // A new helper means a new display: whatever was open on the old one is gone, and agents should be told why.
        if (reused == null && hadDisplay) restarted = true
        val (width, height, dpi) = displayShape()
        val made = client.call("display", JSONObject().put("width", width).put("height", height).put("dpi", dpi)).json
        val id = made.getInt("display")
        helper = client
        // A helper that outlived this process keeps the display it already made; go by what it reports.
        screen = Screen(id, made.optInt("width", width), made.optInt("height", height), made.optInt("dpi", dpi))
        ScreenReaderService.targetDisplay = id
        _state.update { it.copy(shellReady = true, displayId = id, message = context.getString(R.string.phone_ready)) }
        ensureAccessibility(client, id)
    }

    /**
     * The background display takes this phone's own screen size and density, so an app that the owner
     * later opens on the main screen (or that is handed over) looks the same there: apps lay themselves
     * out for the display they start on, and many do not redo it well when moved to a different one.
     * A screen that is not phone-shaped (a tablet, an unfolded foldable) gets an upright phone-shaped
     * column of the same height and density instead, which is what apps and agents both handle best.
     */
    private fun displayShape(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return shapeFor(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /** Reuses a running helper from an earlier app process if it answers and runs this APK. */
    private fun reuseHelper(): HelperClient? {
        val session = HelperClient.Session.load(sessionFile) ?: return null
        val client = runCatching { HelperClient.connect(session) }.getOrNull() ?: return null
        return try {
            if (client.call("hello", timeoutMs = 3_000).json.optString("apk") == context.applicationInfo.sourceDir) {
                client
            } else {
                runCatching { client.call("quit", timeoutMs = 2_000) }
                client.close()
                null
            }
        } catch (e: Exception) {
            client.close()
            null
        }
    }

    private suspend fun startHelper(): HelperClient {
        progress(context.getString(R.string.phone_connecting))
        val key = AdbKey.of(context)
        val session = HelperClient.Session.fresh()
        openShell(key).use { shell ->
            if (WirelessAdb.isPaired(context) && !WirelessAdb.canSwitchOn(context)) {
                // Lets the app switch wireless debugging back on by itself after a reboot.
                runCatching { shell.exec("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS") }
            }
            progress(context.getString(R.string.phone_starting))
            shell.exec(
                HelperClient.launchCommand(context.applicationInfo.sourceDir, session.port),
                stdin = (session.secret + "\n").toByteArray(),
                timeoutMs = 10_000,
            )
            session.save(sessionFile)
            // Keep the adb connection open until the helper answers: closing it at once can take the
            // just-started helper down with it on some phones.
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                delay(250)
                runCatching { HelperClient.connect(session) }.getOrNull()?.let { return it }
            }
        }
        throw HelperError("helper did not start (port ${session.port})")
    }

    /**
     * This phone's wireless debugging (paired from the app, no computer) when it is on or we may
     * switch it on; otherwise the legacy port left open by `adb tcpip 5555`.
     */
    private fun openShell(key: AdbKey): AdbShell {
        val paired = WirelessAdb.isPaired(context)
        var wirelessError: IOException? = null
        // Only a phone paired from the app gets wireless debugging switched on by us (e.g. after a reboot).
        if (WirelessAdb.isOn(context) || (paired && WirelessAdb.switchOn(context))) {
            progress(context.getString(R.string.phone_finding_port))
            val port = WirelessAdb.findLocalPort(context, WirelessAdb.CONNECT_SERVICE, 10_000)
            // Right after pairing, Android may take a moment to trust the new key.
            val attempts = if (paired) 4 else 1
            if (port != null) for (attempt in 1..attempts) {
                try {
                    return AdbShell.connect("127.0.0.1", port, key, AdbKey.NAME) {}
                        .also { Log.i(TAG, "shell via wireless debugging") }
                } catch (e: ShellNotAuthorizedException) {
                    wirelessError = e
                    if (attempt < attempts) Thread.sleep(1_000)
                }
            }
        }
        try {
            return AdbShell.connect("127.0.0.1", ADB_PORT, key, AdbKey.NAME) {
                progress(context.getString(R.string.phone_wait_allow))
            }.also { Log.i(TAG, "shell via adb tcpip port") }
        } catch (e: ShellUnavailableException) {
            throw wirelessError ?: e
        }
    }

    /** A small JPEG of the background display for the owner to watch, or null when it is not running. */
    suspend fun preview(): ByteArray? {
        val h = helper ?: return null
        if (screen == null) return null
        return runCatching {
            withContext(Dispatchers.IO) { h.call("screenshot", JSONObject().put("maxWidth", 540).put("quality", 60)).bytes }
        }.getOrNull()
    }

    /** Enables our accessibility service through the helper (keeping others enabled) and waits for it to bind. */
    private suspend fun ensureAccessibility(client: HelperClient, displayId: Int) {
        if (ScreenReaderService.current.value == null) {
            val ours = ComponentName(context, ScreenReaderService::class.java)
            val current = shell(client, "settings", "get", "secure", "enabled_accessibility_services").trim()
            val entries = current.split(':').filter { it.isNotBlank() && it != "null" }
            if (entries.none { ComponentName.unflattenFromString(it) == ours }) {
                shell(client, "settings", "put", "secure", "enabled_accessibility_services", (entries + ours.flattenToString()).joinToString(":"))
            }
            shell(client, "settings", "put", "secure", "accessibility_enabled", "1")
            withTimeoutOrNull(8_000) { ScreenReaderService.current.first { it != null } }
        }
        val ready = ScreenReaderService.current.value != null
        _state.update {
            it.copy(a11yReady = ready, message = context.getString(if (ready) R.string.phone_ready else R.string.phone_ready_no_reader))
        }
    }

    private fun shell(client: HelperClient, vararg argv: String): String =
        client.call("exec", JSONObject().put("argv", JSONArray(argv.toList())).put("timeoutMs", 10_000)).json.optString("out")

    private fun progress(message: String) = _state.update { it.copy(message = message) }

    private fun fail(message: String): Boolean {
        _state.update { it.copy(shellReady = false, message = message) }
        return false
    }

    private fun xy(x: Int, y: Int) = JSONObject().put("x", x).put("y", y)

    private fun JSONObject.str(key: String, allowEmpty: Boolean = false): String {
        if (!has(key) || isNull(key)) throw BadRequest("missing $key")
        val v = optString(key)
        if (!allowEmpty && v.isBlank()) throw BadRequest("$key is empty")
        return v
    }

    companion object {
        /** Upright; the phone's own size when it is phone-shaped (16:9 or taller), else a 9:20 column of its long side. */
        internal fun shapeFor(w: Int, h: Int, dpi: Int): Triple<Int, Int, Int> {
            val short = minOf(w, h)
            val long = maxOf(w, h)
            val width = if (long * 9 >= short * 16) short else (long * 9 / 20) / 2 * 2
            return Triple(width, long, dpi)
        }


        /** Actions that change the screen and so are held to a tempo. */
        private val PACED = setOf("phone_launch", "phone_open_url", "phone_tap", "phone_long_press", "phone_swipe", "phone_type", "phone_key")
        /** Of those, the ones that can take the agent to another screen (scrolling and typing do not). */
        private val CHANGES_SCREEN = setOf("phone_launch", "phone_open_url", "phone_tap", "phone_long_press", "phone_key")
        /** Results of these carry the element tree, and can carry a picture with it. */
        private val RETURNS_SCREEN = setOf("phone_launch", "phone_open_url", "phone_snapshot", "phone_tap", "phone_long_press", "phone_swipe", "phone_type", "phone_key", "phone_wait")
        /** How long a result waits for a progress indicator the action started. */
        private const val LOADING_WAIT_MS = 6_000L
        /** How long an action that changed nothing is given to show its effect. */
        private const val UNCHANGED_WAIT_MS = 1_500L
        private const val TAG = "PhoneController"
        private const val ADB_PORT = 5555
    }
}
