package com.portal.app

import android.app.Activity
import android.content.Context
import com.google.android.material.color.DynamicColors

object Style {
    const val KEY = "style"

    fun index(ctx: Context): Int = ctx.getSharedPreferences("s", Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, 2)

    fun apply(a: Activity) {
        val i = index(a)
        a.setTheme(when (i) {
            1 -> R.style.Theme_Portal_Expressive
            2 -> R.style.Theme_Portal_Dark
            else -> R.style.Theme_Portal_Clean
        })
        if (i != 2) DynamicColors.applyToActivityIfAvailable(a)
    }
}
