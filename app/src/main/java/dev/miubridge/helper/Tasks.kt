package dev.miubridge.helper

import android.content.ComponentName
import android.os.Build

/** Root tasks across displays, read and moved through the hidden IActivityTaskManager. */
internal class Tasks {
    class Entry(val id: Int, val displayId: Int, val packages: List<String>)

    private val service: Any = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    private val modern = Build.VERSION.SDK_INT >= 31

    /** Root tasks, topmost first within each display. */
    fun list(): List<Entry> {
        val infos = call(if (modern) "getAllRootTaskInfos" else "getAllStackInfos") as List<*>
        return infos.filterNotNull().map { info ->
            val id = (info.field("taskId") ?: info.field("stackId")) as Int
            val packages = listOfNotNull(info.field("topActivity"), info.field("baseActivity"))
                .map { (it as ComponentName).packageName }
                .distinct()
            Entry(id, info.field("displayId") as Int, packages)
        }
    }

    fun moveToDisplay(taskId: Int, displayId: Int) {
        call(if (modern) "moveRootTaskToDisplay" else "moveStackToDisplay", taskId, displayId)
    }

    fun remove(taskId: Int) {
        call("removeTask", taskId)
    }

    private fun call(name: String, vararg ints: Int): Any? {
        val types = Array(ints.size) { Int::class.javaPrimitiveType }
        return service.javaClass.getMethod(name, *types).invoke(service, *ints.toTypedArray())
    }

    private fun Any.field(name: String): Any? =
        try { javaClass.getField(name).get(this) } catch (e: NoSuchFieldException) { null }
}
