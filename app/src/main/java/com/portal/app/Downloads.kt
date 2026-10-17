package com.portal.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Variant(val url: String, val label: String, val bw: Long)

fun makeClient(mode: Int): OkHttpClient {
    val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    if (mode == 0) return base.build()
    val (url, ips) = when (mode) {
        1 -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
        2 -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
        3 -> "https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15")
        else -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")
    }
    val dns = DnsOverHttps.Builder().client(OkHttpClient()).url(url.toHttpUrl())
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).build()
    return base.dns(dns).build()
}

fun ensureChannel(c: Context) {
    if (Build.VERSION.SDK_INT >= 26)
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
}

class Item(val id: Long, val title: String, val kind: String, val url: String, val referer: String?, val ua: String) {
    @Volatile var status = "queued"
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var bytes = 0L
    @Volatile var error = ""
    @Volatile var uri: String? = null
    @Volatile var where = ""
    @Volatile var retries = 0
    val pct: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
}

object Hls {
    class Seg(val url: String, val key: String?, val iv: String?, val seq: Long)

    fun req(url: String, referer: String?, ua: String): Request {
        val b = Request.Builder().url(url).header("User-Agent", ua)
        if (referer != null) {
            b.header("Referer", referer)
            runCatching { val h = referer.toHttpUrl(); b.header("Origin", "${h.scheme}://${h.host}") }
        }
        return b.build()
    }

    fun text(http: OkHttpClient, url: String, referer: String?, ua: String): String =
        http.newCall(req(url, referer, ua)).execute().use {
            if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
            it.body!!.string()
        }

    fun bytes(http: OkHttpClient, url: String, referer: String?, ua: String): ByteArray {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return http.newCall(req(url, referer, ua)).execute().use {
                    if (!it.isSuccessful) throw Exception("HTTP ${it.code}")
                    it.body!!.bytes()
                }
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("download error")
    }

    fun variants(base: String, text: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines()
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0L
            val res = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val abs = base.toHttpUrl().resolve(uri.trim())?.toString() ?: continue
            out.add(Variant(abs, if (res != null) "${res}p" else "${bw / 1000}kbps", bw))
        }
        return out.sortedByDescending { it.bw }
    }

    fun parse(playlistUrl: String, text: String): List<Seg> {
        if (text.contains("#EXT-X-MAP")) throw Exception("Unsupported stream format")
        val lines = text.lines()
        val seq0 = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val base = playlistUrl.toHttpUrl()
        val segs = ArrayList<Seg>()
        var key: String? = null
        var iv: String? = null
        for (raw in lines) {
            val l = raw.trim()
            if (l.startsWith("#EXT-X-KEY")) {
                if (l.contains("METHOD=NONE")) { key = null; iv = null }
                else {
                    if (!l.contains("METHOD=AES-128")) throw Exception("Unsupported encryption")
                    key = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { base.resolve(it)?.toString() }
                    iv = Regex("IV=(0x[0-9a-fA-F]+)").find(l)?.groupValues?.get(1)
                }
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val abs = base.resolve(l)?.toString() ?: continue
                segs.add(Seg(abs, key, iv, seq0 + segs.size))
            }
        }
        return segs
    }

    fun fetch(http: OkHttpClient, s: Seg, keys: Map<String, ByteArray>, referer: String?, ua: String): ByteArray {
        val data = bytes(http, s.url, referer, ua)
        val k = s.key ?: return data
        val ivb = ByteArray(16)
        val hex = s.iv
        if (hex != null) {
            val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            for (j in 0 until 16) ivb[j] = h.substring(j * 2, j * 2 + 2).toInt(16).toByte()
        } else {
            var v = s.seq
            for (j in 15 downTo 8) { ivb[j] = (v and 0xff).toByte(); v = v shr 8 }
        }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys[k]!!, "AES"), IvParameterSpec(ivb))
        return c.doFinal(data)
    }
}

object Downloads {
    private lateinit var app: Context
    private var ready = false
    val items = CopyOnWriteArrayList<Item>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val exec = Executors.newFixedThreadPool(2)
    private val running = HashSet<Long>()
    private val ctl = ConcurrentHashMap<Long, Int>() // 1 = pause, 2 = cancel

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        ready = true
        try {
            val arr = JSONArray(app.getSharedPreferences("dl", Context.MODE_PRIVATE).getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val x = Item(o.getLong("id"), o.getString("title"), o.getString("kind"), o.getString("url"),
                    o.optString("referer").ifEmpty { null }, o.optString("ua"))
                x.status = o.optString("status", "paused")
                x.done = o.optLong("done"); x.total = o.optLong("total"); x.bytes = o.optLong("bytes")
                x.error = o.optString("error"); x.uri = o.optString("uri").ifEmpty { null }; x.where = o.optString("where")
                if (x.status == "queued" || x.status == "downloading" || x.status == "converting") x.status = "paused"
                items.add(x)
            }
        } catch (e: Exception) { }
    }

    fun addListener(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { for (l in listeners) l() }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for (x in items) {
            arr.put(JSONObject().put("id", x.id).put("title", x.title).put("kind", x.kind).put("url", x.url)
                .put("referer", x.referer ?: "").put("ua", x.ua).put("status", x.status).put("done", x.done)
                .put("total", x.total).put("bytes", x.bytes).put("error", x.error).put("uri", x.uri ?: "")
                .put("where", x.where))
        }
        app.getSharedPreferences("dl", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    fun httpClient(): OkHttpClient =
        makeClient(app.getSharedPreferences("s", Context.MODE_PRIVATE).getInt("dns", 0))
            .newBuilder().followRedirects(true).followSslRedirects(true).readTimeout(30, TimeUnit.SECONDS).build()

    private fun dir(): File = File(app.filesDir, "dl").apply { mkdirs() }

    private fun ensureService() {
        try { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) } catch (e: Exception) { }
    }

    // ----- public controls -----
    fun enqueue(title: String, kind: String, url: String, referer: String?, ua: String): Item {
        val x = Item(System.currentTimeMillis(), title, kind, url, referer, ua)
        items.add(0, x); save(); fire(); start(x)
        return x
    }

    private fun start(x: Item) {
        ensureService()
        pump()
    }

    @Synchronized
    private fun pump() {
        while (running.size < 2) {
            val x = items.firstOrNull { it.status == "queued" && !running.contains(it.id) } ?: return
            running.add(x.id)
            exec.execute {
                try { run(x) } catch (t: Throwable) { }
                finally {
                    synchronized(this) { running.remove(x.id) }
                    pump()
                }
            }
        }
    }

    fun moveTop(x: Item) { if (items.remove(x)) { items.add(0, x); save(); fire(); pump() } }
    fun moveBottom(x: Item) { if (items.remove(x)) { items.add(x); save(); fire(); pump() } }

    fun storageUsed(): Long {
        var total = 0L
        dir().listFiles()?.forEach { total += it.length() }
        for (x in items) {
            val u = x.uri
            if (x.status == "done" && u != null) {
                try {
                    app.contentResolver.query(Uri.parse(u), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) total += c.getLong(0)
                    }
                } catch (e: Exception) { }
            }
        }
        return total
    }

    fun pause(x: Item) {
        ctl[x.id] = 1
        if (x.status == "queued") { x.status = "paused"; save(); fire() }
    }

    fun resume(x: Item) {
        if (x.status == "paused" || x.status == "failed") {
            ctl.remove(x.id); x.retries = 0; x.status = "queued"; x.error = ""; save(); fire(); start(x)
        }
    }

    fun cancel(x: Item) {
        ctl[x.id] = 2
        if (x.status != "downloading" && x.status != "converting") cleanup(x)
    }

    fun delete(x: Item) {
        x.uri?.let { try { app.contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { } }
        cleanup(x)
    }

    private fun cleanup(x: Item) {
        File(dir(), "${x.id}.part").delete()
        File(dir(), "${x.id}.mp4").delete()
        items.remove(x); ctl.remove(x.id); save(); fire()
    }

    private fun stopped(x: Item): Boolean {
        when (ctl[x.id]) {
            1 -> { x.status = "paused"; save(); fire(); return true }
            2 -> { cleanup(x); return true }
        }
        return false
    }

    private fun wifiBlocked(): Boolean {
        if (!app.getSharedPreferences("s", Context.MODE_PRIVATE).getBoolean("wifi_only", false)) return false
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun waitWifi(x: Item): Boolean {
        if (!wifiBlocked()) return false
        x.status = "paused"; x.error = "Waiting for Wi-Fi"; save(); fire()
        return true
    }

    // ----- worker -----
    private fun run(x: Item) {
        if (x.status != "queued") return
        try {
            x.status = "downloading"; x.error = ""; save(); fire()
            val http = httpClient()
            val tmp = File(dir(), "${x.id}.part")
            if (waitWifi(x)) return
            val finished = if (x.kind == "mp4") dlMp4(x, http, tmp) else dlHls(x, http, tmp)
            if (!finished) return
            x.status = "converting"; save(); fire()
            finish(x, tmp)
        } catch (e: Exception) {
            if (ctl[x.id] == 2) return
            val msg = e.message ?: e.javaClass.simpleName
            if (ctl[x.id] != 1 && x.retries < 3 && !msg.startsWith("Unsupported")) {
                x.retries = x.retries + 1
                x.error = "Retrying (" + x.retries + "/3)..."
                x.status = "queued"; save(); fire()
                try { Thread.sleep(3000L * x.retries) } catch (ie: InterruptedException) { }
                return
            }
            x.status = "failed"; x.error = msg
            save(); fire(); notifyDone(x, false)
        }
    }

    private fun dlHls(x: Item, http: OkHttpClient, tmp: File): Boolean {
        val segs = Hls.parse(x.url, Hls.text(http, x.url, x.referer, x.ua))
        if (segs.isEmpty()) throw Exception("No segments found")
        x.total = segs.size.toLong()
        val keys = HashMap<String, ByteArray>()
        for (k in segs.mapNotNull { it.key }.distinct()) keys[k] = Hls.bytes(http, k, x.referer, x.ua)
        val start = x.done.toInt().coerceIn(0, segs.size)
        if (start == 0 || !tmp.exists()) { tmp.delete(); x.done = 0; x.bytes = 0 }
        else RandomAccessFile(tmp, "rw").use { it.setLength(x.bytes) }
        val fos = FileOutputStream(tmp, true)
        val pool = Executors.newFixedThreadPool(4)
        try {
            var i = x.done.toInt()
            while (i < segs.size) {
                if (stopped(x)) return false
                if (waitWifi(x)) return false
                val chunk = segs.subList(i, minOf(i + 8, segs.size))
                val fs = chunk.map { s -> pool.submit(Callable { Hls.fetch(http, s, keys, x.referer, x.ua) }) }
                for (f in fs) {
                    val d = try { f.get() } catch (e: ExecutionException) { throw (e.cause as? Exception) ?: e }
                    fos.write(d)
                    x.bytes = x.bytes + d.size
                    x.done = x.done + 1
                }
                fos.flush()
                i += chunk.size
                fire()
                if (x.done % 16 == 0L) save()
            }
        } finally { pool.shutdownNow(); fos.close() }
        return true
    }

    private fun dlMp4(x: Item, http: OkHttpClient, tmp: File): Boolean {
        var have = if (tmp.exists()) tmp.length() else 0L
        val b = Hls.req(x.url, x.referer, x.ua).newBuilder()
        if (have > 0) b.header("Range", "bytes=$have-")
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            if (r.code != 206) have = 0
            val body = r.body!!
            x.total = if (r.code == 206) have + body.contentLength() else body.contentLength()
            x.done = have
            FileOutputStream(tmp, have > 0).use { o ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(65536)
                    var n = inp.read(buf)
                    var c = 0
                    while (n >= 0) {
                        o.write(buf, 0, n)
                        x.done = x.done + n
                        c++
                        if (c % 32 == 0) { fire(); if (stopped(x) || waitWifi(x)) return false }
                        n = inp.read(buf)
                    }
                }
            }
        }
        return true
    }

    private fun remux(ts: File, mp4: File) {
        val ex = MediaExtractor()
        ex.setDataSource(ts.absolutePath)
        val mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i); map[i] = mux.addTrack(f)
                }
            }
            if (map.isEmpty()) throw Exception("No tracks")
            mux.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, ex.sampleTime, flags)
                mux.writeSampleData(map[ex.sampleTrackIndex]!!, buf, info)
                ex.advance()
            }
            mux.stop()
        } finally {
            try { mux.release() } catch (e: Exception) { }
            ex.release()
        }
    }

    private fun finish(x: Item, tmp: File) {
        var out = tmp
        var ext = "mp4"
        var mime = "video/mp4"
        if (x.kind == "hls") {
            val mp4 = File(dir(), "${x.id}.mp4")
            try { remux(tmp, mp4); tmp.delete(); out = mp4 }
            catch (e: Exception) { mp4.delete(); ext = "ts"; mime = "video/mp2t"; x.error = "MP4 conversion failed, saved as .ts" }
        }
        if (ctl[x.id] == 2) { cleanup(x); return }
        val (where, uri) = store(out, "${x.title}.$ext", mime)
        out.delete()
        x.where = where; x.uri = uri.ifEmpty { null }; x.done = x.total; x.status = "done"
        save(); fire(); notifyDone(x, true)
    }

    private fun store(src: File, name: String, mime: String): Pair<String, String> {
        val where = "Downloads/animepahe/$name"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/animepahe")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("Can't create file")
            app.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o, 65536) } }
            return Pair(where, uri.toString())
        }
        val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "animepahe").apply { mkdirs() }
        var f = File(d, name)
        if (f.exists()) f = File(d, name.substringBeforeLast('.') + "_" + System.currentTimeMillis() + "." + name.substringAfterLast('.'))
        src.inputStream().use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 65536) } }
        val latch = CountDownLatch(1)
        var u = ""
        MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), arrayOf(mime)) { _, uri ->
            u = uri?.toString() ?: ""; latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return Pair(where, u)
    }

    private fun notifyDone(x: Item, ok: Boolean) {
        ensureChannel(app)
        val pi = PendingIntent.getActivity(app, 0, Intent(app, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(app, "dl").setContentTitle(x.title)
            .setContentText(if (ok) "Download complete" else "Download failed: ${x.error}")
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true).setContentIntent(pi).build()
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((3000 + x.id % 1000).toInt(), n)
    }
}

class DownloadService : Service() {
    private var last = 0L
    private val l: () -> Unit = { refresh(false) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Downloads.init(this)
        Downloads.addListener(l)
        refresh(true)
        return START_NOT_STICKY
    }

    private fun refresh(force: Boolean) {
        val active = Downloads.items.filter { it.status == "queued" || it.status == "downloading" || it.status == "converting" }
        val now = System.currentTimeMillis()
        if (!force && active.isNotEmpty() && now - last < 800) return
        last = now
        ensureChannel(this)
        val cur = active.firstOrNull { it.status != "queued" } ?: active.firstOrNull()
        val text = if (cur == null) "Finishing..." else if (cur.status == "converting") "Converting ${cur.title}" else "${cur.title}  ${cur.pct}%"
        val pi = PendingIntent.getActivity(this, 0, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "dl")
            .setContentTitle(if (active.size > 1) "Downloading ${active.size} videos" else "Downloading")
            .setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi)
            .setProgress(100, cur?.pct ?: 0, cur == null || cur.status == "converting").build()
        startForeground(1001, n)
        if (active.isEmpty()) { stopForeground(true); stopSelf() }
    }

    override fun onDestroy() { Downloads.removeListener(l); super.onDestroy() }
}
