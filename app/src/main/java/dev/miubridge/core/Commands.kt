package dev.miubridge.core

import org.json.JSONObject

/** One parameter of a command, as an agent sees it in the tool's schema. */
data class Param(val type: String, val description: String)

/** A command the phone offers agents. */
data class CommandSpec(
    val description: String,
    val required: Map<String, Param> = emptyMap(),
    val optional: Map<String, Param> = emptyMap(),
    val timeoutMs: Long = 30_000,
)

sealed class CommandResult {
    /** Success. [images] are JPEG bytes returned beside the payload (MCP image content). */
    data class Ok(val payload: JSONObject, val images: List<ByteArray> = emptyList()) : CommandResult()

    /**
     * Failure with a short, safe message for the agent to read, and a stable [code] for a script to
     * branch on. The codes are listed in the guide; "failed" is the catch-all.
     */
    data class Error(val message: String, val code: String = "failed") : CommandResult()
}

/** What the MCP server calls; the phone layer implements it. */
interface CommandHandler {
    /** Command name → spec. Names are tool names: lowercase with underscores, such as `phone_tap`. */
    fun specs(): Map<String, CommandSpec>

    /** Markdown for the agent: what this phone is and how to drive it (MCP server instructions). */
    fun guideMarkdown(): String

    suspend fun invoke(name: String, params: JSONObject): CommandResult
}
