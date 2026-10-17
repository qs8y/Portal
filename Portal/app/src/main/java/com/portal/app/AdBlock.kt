package com.portal.app

import android.content.Context
import okhttp3.Request
import java.io.File

object AdBlock {
    private const val LIST_URL = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
    @Volatile private var extra: Set<String> = emptySet()

    private fun file(ctx: Context) = File(ctx.filesDir, "adblock.txt")

    private fun parse(text: String): Set<String> {
        val out = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val h = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]).lowercase()
            if (h.contains('.') && h != "localhost" && !h.contains('/')) out.add(h)
        }
        return out
    }

    fun load(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) extra = try { parse(f.readText()) } catch (e: Exception) { emptySet() }
    }

    fun lastUpdated(ctx: Context): Long = file(ctx).let { if (it.exists()) it.lastModified() else 0L }

    fun status(ctx: Context): String {
        val t = lastUpdated(ctx)
        if (t == 0L) return "Using the built-in list"
        return "Updated " + java.text.DateFormat.getDateInstance().format(java.util.Date(t)) + ", " + extra.size + " extra hosts"
    }

    fun update(ctx: Context, done: (Int, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                makeClient(0).newCall(Request.Builder().url(LIST_URL).build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP " + r.code)
                    val body = r.body?.string() ?: ""
                    val set = parse(body)
                    if (set.size < 100) throw Exception("The list looked empty")
                    file(app).writeText(body)
                    extra = set
                    done(set.size, null)
                }
            } catch (e: Exception) { done(0, e.message ?: "unknown error") }
        }.start()
    }

    private val hosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "adnxs.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com", "magsrv.com",
        "juicyads.com", "trafficjunky.com", "trafficjunky.net", "adsterra.com", "highperformanceformat.com",
        "onclickads.net", "adcash.com", "clickadu.com", "hilltopads.net", "a-ads.com", "mgid.com",
        "taboola.com", "outbrain.com", "rubiconproject.com", "pubmatic.com", "criteo.com", "adsco.re",
        "ad-maven.com", "admaven.com", "monetag.com", "bidgear.com", "tsyndicate.com", "realsrv.com",
        "acscdn.com", "ero-advertising.com", "plugrush.com", "trafficstars.com", "adtng.com",
        "clickaine.com", "popunderjs.com", "syndication.exoclick.com", "adskeeper.com", "revcontent.com",
        "yllix.com", "zeropark.com", "richads.com", "pushnami.com", "onesignal.com", "adspyglass.com",
        "adxpansion.com", "dolohen.com", "betteradsystem.com", "cdn4ads.com", "gadsme.com", "ad.gt"
    )

    fun blocked(host: String?): Boolean {
        if (host == null) return false
        var h = host.lowercase()
        while (true) {
            if (hosts.contains(h) || extra.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }
}
