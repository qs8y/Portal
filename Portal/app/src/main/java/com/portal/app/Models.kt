package com.portal.app

import android.webkit.WebView
import java.util.concurrent.CopyOnWriteArrayList

data class Media(val url: String, val referer: String?, val ua: String)

class Tab(val web: WebView) {
    val captured = CopyOnWriteArrayList<Media>()
    var title: String = "New tab"
    var url: String = ""
    var vt: Double = -1.0
    var vd: Double = 0.0
}
