package com.portal.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class Snap(
    val id: Long, var title: String, val url: String, val at: Long, val type: String,
    val t: Double, val d: Double, val y: Int, val stream: String?, val referer: String?, val ua: String?
)

object Snaps {
    private var app: Context? = null
    private var p: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        p = app!!.getSharedPreferences("snaps", Context.MODE_PRIVATE)
        migrateLegacy()
    }

    private fun dir(): File = File(app!!.filesDir, "snaps").apply { mkdirs() }
    fun thumbFile(id: Long): File = File(dir(), "$id.jpg")
    private fun sessionFile(id: Long): File = File(dir(), "$id.bin")

    fun fmt(sec: Double): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun list(): MutableList<Snap> {
        val out = ArrayList<Snap>()
        try {
            val a = JSONArray(p!!.getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Snap(o.getLong("id"), o.optString("title"), o.optString("url"), o.optLong("at"), o.optString("type", "web"),
                    o.optDouble("t", -1.0), o.optDouble("d", 0.0), o.optInt("y"),
                    o.optString("stream").ifEmpty { null }, o.optString("referer").ifEmpty { null }, o.optString("ua").ifEmpty { null }))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun toJson(l: List<Snap>): JSONArray {
        val a = JSONArray()
        for (s in l) a.put(JSONObject().put("id", s.id).put("title", s.title).put("url", s.url).put("at", s.at)
            .put("type", s.type).put("t", s.t).put("d", s.d).put("y", s.y)
            .put("stream", s.stream ?: "").put("referer", s.referer ?: "").put("ua", s.ua ?: ""))
        return a
    }

    @Synchronized
    private fun write(l: List<Snap>) { p!!.edit().putString("list", toJson(l).toString()).apply() }

    @Synchronized
    fun add(s: Snap, session: Bundle?, thumb: Bitmap?) {
        val l = list()
        l.add(0, s)
        write(l)
        if (session != null) {
            try {
                val pc = Parcel.obtain()
                pc.writeBundle(session)
                sessionFile(s.id).writeBytes(pc.marshall())
                pc.recycle()
            } catch (e: Exception) { }
        }
        if (thumb != null) {
            try {
                val tw = 360
                val th = (tw.toFloat() * thumb.height / thumb.width).toInt().coerceAtLeast(1)
                val sb = Bitmap.createScaledBitmap(thumb, tw, th, true)
                FileOutputStream(thumbFile(s.id)).use { sb.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            } catch (e: Exception) { }
        }
    }

    fun session(id: Long): Bundle? {
        return try {
            val f = sessionFile(id)
            if (!f.exists()) return null
            val b = f.readBytes()
            val pc = Parcel.obtain()
            pc.unmarshall(b, 0, b.size)
            pc.setDataPosition(0)
            val bundle = pc.readBundle(Snaps::class.java.classLoader)
            pc.recycle()
            bundle
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun delete(id: Long) {
        write(list().filter { it.id != id })
        thumbFile(id).delete(); sessionFile(id).delete()
    }

    @Synchronized
    fun rename(id: Long, title: String) {
        val l = list()
        l.firstOrNull { it.id == id }?.title = title
        write(l)
    }

    fun metaJson(): JSONArray = toJson(list())

    @Synchronized
    fun importMeta(a: JSONArray) {
        val l = list()
        for (i in 0 until a.length()) {
            try {
                val o = a.getJSONObject(i)
                val id = o.getLong("id")
                if (l.none { it.id == id }) l.add(Snap(id, o.optString("title"), o.optString("url"), o.optLong("at"), o.optString("type", "web"),
                    o.optDouble("t", -1.0), o.optDouble("d", 0.0), o.optInt("y"),
                    o.optString("stream").ifEmpty { null }, o.optString("referer").ifEmpty { null }, o.optString("ua").ifEmpty { null }))
            } catch (e: Exception) { }
        }
        l.sortByDescending { it.at }
        write(l)
    }

    private fun migrateLegacy() {
        if (p!!.getBoolean("migrated", false)) return
        try {
            val raw = app!!.getSharedPreferences("s", Context.MODE_PRIVATE).getString("states", null)
            if (raw != null) {
                val a = JSONArray(raw)
                val l = list()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    l.add(Snap(o.optLong("at", System.currentTimeMillis()) + i, o.optString("title"), o.getString("url"),
                        o.optLong("at"), "web", o.optDouble("t", -1.0), 0.0, o.optInt("y"), null, null, null))
                }
                l.sortByDescending { it.at }
                write(l)
            }
        } catch (e: Exception) { }
        p!!.edit().putBoolean("migrated", true).apply()
    }
}
