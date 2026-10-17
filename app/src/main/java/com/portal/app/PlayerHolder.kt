package com.portal.app

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.HttpUrl.Companion.toHttpUrl

object PlayerHolder {
    var player: ExoPlayer? = null
    var title: String = ""
    var itemId: Long = -1
    var uri: String? = null
    var referer: String? = null
    var ua: String = "Portal"
    var pageUrl: String? = null
    private var appCtx: Context? = null

    fun start(ctx: Context, uri: String, referer: String?, ua: String, title: String, itemId: Long,
              pageUrl: String? = null, startMs: Long = 0L) {
        appCtx = ctx.applicationContext
        release()
        val app = ctx.applicationContext
        val b = ExoPlayer.Builder(app).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
        if (uri.startsWith("http")) {
            val dns = app.getSharedPreferences("s", Context.MODE_PRIVATE).getInt("dns", 0)
            val http = makeClient(dns).newBuilder().followRedirects(true).followSslRedirects(true).build()
            val f = OkHttpDataSource.Factory(http).setUserAgent(ua)
            if (referer != null) {
                val props = HashMap<String, String>()
                props["Referer"] = referer
                try { val h = referer.toHttpUrl(); props["Origin"] = h.scheme + "://" + h.host } catch (e: Exception) { }
                f.setDefaultRequestProperties(props)
            }
            b.setMediaSourceFactory(DefaultMediaSourceFactory(f))
        }
        val p = b.build()
        p.setMediaItem(MediaItem.fromUri(uri), startMs)
        p.prepare()
        p.playWhenReady = true
        player = p
        this.title = title
        this.itemId = itemId
        this.uri = uri
        this.referer = referer
        this.ua = ua
        this.pageUrl = pageUrl
        try { app.startService(Intent(app, PlaybackService::class.java)) } catch (e: Exception) { }
    }

    fun release() {
        appCtx?.let { try { it.stopService(Intent(it, PlaybackService::class.java)) } catch (e: Exception) { } }
        try { player?.release() } catch (e: Exception) { }
        player = null
        itemId = -1
    }
}
