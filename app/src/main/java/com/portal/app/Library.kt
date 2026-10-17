package com.portal.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Entry(val url: String, var title: String, var at: Long, var watched: Boolean = false)

object Library {
    private var p: SharedPreferences? = null

    fun init(ctx: Context) {
        if (p == null) p = ctx.applicationContext.getSharedPreferences("lib", Context.MODE_PRIVATE)
    }

    private fun load(key: String): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(p?.getString(key, "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Entry(o.getString("u"), o.optString("t"), o.optLong("a"), o.optBoolean("w")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun save(key: String, l: List<Entry>) {
        val a = JSONArray()
        for (e in l) a.put(JSONObject().put("u", e.url).put("t", e.title).put("a", e.at).put("w", e.watched))
        p?.edit()?.putString(key, a.toString())?.apply()
    }

    fun history(): List<Entry> = load("history")
    fun favs(): List<Entry> = load("favs")

    @Synchronized
    fun visit(url: String, title: String) {
        val l = load("history")
        val i = l.indexOfFirst { it.url == url }
        if (i >= 0) {
            val e = l.removeAt(i)
            e.title = title; e.at = System.currentTimeMillis()
            l.add(0, e)
        } else l.add(0, Entry(url, title, System.currentTimeMillis()))
        while (l.size > 300) l.removeAt(l.size - 1)
        save("history", l)
    }

    fun setWatched(url: String, w: Boolean) {
        val l = load("history")
        l.firstOrNull { it.url == url }?.watched = w
        save("history", l)
    }

    fun removeHistory(url: String) { save("history", load("history").filter { it.url != url }) }
    fun clearHistory() { save("history", emptyList()) }

    fun isFav(url: String): Boolean = load("favs").any { it.url == url }

    fun toggleFav(url: String, title: String): Boolean {
        val l = load("favs")
        val i = l.indexOfFirst { it.url == url }
        return if (i >= 0) { l.removeAt(i); save("favs", l); false }
        else { l.add(0, Entry(url, title, System.currentTimeMillis())); save("favs", l); true }
    }

    fun removeFav(url: String) { save("favs", load("favs").filter { it.url != url }) }
    fun clearFavs() { save("favs", emptyList()) }
}
