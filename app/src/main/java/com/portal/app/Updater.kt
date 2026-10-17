package com.portal.app

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Updater {
    class Info(val version: String, val url: String)

    private fun parts(v: String): List<Int> = v.trim().trimStart('v', 'V').split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Returns the new version if one exists, or null when up to date. Throws if the source can't be read. */
    fun check(ctx: Context, src: String): Info? {
        val client = makeClient(0).newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        val body = client.newCall(Request.Builder().url(src).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception(if (r.code == 404) "No release has been published yet" else "HTTP " + r.code)
            r.body?.string() ?: throw Exception("Empty reply")
        }
        val o = JSONObject(body)
        val version: String
        var url: String
        if (o.has("tag_name")) {
            version = o.getString("tag_name")
            url = o.optString("html_url")
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url", url); break }
            }
        } else {
            version = o.getString("versionName")
            url = o.getString("url")
        }
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        return if (newer(version, current)) Info(version.trimStart('v', 'V'), url) else null
    }
}
