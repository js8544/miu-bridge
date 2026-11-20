package dev.miubridge.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Code mode: runs a JavaScript script that calls the phone tools, so an agent can do many steps in
 * one round trip and get back only what the script prints. The script runs in a hidden WebView with
 * no network and no page; its only door to the phone is [Bridge.call], which goes through the same
 * tool handler (and the same safety checks) as a single tool call.
 */
class ScriptRunner(private val context: Context, private val tools: CommandHandler) {

    /** [images] are the screenshots the script took, in order; [calls] is how many tool calls succeeded. */
    class Result(val output: String, val error: String?, val images: List<ByteArray>, val calls: Int, val code: String? = null)

    suspend fun run(code: String, timeoutMs: Long = 120_000): Result {
        val finished = CompletableDeferred<Unit>()
        val bridge = Bridge(finished)
        val main = Handler(Looper.getMainLooper())
        var view: WebView? = null
        main.post {
            @SuppressLint("SetJavaScriptEnabled")
            view = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(bridge, "__pb")
                // JSON.stringify of the source keeps any script text from breaking out of the wrapper.
                evaluateJavascript(PRELUDE + "\n__run(" + JSONObject.quote(code) + ");", null)
            }
        }
        val completed = withTimeoutOrNull(timeoutMs) { finished.await() } != null
        bridge.stopped = true
        main.post { view?.apply { removeJavascriptInterface("__pb"); destroy() } }
        val error = bridge.error ?: if (completed) null else "timed out after ${timeoutMs / 1000} s; tool calls already made are not undone"
        // A script stopped by a failed tool call fails for that call's reason; keep its code.
        return Result(bridge.output.toString().take(MAX_OUTPUT), error, bridge.images.toList(), bridge.calls, bridge.code)
    }

    private inner class Bridge(private val finished: CompletableDeferred<Unit>) {
        val output = StringBuilder()
        val images = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        @Volatile var calls = 0
        @Volatile var error: String? = null
        @Volatile var code: String? = null
        @Volatile var stopped = false

        /** Runs one tool. Returns `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`. */
        @JavascriptInterface
        fun call(name: String, argsJson: String): String {
            if (stopped) return fail("the script was stopped")
            if (name !in tools.specs() || name in NOT_IN_SCRIPTS) return fail("$name cannot be called from a script")
            val args = runCatching { JSONObject(argsJson) }.getOrElse { return fail("arguments must be an object") }
            return when (val r = runBlocking { tools.invoke(name, args) }) {
                is CommandResult.Ok -> {
                    calls++
                    // A screenshot's picture cannot go into the script; it rides back with the script's result.
                    if (r.images.isNotEmpty()) {
                        if (images.size < MAX_IMAGES) {
                            images += r.images.first()
                            r.payload.put("image", images.size)
                        } else if (name == "phone_screenshot") {
                            return fail("a script can return at most $MAX_IMAGES screenshots", "too_many_screenshots")
                        } // a picture that merely came along with an action is dropped, the action stands
                    }
                    JSONObject().put("ok", true).put("result", r.payload).toString()
                }
                is CommandResult.Error -> fail(r.message, r.code)
            }
        }

        @JavascriptInterface
        fun print(text: String) {
            synchronized(output) { if (output.length < MAX_OUTPUT) output.append(text).append('\n') }
        }

        @JavascriptInterface
        fun sleep(ms: Int) = Thread.sleep(ms.coerceIn(0, 10_000).toLong())

        @JavascriptInterface
        fun done(errorMessage: String?, errorCode: String?) {
            if (!errorMessage.isNullOrEmpty()) {
                error = errorMessage
                code = errorCode?.takeIf { it.isNotEmpty() }
            }
            finished.complete(Unit)
        }

        private fun fail(message: String, code: String = "failed") = JSONObject().put("ok", false).put("error", message).put("code", code).toString()
    }

    companion object {
        const val TOOL = "phone_script"
        private const val MAX_OUTPUT = 20_000
        private const val MAX_IMAGES = 4
        // A script must not start another script.
        private val NOT_IN_SCRIPTS = setOf(TOOL)

        val SPEC = CommandSpec(
            "Run a JavaScript script on the phone that calls the other phone tools, so many steps take one round trip. " +
                "In the script: call(name, args) runs a tool and returns its result object (it throws if the tool fails); " +
                "findRef(snapshot, /pattern/) returns the ref of the first matching snapshot line; " +
                "print(value) adds a line to the output; sleep(ms) waits. Only what you print, plus the script's return value, " +
                "comes back, so read snapshots inside the script and print just what you need. Example: " +
                "call('phone_launch',{package:'com.android.settings'}); const s = call('phone_snapshot',{}); print(s.snapshot.split('\\n').length). " +
                "call('phone_screenshot',{}) takes a picture that comes back with the script's result (at most 4 per script, numbered in " +
                "the order taken). Tool calls are real and are not undone if the script fails. Limit 120 seconds.",
            required = mapOf("code" to Param("string", "The JavaScript to run. Plain statements; `return x` ends the script with a value.")),
            timeoutMs = 130_000,
        )

        /** How and when to use scripts; part of every agent's instructions, on the phone or connected over MCP. */
        val GUIDE = """
            ## Scripts (code mode)

            `phone_script` runs JavaScript on the phone that calls the other phone tools. Think in scripts rather
            than single calls: one script does every step you can already plan and returns only what it prints, which
            is faster and keeps long snapshots out of your context.

            How much to put in one script:
            - As many steps as what you know now lets you plan. On an unfamiliar screen that may be one action;
              on a known flow it is the whole flow.
            - Stop and look in between only when what you see would change what you do next.
            - End by printing what the next round needs: the facts you were after, or the lines of the final snapshot
              you will act on. Then the next script can start from that without another read.
            The single tools remain for when one action is all you need.

            In a script:
            - `call(name, args)` runs a tool and returns its result object; it throws if the tool fails.
            - Name elements with selectors, which need no snapshot: `call('phone_tap', {ref: 'text=Send'})`,
              `{ref: 'id=tab_home'}`, `{ref: 'role=button[name="OK"]'}`. They wait up to 3 s for the element and fail if
              it is missing or ambiguous. `call('phone_wait', {target: 'text=Results'})` waits for a page to load.
            - Every action returns a fresh `snapshot` string to read from. `findRef(snapshot, /pattern/)` returns the ref
              of the first line matching a regular expression, or null, for when a selector cannot say it.
            - `print(value)` adds a line to the output; `sleep(ms)` waits; `return value` ends the script.
            - Print only what you need. `call('phone_screenshot', {})` takes a picture that comes back with the
              script's result (up to 4, in the order taken), for screens the element tree cannot describe or to see
              where a script went wrong.
            - Tool calls are real. If the script fails midway, the earlier steps are not undone: the error says how many
              calls had succeeded and comes back with what was printed so far, so print progress as you go.
            - `phone_launch` returns once the app's first screen is there. Content that loads later (a list, a search
              result) still needs `call('phone_wait', {target: ...})` before you read or tap it.

            Example: open an app, enter a section, and collect the titles on three screens of a list.

            ```js
            call('phone_launch', {package: 'com.android.settings'});
            let r = call('phone_tap', {ref: 'text=Network'});
            const titles = new Set();
            for (let i = 0; i < 3; i++) {
              r.snapshot.split('\n').forEach(l => { const m = l.match(/"([^"]+)"/); if (m) titles.add(m[1]); });
              r = call('phone_swipe', {direction: 'up'});
            }
            print([...titles].join('\n'));
            ```
        """.trimIndent()

        private val PRELUDE = """
            function findRef(snapshot, pattern) {
              var lines = String(snapshot || '').split('\n');
              for (var i = 0; i < lines.length; i++) {
                if (pattern.test ? pattern.test(lines[i]) : lines[i].indexOf(pattern) >= 0) {
                  var m = lines[i].match(/\[(e\d+)\]/);
                  if (m) return m[1];
                }
              }
              return null;
            }
            function call(name, args) {
              var r = JSON.parse(__pb.call(String(name), JSON.stringify(args || {})));
              if (!r.ok) { var e = new Error(name + ': ' + r.error); e.code = r.code; throw e; }
              return r.result;
            }
            function print() {
              __pb.print(Array.prototype.map.call(arguments, function (v) { return typeof v === 'string' ? v : JSON.stringify(v); }).join(' '));
            }
            function sleep(ms) { __pb.sleep(ms | 0); }
            function __run(source) {
              var error = '', code = '';
              try {
                var value = (new Function(source))();
                if (value !== undefined) print(value);
              } catch (e) { error = String(e && e.message ? e.message : e); code = String(e && e.code ? e.code : ''); }
              __pb.done(error, code);
            }
        """.trimIndent()
    }
}
