package com.portal.app

import android.content.SharedPreferences
import org.json.JSONObject

/** Per-website overrides. A value of -1 (or missing) means "follow the global setting". */
object SiteCfg {
    private const val KEY = "site_cfg"

    fun get(p: SharedPreferences, host: String, key: String): Int {
        return try { JSONObject(p.getString(KEY, "{}") ?: "{}").optJSONObject(host)?.optInt(key, -1) ?: -1 } catch (e: Exception) { -1 }
    }

    fun set(p: SharedPreferences, host: String, key: String, v: Int) {
        try {
            val all = JSONObject(p.getString(KEY, "{}") ?: "{}")
            val o = all.optJSONObject(host) ?: JSONObject()
            if (v < 0) o.remove(key) else o.put(key, v)
            if (o.length() == 0) all.remove(host) else all.put(host, o)
            p.edit().putString(KEY, all.toString()).apply()
        } catch (e: Exception) { }
    }

    fun bool(p: SharedPreferences, host: String, key: String, global: Boolean): Boolean {
        val v = get(p, host, key)
        return if (v < 0) global else v == 1
    }

    fun dns(p: SharedPreferences, host: String): Int {
        val v = get(p, host, "dns")
        return if (v < 0) p.getInt("dns", 0) else v
    }
}
