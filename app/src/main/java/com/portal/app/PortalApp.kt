package com.portal.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class PortalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("Portal crash " + Date() + "\nThread: " + t.name + "\n\n" + sw.toString())
            } catch (x: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }
}
