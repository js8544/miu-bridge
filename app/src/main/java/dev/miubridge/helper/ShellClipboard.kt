package dev.miubridge.helper

import android.content.ClipData
import android.os.IBinder

/**
 * Puts text on the clipboard as the shell user, through the clipboard service's binder. Used to type
 * into apps whose fields accessibility cannot reach: set the text, then press paste on the display.
 * The method's parameters changed across releases (calling package, attribution tag, user, device),
 * so they are filled by type.
 */
internal object ShellClipboard {
    private const val SHELL_PACKAGE = "com.android.shell"

    fun set(text: String) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        val service = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        val method = service.javaClass.methods.first { it.name == "setPrimaryClip" }
        val clip = ClipData.newPlainText("miu-bridge", text)
        var strings = 0
        val args = method.parameterTypes.map { type ->
            when (type) {
                ClipData::class.java -> clip
                String::class.java -> if (strings++ == 0) SHELL_PACKAGE else null // package, then attribution tag
                Int::class.javaPrimitiveType -> 0 // user 0 (and default device 0 on Android 14+)
                else -> null
            }
        }.toTypedArray()
        method.invoke(service, *args)
    }
}
