package dev.miubridge.phone

import dev.miubridge.core.CommandSpec
import dev.miubridge.core.Param

/** Tool contracts and the agent guide for the phone layer. */
internal object PhoneSpecs {
    private const val COORDS = "Coordinates are pixels of the background display (the same space as the @x,y in snapshots)."
    private const val RETURNS = "Waits for the screen to settle (up to ~3 s) and returns the new snapshot."

    private const val TARGETS = "An element is named by a ref from the latest snapshot (e12) or by a selector that needs no snapshot: " +
        "text=Send (contains), text=\"Send\" (exact), id=send_btn (the #id in snapshots), role=button[name=\"Send\"], " +
        "each optionally followed by \" >> nth=N\". A selector must match exactly one element and is waited for up to 3 s. " +
        "The element is located at the moment of the action, so it is hit where it is now even if the page has shifted."
    private val ref = Param("string", "A ref from the latest snapshot (\"e12\") or a selector (\"text=Send\", \"id=send_btn\", \"role=button[name=\\\"Send\\\"]\").")
    private val shot = Param("boolean", "true to get a JPEG of the screen with the snapshot, in the same result (image pixels × `scale` = display pixels).")
    private val x = Param("integer", "X in background-display pixels.")
    private val y = Param("integer", "Y in background-display pixels.")

    val all: Map<String, CommandSpec> = mapOf(
        "phone_apps" to CommandSpec(
            "List launchable apps on the phone (label, package). Apps marked blocked cannot be launched by agents.",
            optional = mapOf("query" to Param("string", "Only apps whose label or package contains this, e.g. \"mail\". Leave out for all of them.")),
        ),
        "phone_status" to CommandSpec(
            "The phone's state, read without touching the display: `locked`, `ready` (the background display is up), its `display` " +
                "(id, width, height, dpi), the `package` in front on it, `reader` (element trees are available), `restarted` " +
                "(the display was restarted and the apps on it closed; not yet reported in a result), and the app `version`.",
        ),
        "phone_launch" to CommandSpec(
            "Open an app on the background display (or bring its task there to the front). Payment and banking apps are refused. $RETURNS",
            required = mapOf("package" to Param("string", "Package name from phone_apps, e.g. com.android.settings.")),
            optional = mapOf("screenshot" to shot),
            timeoutMs = 30_000,
        ),
        "phone_snapshot" to CommandSpec(
            "Text tree of the background display: one line per element, `[ref] role \"text\" @x,y #id (flags)`, plus the foreground package. " +
                "Every action returns a fresh snapshot with new refs. A ref still works after the screen changed as long as its element is there; " +
                "selectors (text=, id=, role=) never go stale.",
            optional = mapOf("screenshot" to shot),
        ),
        "phone_screenshot" to CommandSpec(
            "JPEG of the background display, downscaled to about 720 px wide. Multiply image pixels by `scale` to get display pixels for phone_tap.",
        ),
        "phone_tap" to CommandSpec(
            "Tap an element or a point (x,y). $TARGETS $COORDS $RETURNS",
            optional = mapOf("ref" to ref, "x" to x, "y" to y, "screenshot" to shot),
        ),
        "phone_long_press" to CommandSpec(
            "Long-press an element or a point (x,y) for about 0.8 s. $TARGETS $COORDS $RETURNS",
            optional = mapOf("ref" to ref, "x" to x, "y" to y, "screenshot" to shot),
        ),
        "phone_swipe" to CommandSpec(
            "Swipe either by direction or between two points. `direction` is the finger's movement: \"up\" moves the finger up, " +
                "which scrolls the content to show what is below. $COORDS $RETURNS",
            optional = mapOf(
                "direction" to Param("string", "up | down | left | right (finger movement across the middle of the screen)."),
                "x1" to Param("integer", "Start X."),
                "y1" to Param("integer", "Start Y."),
                "x2" to Param("integer", "End X."),
                "y2" to Param("integer", "End Y."),
                "duration_ms" to Param("integer", "Swipe duration, default 300."),
                "screenshot" to shot,
            ),
        ),
        "phone_type" to CommandSpec(
            "Replace the text of an input field (any language, including Chinese). Uses the ref if given, otherwise the focused input " +
                "on the background display. If no field is reachable through accessibility (some apps, e.g. WeChat, hide theirs), it pastes " +
                "the text into whatever has focus: tap the field first, then verify with phone_screenshot. Does not press Enter; use " +
                "phone_key enter to submit. $RETURNS",
            required = mapOf("text" to Param("string", "The full new text of the field.")),
            optional = mapOf("ref" to Param("string", "The input, as a ref or a selector (e.g. role=input, id=search_box)."), "screenshot" to shot),
        ),
        "phone_key" to CommandSpec(
            "Press a key on the background display: back, enter, or home (home closes every app on the background display, " +
                "like swiping them away from recents). $RETURNS",
            required = mapOf("key" to Param("string", "back | enter | home")),
            optional = mapOf("screenshot" to shot),
        ),
        "phone_open_url" to CommandSpec(
            "Open a URL or deep link on the background display with the app that handles it. $RETURNS",
            required = mapOf("url" to Param("string", "Absolute URL, e.g. https://example.com or an app deep link.")),
            optional = mapOf("screenshot" to shot),
        ),
        "phone_wait" to CommandSpec(
            "Wait until an element is on the screen, or until it is gone (a spinner, a dialog). Returns the snapshot at that moment, " +
                "or an error after the timeout. Use it instead of guessing how long a page takes to load.",
            required = mapOf("target" to Param("string", "A selector: text=…, id=…, role=…[name=…].")),
            optional = mapOf(
                "state" to Param("string", "visible (default) or gone."),
                "timeout_ms" to Param("integer", "How long to wait, default 5000, at most 20000."),
                "screenshot" to shot,
            ),
            timeoutMs = 30_000,
        ),
        "phone_handoff" to CommandSpec(
            "Move the background display's top app to the owner's main screen so the owner can take over " +
                "(payment, login, face or SMS verification, anything irreversible). The agent loses that app afterwards.",
        ),
    )

    val guide = """
        # Phone

        You operate apps on this Android phone on a **background virtual display**. The owner keeps using the main screen;
        nothing you do appears there. Never ask for or try to reach the main screen.

        How to work:
        1. `phone_apps` to find the package, then `phone_launch`. Or `phone_open_url` for a link.
        2. Read `phone_snapshot` before acting. Each line is `[ref] role "text" @x,y #id`; act on the element (`phone_tap {ref: "e5"}`), not by guessing coordinates.
        3. Every action waits for the UI to settle and returns a fresh snapshot. When you already know what you want next, name it
           with a selector instead of reading for its ref: `phone_tap {ref: "text=Send"}`, `{ref: "id=tab_home"}`,
           `{ref: "role=button[name=\"OK\"]"}`. If a selector matches several elements you get the list; add ` >> nth=N`.
           An action waits out a loading indicator it started (a page load, a refresh), up to about 6 s; if the result
           carries `loading: true` the screen is still loading, so `phone_wait` for the content you expect.
        4. Use `phone_type` to fill fields (it sets text directly, no keyboard). Then `phone_key enter` or tap the send/search button.
        5. When the tree is not enough (games, maps, canvas or web content), pass `screenshot: true` to the action or to
           `phone_snapshot` and the picture comes back in the same result. Image pixels × `scale` = display pixels.
        6. Scroll with `phone_swipe {direction: "up"}` to see content further down.

        Coordinates are always background-display pixels.

        Things to know about the background display:
        - It is upright and phone-shaped, with this phone's own screen size and density. Its size is in the first line of
          every snapshot and in `phone_status`; positions you note belong to that size.
        - It shows no keyboard. A field that has focus takes text from `phone_type` directly.
        - Some apps expose no element tree. Every result for such a screen comes with its picture, without asking;
          work from it and tap by x,y. `phone_type` without a ref pastes into the focused field, so tap the field first.
        - A screenshot taken during a page change can show two pages on top of each other; take another.
        - On Android 12 and earlier it cannot be used while the phone is locked; the tools say so and the owner must unlock.
        - If a result carries `restarted: true` (and a `notice` saying so), the background display was restarted and apps that were open are closed.
          That is not the app crashing: launch it again.

        Pacing: in apps with an account behind them the phone holds your actions to a human tempo and, after a long run
        of screens in one app, refuses to go further for a few minutes. This protects the owner's account from being
        flagged; do not work around it. If a result carries a `warning` about a rate-limit or verification screen, stop
        acting in that app and hand off to the owner.

        Safety:
        - Do not do irreversible things: no paying, transferring money, placing orders, sending money-related messages, deleting data, or changing security settings.
        - Payment and banking apps are blocked. For payment, login, face/SMS verification, or anything that needs the owner, call `phone_handoff` and tell the owner what is waiting.
    """.trimIndent()
}
