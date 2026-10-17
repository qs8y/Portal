package com.portal.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class Recent(val url: String, var title: String, var t: Double, var y: Int, var at: Long)

object Recents {
    private var app: Context? = null
    private var p: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        p = app!!.getSharedPreferences("recents", Context.MODE_PRIVATE)
    }

    private fun dir(): File = File(app!!.filesDir, "recents").apply { mkdirs() }
    fun thumbFile(url: String): File = File(dir(), (url.hashCode().toLong() and 0xffffffffL).toString() + ".jpg")

    fun list(): MutableList<Recent> {
        val out = ArrayList<Recent>()
        try {
            val a = JSONArray(p!!.getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Recent(o.getString("u"), o.optString("t0"), o.optDouble("t", 0.0), o.optInt("y"), o.optLong("a")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun write(l: List<Recent>) {
        val a = JSONArray()
        for (r in l) a.put(JSONObject().put("u", r.url).put("t0", r.title).put("t", r.t).put("y", r.y).put("a", r.at))
        p!!.edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun touch(url: String, title: String, t: Double, y: Int) {
        val l = list()
        l.removeAll { it.url == url }
        l.add(0, Recent(url, title, t, y, System.currentTimeMillis()))
        while (l.size > 8) { val gone = l.removeAt(l.size - 1); thumbFile(gone.url).delete() }
        write(l)
    }

    @Synchronized
    fun remove(url: String) {
        write(list().filter { it.url != url })
        thumbFile(url).delete()
    }

    @Synchronized
    fun saveThumb(url: String, bmp: Bitmap) {
        if (list().none { it.url == url }) return
        try {
            val tw = 320
            val th = (tw.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
            val sb = Bitmap.createScaledBitmap(bmp, tw, th, true)
            FileOutputStream(thumbFile(url)).use { sb.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        } catch (e: Exception) { }
    }
}
