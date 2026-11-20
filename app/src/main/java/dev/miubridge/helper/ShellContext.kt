package dev.miubridge.helper

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.os.Process

/**
 * The little a framework manager needs from a Context when called from a bare `app_process`: who
 * we are (the shell package) and a looper. Anything else on it is unsupported and throws.
 */
internal class ShellContext : ContextWrapper(null) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getApplicationContext(): Context = this
    override fun getMainLooper(): Looper = Looper.getMainLooper()

    override fun getAttributionSource(): AttributionSource =
        if (Build.VERSION.SDK_INT >= 31) {
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        } else {
            super.getAttributionSource()
        }

    // Hidden Context methods; declared by name so they override at runtime.
    @Suppress("unused") fun getBasePackageName(): String = SHELL_PACKAGE
    @Suppress("unused") fun getDisplayId(): Int = 0

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
