package com.portal.app

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.ui.PlayerView

class FloatingPlayerService : Service() {
    private var wm: WindowManager? = null
    private var root: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        ensureChannel(this)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "dl").setContentTitle("Floating player")
            .setContentText(PlayerHolder.title).setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setContentIntent(pi).build()
        startForeground(1002, n)
        if (root == null) show()
        return START_NOT_STICKY
    }

    private fun show() {
        val p = PlayerHolder.player
        if (p == null) { stopSelf(); return }
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_App)).inflate(R.layout.floating_player, null)
        root = v
        val pv = v.findViewById<PlayerView>(R.id.fpv)
        pv.player = p

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dp(260), dp(146), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16); lp.y = dp(120)

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        pv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    w.updateViewLayout(v, lp)
                }
            }
            true
        }
        var sw = 0
        v.findViewById<View>(R.id.fResize).setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sw = lp.width }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw + (e.rawX - sx)).toInt().coerceIn(dp(140), dp(700))
                    lp.height = lp.width * 9 / 16
                    w.updateViewLayout(v, lp)
                }
            }
            true
        }
        v.findViewById<TextView>(R.id.fPlay).setOnClickListener { if (p.isPlaying) p.pause() else p.play() }
        v.findViewById<View>(R.id.fExpand).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stopSelf()
        }
        v.findViewById<View>(R.id.fClose).setOnClickListener { PlayerHolder.release(); stopSelf() }
        w.addView(v, lp)
    }

    override fun onDestroy() {
        try { root?.let { wm?.removeView(it) } } catch (e: Exception) { }
        root = null
        super.onDestroy()
    }
}
