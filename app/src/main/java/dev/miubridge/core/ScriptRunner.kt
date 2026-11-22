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
            "Run JavaScript on the phone that calls the other phone tools, so many steps take one round trip. In the script: " +
                "call(name, args) runs a tool and returns its result object, and throws if the tool fails; findRef(snapshot, /pattern/) " +
                "returns the ref of the first matching snapshot line; print(value) adds a line to the output; sleep(ms) waits. " +
                "Only what you print, and the script's return value, comes back. " +
                "Example: call('phone_launch',{package:'com.android.settings'}); const r = call('phone_tap',{ref:'text=Network'}); print(r.snapshot). " +
                "call('phone_screenshot',{}) adds a picture to the result (at most 4). Tool calls are real and are not undone if the " +
                "script fails. Limit 120 seconds.",
            required = mapOf("code" to Param("string", "The JavaScript to run. Plain statements; `return x` ends the script with a value.")),
            timeoutMs = 130_000,
        )

        /** How and when to use scripts; part of every agent's instructions, on the phone or connected over MCP. */
        val GUIDE = """
            ## Many steps in one call

            `phone_script` runs JavaScript on the phone that calls the other tools. One script does every step you can
            already plan and returns only what it prints, which is faster and keeps long snapshots out of your context.

            - Put in as many steps as you can plan now: one action on an unfamiliar screen, the whole flow on a known
              one. Stop to look only when what you see would change what you do next.
            - `call(name, args)` runs a tool and returns its result; it throws if the tool fails. The JavaScript SDK's
              `phone` object works too, without `await`: `phone.tap('text=Send')`, `phone.type('hello', 'role=input')`.
            - Name elements with selectors, which need no snapshot. Every action returns a `snapshot` string;
              `findRef(snapshot, /pattern/)` gives the ref of its first matching line, or null.
            - `phone_launch` returns when the app's first screen is there. Content that loads later needs
              `call('phone_wait', {target: ...})` before you read or tap it.
            - `print(value)` adds to the output, `sleep(ms)` waits, `return value` ends the script. End by printing what
              you need next: the facts you were after, or the lines you will act on.
            - Steps already done are not undone when a script fails; the error says how many succeeded and carries what
              was printed, so print progress as you go.

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
            // The same `phone` object as the JavaScript SDK, so code written for one runs in the other.
            // Results that show the screen also read like the SDK's Screen: text, lines, find(), texts().
            function __screen(r) {
              if (r && typeof r.snapshot === 'string') {
                r.text = r.snapshot;
                r.lines = r.snapshot.split('\n').slice(1);
                r.find = function (pattern) { return findRef(r.lines.join('\n'), pattern instanceof RegExp ? pattern : new RegExp(pattern)); };
                r.texts = function () { return r.lines.map(function (l) { var m = l.match(/"([^"]*)"/); return m ? m[1] : undefined; }).filter(function (t) { return t !== undefined; }); };
              }
              return r;
            }
            function __target(t) { return typeof t === 'string' ? {ref: t} : (t || {}); }
            function __opts(args, opts) { if (opts && opts.screenshot) args.screenshot = true; return args; }
            var phone = {
              call: function (name, args) { return call(name, args); },
              apps: function (query) { return call('phone_apps', {query: query}).apps || []; },
              status: function () { return call('phone_status', {}); },
              snapshot: function (opts) { return __screen(call('phone_snapshot', __opts({}, opts))); },
              screenshot: function () { return call('phone_screenshot', {}); },
              wait: function (target, o) {
                o = o || {};
                return __screen(call('phone_wait', __opts({target: target, state: o.state || 'visible', timeout_ms: o.timeoutMs || 5000}, o)));
              },
              launch: function (pkg, opts) { return __screen(call('phone_launch', __opts({package: pkg}, opts))); },
              openUrl: function (url, opts) { return __screen(call('phone_open_url', __opts({url: url}, opts))); },
              tap: function (target, opts) { return __screen(call('phone_tap', __opts(__target(target), opts))); },
              longPress: function (target, opts) { return __screen(call('phone_long_press', __opts(__target(target), opts))); },
              swipe: function (how, opts) { return __screen(call('phone_swipe', __opts(typeof how === 'string' ? {direction: how} : (how || {}), opts))); },
              type: function (text, target, opts) { return __screen(call('phone_type', __opts(target ? {text: text, ref: target} : {text: text}, opts))); },
              key: function (key, opts) { return __screen(call('phone_key', __opts({key: key}, opts))); },
              handoff: function () { return call('phone_handoff', {}); },
              takeback: function () { return __screen(call('phone_takeback', {})); },
              script: function () { throw new Error('a script cannot start another script'); },
              notes: function () { return call('phone_skills', {}).skills || []; },
              readNotes: function (name) { return call('phone_skill_read', {name: name}).skill || ''; },
              learn: function (name, fact, o) { o = o || {}; return call('phone_skill_learn', {name: name, fact: fact, description: o.description, replaces: o.replaces}); },
              forget: function (name, fact) { return call('phone_skill_forget', {name: name, fact: fact}); },
            };
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
