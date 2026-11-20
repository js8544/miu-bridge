package dev.miubridge.server

import android.content.Context
import dev.miubridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Tells the owner when a newer release exists. At most once a day it asks GitHub for the latest
 * release of [BuildConfig.RELEASES_REPO] and compares its tag with this build's version; the owner
 * downloads and installs it themselves from the release page. Nothing about the phone is sent, and a
 * build with an empty `releasesRepo` never asks.
 */
class Updates(context: Context) {
    class Release(val version: String, val page: String)

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val http = OkHttpClient()

    /** The newer release, if there is one; null when up to date or when it could not be found out. */
    suspend fun newer(now: Long = System.currentTimeMillis()): Release? {
        if (BuildConfig.RELEASES_REPO.isEmpty()) return null
        if (now - prefs.getLong("checked_at", 0) >= CHECK_EVERY_MS) fetch()?.let {
            prefs.edit().putLong("checked_at", now).putString("version", it.version).putString("page", it.page).apply()
        }
        val version = prefs.getString("version", null) ?: return null
        val page = prefs.getString("page", null) ?: return null
        return if (isNewer(version, BuildConfig.VERSION_NAME)) Release(version, page) else null
    }

    private suspend fun fetch(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.RELEASES_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "miu-bridge/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val page = json.optString("html_url")
                val version = json.optString("tag_name").removePrefix("v")
                // Only a page on GitHub is ever opened, whatever the answer says.
                if (version.isEmpty() || !page.startsWith("https://github.com/")) null else Release(version, page)
            }
        }.getOrNull()
    }

    companion object {
        private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L

        /** Whether [latest] is a later version than [current]; both are dotted numbers, "v" allowed. */
        fun isNewer(latest: String, current: String): Boolean {
            val a = parts(latest) ?: return false
            val b = parts(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(version: String): List<Int>? {
            val numbers = version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() }
            return if (numbers.any { it == null }) null else numbers.filterNotNull()
        }
    }
}
