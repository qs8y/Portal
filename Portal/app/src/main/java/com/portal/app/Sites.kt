package com.portal.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Sites {
    fun load(p: SharedPreferences): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val a = JSONArray(p.getString("sites", null) ?: p.getString("custom_sites", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Pair(o.getString("n"), o.getString("u"))) }
        } catch (e: Exception) { }
        return out
    }

    fun save(p: SharedPreferences, l: List<Pair<String, String>>) {
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("n", x.first).put("u", x.second))
        p.edit().putString("sites", a.toString()).apply()
    }
}
