package dev.miubridge.phone

import android.content.Context

/**
 * Which apps agents may open on the background display, as the owner set it. Nothing is closed to
 * agents until the owner chooses so. Two modes, each with its own list, kept apart so switching mode
 * does not lose the other list:
 *
 * - [Mode.BLOCK] (the default): agents may open every app except those in [blocked]. An empty list
 *   closes nothing.
 * - [Mode.ALLOW]: agents may open only the apps in [allowed]. An empty list closes every app.
 *
 * The rule is checked when an agent opens an app (phone_launch), opens a link (phone_open_url: in
 * allow mode a link whose app cannot be told is refused), and takes an app back from the owner.
 */
class AppPolicy(context: Context) {
    enum class Mode { BLOCK, ALLOW }

    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)

    var mode: Mode
        get() = if (prefs.getString(MODE, null) == "allow") Mode.ALLOW else Mode.BLOCK
        set(value) { prefs.edit().putString(MODE, if (value == Mode.ALLOW) "allow" else "block").apply() }

    /** Package names agents may not open in [Mode.BLOCK]. */
    var blocked: Set<String>
        get() = prefs.getStringSet(BLOCKED, emptySet()).orEmpty()
        set(value) { prefs.edit().putStringSet(BLOCKED, value.toSet()).apply() }

    /** The only package names agents may open in [Mode.ALLOW]. */
    var allowed: Set<String>
        get() = prefs.getStringSet(ALLOWED, emptySet()).orEmpty()
        set(value) { prefs.edit().putStringSet(ALLOWED, value.toSet()).apply() }

    /** Whether agents may open [pkg]; null is an app that cannot be told, open only in block mode. */
    fun allows(pkg: String?): Boolean = when (mode) {
        Mode.BLOCK -> pkg == null || pkg !in blocked
        Mode.ALLOW -> pkg != null && pkg in allowed
    }

    /** Why [pkg] was refused, for the agent. */
    fun refusal(pkg: String?): String = when (mode) {
        Mode.BLOCK -> "The owner does not let agents open ${pkg ?: "that app"}. Ask the owner if it is needed."
        Mode.ALLOW -> "The owner lets agents open only the apps they chose, and ${pkg ?: "the app for that link"} is not one of them. " +
            "phone_apps shows which apps are open to you; ask the owner if this one is needed."
    }

    private companion object {
        const val MODE = "app_policy_mode"
        const val BLOCKED = "blocked_apps"
        const val ALLOWED = "allowed_apps"
    }
}
