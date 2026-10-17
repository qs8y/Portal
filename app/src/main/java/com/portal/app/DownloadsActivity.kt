package com.portal.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class DownloadsActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private var pending = false
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: View
    private val l: () -> Unit = {
        if (!pending) {
            pending = true
            h.postDelayed({ pending = false; refresh() }, 250)
        }
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        Thread {
            val bytes = Downloads.storageUsed()
            runOnUiThread {
                findViewById<TextView>(R.id.storage).text = "Storage used: " + android.text.format.Formatter.formatFileSize(this, bytes)
            }
        }.start()
        empty.visibility = if (Downloads.items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        Downloads.init(this)
        setContentView(R.layout.activity_downloads)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        empty = findViewById(R.id.empty)

        adapter = object : BaseAdapter() {
            override fun getCount() = Downloads.items.size
            override fun getItem(p: Int): Any = Downloads.items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_download, parent, false)
                val x = Downloads.items.getOrNull(p) ?: return v
                v.findViewById<TextView>(R.id.title).text = x.title
                val bar = v.findViewById<LinearProgressIndicator>(R.id.bar)
                val st = v.findViewById<TextView>(R.id.status)
                val b1 = v.findViewById<MaterialButton>(R.id.b1)
                val b2 = v.findViewById<MaterialButton>(R.id.b2)
                val indeterminate = x.status == "converting" || x.status == "queued"
                if (indeterminate) bar.isIndeterminate = true
                else { bar.isIndeterminate = false; bar.setProgressCompat(if (x.status == "done") 100 else x.pct, false) }
                st.text = when (x.status) {
                    "queued" -> "Waiting..."
                    "downloading" -> "Downloading ${x.pct}%"
                    "paused" -> "Paused at ${x.pct}%" + (if (x.error.isNotEmpty()) " • ${x.error}" else "")
                    "converting" -> "Converting to MP4..."
                    "failed" -> "Failed: ${x.error}"
                    else -> "Saved to ${x.where}" + if (x.error.isNotEmpty()) "\n${x.error}" else ""
                }
                fun set(b: MaterialButton, t: String?, a: () -> Unit) {
                    if (t == null) b.visibility = View.GONE
                    else { b.visibility = View.VISIBLE; b.text = t; b.setOnClickListener { a() } }
                }
                when (x.status) {
                    "downloading", "queued" -> { set(b1, "Pause") { Downloads.pause(x) }; set(b2, "Cancel") { confirm("Cancel and remove this download?") { Downloads.cancel(x) } } }
                    "paused", "failed" -> { set(b1, "Resume") { Downloads.resume(x) }; set(b2, "Remove") { confirm("Remove this download?") { Downloads.cancel(x) } } }
                    "done" -> { set(b1, "Play") { play(x) }; set(b2, "Delete") { confirm("Delete the video file too?") { Downloads.delete(x) } } }
                    else -> { set(b1, null) {}; set(b2, null) {} }
                }
                return v
            }
        }
        val lv = findViewById<ListView>(R.id.list)
        lv.adapter = adapter
        lv.setOnItemLongClickListener { _, _, pos, _ -> Downloads.items.getOrNull(pos)?.let { longPress(it) }; true }
    }

    override fun onStart() { super.onStart(); Downloads.addListener(l); refresh() }
    override fun onStop() { super.onStop(); Downloads.removeListener(l) }

    private fun confirm(msg: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> action() }.setNegativeButton("No", null).show()
    }

    private fun play(x: Item) {
        val u = x.uri
        if (u == null) { Toast.makeText(this, "Open Downloads/animepahe in your file manager", Toast.LENGTH_LONG).show(); return }
        PlayerActivity.start(this, u, null, "Portal", x.title, x.id)
    }

    private fun playExternal(x: Item) {
        val u = x.uri ?: return
        val mime = if (x.where.contains(".ts")) "video/mp2t" else "video/mp4"
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Play with"))
        } catch (e: Exception) { Toast.makeText(this, "No video player found", Toast.LENGTH_LONG).show() }
    }

    private fun longPress(x: Item) {
        val opts = ArrayList<String>()
        opts.add("Move to top"); opts.add("Move to bottom")
        if (x.status == "done" && x.uri != null) opts.add("Play with another app")
        MaterialAlertDialogBuilder(this).setTitle(x.title).setItems(opts.toTypedArray()) { _, i ->
            when (i) {
                0 -> Downloads.moveTop(x)
                1 -> Downloads.moveBottom(x)
                else -> playExternal(x)
            }
        }.show()
    }
}
