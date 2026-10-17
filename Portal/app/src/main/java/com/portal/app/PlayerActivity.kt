package com.portal.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    companion object {
        fun start(ctx: Context, uri: String, referer: String?, ua: String, title: String, itemId: Long,
                  pageUrl: String? = null, startMs: Long = 0L) {
            PlayerHolder.start(ctx, uri, referer, ua, title, itemId, pageUrl, startMs)
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var pv: PlayerView
    private lateinit var top: View
    private lateinit var hint: TextView
    private lateinit var speedBtn: TextView
    private var floating = false
    private var volAcc = -1f
    private val ui = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var si = 1
    private var listener: Player.Listener? = null

    private val gd by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = PlayerHolder.player ?: return true
                val w = pv.width.toFloat()
                if (e.x < w / 3f) { p.seekTo(maxOf(0L, p.currentPosition - 10000L)); showHint("-10s") }
                else if (e.x > w * 2f / 3f) { p.seekTo(p.currentPosition + 10000L); showHint("+10s") }
                else if (p.isPlaying) p.pause() else p.play()
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlayerHolder.player == null) { finish(); return }
        setContentView(R.layout.activity_player)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())

        pv = findViewById(R.id.pv)
        top = findViewById(R.id.ptop)
        hint = findViewById(R.id.phint)
        speedBtn = findViewById(R.id.btnSpeed)
        pv.player = PlayerHolder.player
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis -> top.visibility = vis })

        speedBtn.setOnClickListener {
            si = (si + 1) % speeds.size
            val s = speeds[si]
            PlayerHolder.player?.setPlaybackSpeed(s)
            speedBtn.text = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"
        }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip(false) }
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnFloat).setOnClickListener { goFloating() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            val p = PlayerHolder.player
            val src = PlayerHolder.uri
            if (p != null && src != null) {
                Snaps.init(this)
                val now = System.currentTimeMillis()
                val pos = p.currentPosition / 1000.0
                Snaps.add(Snap(now, PlayerHolder.title.ifEmpty { "Video" }, PlayerHolder.pageUrl ?: "", now, "player",
                    pos, maxOf(p.duration, 0L) / 1000.0, 0, src, PlayerHolder.referer, PlayerHolder.ua), null, null)
                Toast.makeText(this, "Saved at " + Snaps.fmt(pos), Toast.LENGTH_LONG).show()
            }
        }

        val root = findViewById<TouchFrame>(R.id.proot)
        root.onTouch = { volAcc = -1f }
        root.onVSwipe = { left, dy -> gesture(left, dy) }

        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && PlayerHolder.itemId >= 0 &&
                    getSharedPreferences("s", Context.MODE_PRIVATE).getBoolean("delete_after", false)) {
                    val id = PlayerHolder.itemId
                    Downloads.items.firstOrNull { it.id == id }?.let { Downloads.delete(it) }
                    finish()
                }
            }
        }
        listener = l
        PlayerHolder.player?.addListener(l)
    }

    private fun showHint(s: String) {
        hint.text = s; hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint); ui.postDelayed(hideHint, 800)
    }

    private fun gesture(left: Boolean, dy: Float) {
        if (left) {
            val lp = window.attributes
            var b = lp.screenBrightness
            if (b < 0) b = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
            b = (b + dy * 1.3f).coerceIn(0.02f, 1f)
            lp.screenBrightness = b
            window.attributes = lp
            showHint("Brightness " + (b * 100).toInt() + "%")
        } else {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (volAcc < 0) volAcc = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            volAcc = (volAcc + dy * max * 1.3f).coerceIn(0f, max.toFloat())
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.toInt(), 0)
            showHint("Volume " + (volAcc / max * 100).toInt() + "%")
        }
    }

    private fun enterPip(silent: Boolean) {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (!silent) Toast.makeText(this, "Mini player isn't supported on this device", Toast.LENGTH_LONG).show()
            return
        }
        try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        catch (e: Exception) { if (!silent) Toast.makeText(this, "Can't open the mini player", Toast.LENGTH_LONG).show() }
    }

    private fun goFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for this app, then try again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        floating = true
        val svc = Intent(this, FloatingPlayerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        finish()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!floating && PlayerHolder.player?.isPlaying == true) enterPip(true)
    }

    override fun onPictureInPictureModeChanged(isIn: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(isIn, cfg)
        pv.useController = !isIn
        top.visibility = if (isIn) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        val screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        if (!floating && !inPip && screenOn) PlayerHolder.player?.pause()
    }

    override fun onDestroy() {
        listener?.let { PlayerHolder.player?.removeListener(it) }
        if (::pv.isInitialized) pv.player = null
        if (isFinishing && !floating) PlayerHolder.release()
        super.onDestroy()
    }
}
