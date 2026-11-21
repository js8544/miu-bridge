package dev.miubridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.miubridge.agent.AgentSession
import dev.miubridge.core.BridgeTools
import dev.miubridge.core.Skills
import dev.miubridge.phone.PhoneController
import dev.miubridge.server.AccessToken
import dev.miubridge.server.Account
import dev.miubridge.server.Traces

class App : Application() {
    lateinit var phone: PhoneController
        private set
    lateinit var token: AccessToken
        private set
    lateinit var agent: AgentSession
        private set
    lateinit var skills: Skills
        private set
    /** What MCP clients see: phone tools and app manuals. */
    lateinit var tools: BridgeTools
        private set
    /** The opt-in experience improvement program. */
    lateinit var traces: Traces
        private set

    override fun onCreate() {
        super.onCreate()
        phone = PhoneController(this)
        token = AccessToken(this)
        skills = Skills(this, java.io.File(filesDir, "workspace/skills"))
        tools = BridgeTools(this, phone, skills)
        traces = Traces(this, Account(this)).also { it.start() }
        tools.onCall = traces::record
        tools.feedback = traces
        // An app that shows a rate-limit or verification screen is remembered as one to handle slowly.
        phone.onFriction = { pkg, phrase ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            skills.learnForPackage(pkg, label, "这个 App 出现过风控提示（“$phrase”）：操作要慢，换屏要少；再出现就停下来交给主人")
        }
        agent = AgentSession(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_AGENT, getString(R.string.channel_agent), NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    companion object {
        const val CHANNEL = "bridge"
        const val CHANNEL_AGENT = "agent"
    }
}
