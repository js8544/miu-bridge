package dev.miubridge.phone

import dev.miubridge.core.CommandSpec
import dev.miubridge.core.Param

/** Tool contracts and the agent guide for the phone layer. */
internal object PhoneSpecs {
    private const val RETURNS = "Returns the snapshot of the screen once it has settled."

    private val ref = Param("string", "The element: a ref from the latest snapshot (e12), or a selector that needs no snapshot: " +
        "text=Send (contains), text=\"Send\" (exact), id=send_btn (the #id in snapshots), role=button[name=\"Send\"], " +
        "each optionally followed by \" >> nth=N\". A selector must match exactly one element and is waited for up to 3 s.")
    private val shot = Param("boolean", "true to also get a JPEG of the screen in the same result (image pixels × `scale` = display pixels).")
    private val x = Param("integer", "X in display pixels, as in a snapshot's @x,y.")
    private val y = Param("integer", "Y in display pixels, as in a snapshot's @x,y.")

    val all: Map<String, CommandSpec> = mapOf(
        "phone_apps" to CommandSpec(
            "List the apps on the phone that can be opened (label, package). `blocked` marks the ones the owner has closed to agents.",
            optional = mapOf("query" to Param("string", "Only apps whose label or package contains this, e.g. \"mail\". Leave out for all of them.")),
        ),
        "phone_status" to CommandSpec(
            "The phone's state, read without touching the display: `locked`, `ready` (the background display is up), `display` " +
                "(id, width, height, dpi), `package` (the app in front on it), `reader` (element trees are available), `restarted` " +
                "(the display was restarted and its apps closed, not yet reported in a result), `handed_off` (the app the owner " +
                "still holds from phone_handoff, or null) and the app `version`.",
        ),
        "phone_launch" to CommandSpec(
            "Open an app on the background display, or bring it to the front there. An app the owner has closed to agents is " +
                "refused (`blocked_app`). Returns the snapshot once the app's first screen is there.",
            required = mapOf("package" to Param("string", "Package name from phone_apps, e.g. com.android.settings.")),
            optional = mapOf("screenshot" to shot),
            timeoutMs = 30_000,
        ),
        "phone_snapshot" to CommandSpec(
            "The screen as text: its size and the app in front, then one line per element, `[ref] role \"text\" @x,y #id (flags)`. " +
                "Every action returns one, so call this only to look without acting. A ref keeps working while its element is on screen.",
            optional = mapOf("screenshot" to shot),
        ),
        "phone_screenshot" to CommandSpec(
            "A JPEG of the screen, about 720 px wide. Image pixels × `scale` = display pixels.",
        ),
        "phone_tap" to CommandSpec(
            "Tap an element (`ref`) or a point (`x`, `y`). $RETURNS",
            optional = mapOf("ref" to ref, "x" to x, "y" to y, "screenshot" to shot),
        ),
        "phone_long_press" to CommandSpec(
            "Long-press an element (`ref`) or a point (`x`, `y`) for about 0.8 s. $RETURNS",
            optional = mapOf("ref" to ref, "x" to x, "y" to y, "screenshot" to shot),
        ),
        "phone_swipe" to CommandSpec(
            "Swipe by `direction`, or between two points. The direction is the finger's: \"up\" moves the finger up, which shows " +
                "what is further down. $RETURNS",
            optional = mapOf(
                "direction" to Param("string", "up | down | left | right, across the middle of the screen."),
                "x1" to Param("integer", "Start X."),
                "y1" to Param("integer", "Start Y."),
                "x2" to Param("integer", "End X."),
                "y2" to Param("integer", "End Y."),
                "duration_ms" to Param("integer", "Swipe duration, default 300."),
                "screenshot" to shot,
            ),
        ),
        "phone_type" to CommandSpec(
            "Replace the text of an input field, in any language. Without `ref` it uses the field that has focus. Some apps " +
                "(WeChat, for one) hide their fields from the element tree; the text then goes into whatever has focus, so tap " +
                "the field first and check the result with a screenshot. It does not submit: use phone_key enter or the app's button. $RETURNS",
            required = mapOf("text" to Param("string", "The full new text of the field.")),
            optional = mapOf("ref" to Param("string", "The input, as a ref or a selector (e.g. role=input, id=search_box)."), "screenshot" to shot),
        ),
        "phone_key" to CommandSpec(
            "Press back, enter or home. Home closes every app on the background display. $RETURNS",
            required = mapOf("key" to Param("string", "back | enter | home")),
            optional = mapOf("screenshot" to shot),
        ),
        "phone_open_url" to CommandSpec(
            "Open a URL or deep link with the app that handles it. $RETURNS",
            required = mapOf("url" to Param("string", "Absolute URL, e.g. https://example.com or an app deep link.")),
            optional = mapOf("screenshot" to shot),
        ),
        "phone_wait" to CommandSpec(
            "Wait until an element is on the screen, or until it is gone (a spinner, a dialog), and return the snapshot at that " +
                "moment. Fails with `wait_timeout`. Use it instead of guessing how long a page takes to load.",
            required = mapOf("target" to Param("string", "A selector: text=…, id=…, role=…[name=…].")),
            optional = mapOf(
                "state" to Param("string", "visible (default) or gone."),
                "timeout_ms" to Param("integer", "How long to wait, default 5000, at most 20000."),
                "screenshot" to shot,
            ),
            timeoutMs = 30_000,
        ),
        "phone_handoff" to CommandSpec(
            "Move the app in front to the owner's own screen so they can take over: payment, sign-in, face or SMS " +
                "verification, anything irreversible. You lose that app until phone_takeback.",
        ),
        "phone_takeback" to CommandSpec(
            "Bring back the app given to the owner by phone_handoff, exactly as they left it, and return its snapshot. Call it " +
                "once the owner says they are done. Opening the app again instead would start a second copy from its home page.",
        ),
    )

    val guide = """
        # Miu Bridge

        You operate apps on an Android phone, on a background display its owner does not see. Their own screen is
        untouched, and you cannot reach it.

        ## How to work

        1. Find the app with `phone_apps` and open it with `phone_launch`, or open a link with `phone_open_url`.
        2. Every action returns the screen as a snapshot, one line per element: `[e5] button "Send" @540,1620 #send_btn`.
           Act on elements, not on guessed coordinates.
        3. Name an element by its ref (`e5`), or by a selector when you already know what you want, which saves a read:
           `text=Send`, `text="Send"` (exact), `id=send_btn`, `role=button[name="Send"]`. If several match you get the
           list; add ` >> nth=N`.
        4. `phone_type` sets a field's text directly; the display shows no keyboard. Submit with `phone_key enter` or
           the app's own button.
        5. `phone_swipe {direction: "up"}` shows what is further down.
        6. A result with `loading: true` is a page still loading: `phone_wait` for what you expect.
        7. When the element tree is not enough (games, maps, web content), pass `screenshot: true` and work from the
           picture, tapping by x,y (image pixels × `scale`). A screen with no tree at all sends its picture unasked.
           A picture taken while the page changes can show two pages at once; take another.

        The display is upright, with this phone's own size; the size is in the first line of every snapshot.

        ## What is not yours to do

        - No paying, placing orders, transferring money, deleting data or changing security settings.
        - Payment, sign-in, face or SMS verification, or anything else that needs the owner: `phone_handoff` moves the
          app to their screen; tell them what is waiting. When they say they are done, `phone_takeback` returns the
          app as they left it. Do not open it again instead.
        - `blocked_app`: the owner has closed that app to agents (`phone_apps` marks it). Ask them.
        - `paced`, or a `warning` in a result: the phone is keeping an app's account from looking automated. Stop
          acting in that app, and hand off if a verification screen is showing. Do not work around it.
    """.trimIndent()
}
