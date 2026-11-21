package dev.miubridge.core

/**
 * Where agents' notes go when a tool did not behave as its description says. [open] only while the
 * owner is in the experience improvement program: otherwise agents are not offered the tool at all.
 */
interface FeedbackBox {
    val open: Boolean
    fun submit(expected: String, happened: String)
}
