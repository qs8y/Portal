package com.portal.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

class LibraryActivity : AppCompatActivity() {
    // kind: 0 = history, 1 = favorite, 2 = saved state
    class Row(val title: String, val sub: String, val url: String, val kind: Int, val id: Long, val entry: Entry?, val watched: Boolean)

    private var data: List<Row> = emptyList()
    private var showFavs = false
    private var query = ""
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: View
    private lateinit var tabs: TabLayout

    private fun matches(q: String, a: String, b: String) = a.lowercase().contains(q) || b.lowercase().contains(q)

    private fun reload() {
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        fun sub(e: Entry, tag: String?): String {
            val host = Uri.parse(e.url).host ?: e.url
            return host + "  \u2022  " + df.format(java.util.Date(e.at)) + (if (tag != null) "  \u2022  " + tag else "")
        }
        val rows = ArrayList<Row>()
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            val src = if (showFavs) Library.favs() else Library.history()
            for (e in src) rows.add(Row(e.title, sub(e, null), e.url, if (showFavs) 1 else 0, 0, e, e.watched))
        } else {
            for (e in Library.history()) if (matches(q, e.title, e.url)) rows.add(Row(e.title, sub(e, "History"), e.url, 0, 0, e, e.watched))
            for (e in Library.favs()) if (matches(q, e.title, e.url)) rows.add(Row(e.title, sub(e, "Favorite"), e.url, 1, 0, e, false))
            for (s in Snaps.list()) if (matches(q, s.title, s.url)) {
                val host = if (s.type == "player") "App player" else (Uri.parse(s.url).host ?: s.url)
                rows.add(Row(s.title, host + "  \u2022  " + df.format(java.util.Date(s.at)) + "  \u2022  Saved state", s.url, 2, s.id, null, false))
            }
        }
        data = rows
        adapter.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        tabs.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        Library.init(this)
        Snaps.init(this)
        setContentView(R.layout.activity_library)
        empty = findViewById(R.id.empty)
        tabs = findViewById(R.id.tabs)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        adapter = object : BaseAdapter() {
            override fun getCount() = data.size
            override fun getItem(p: Int): Any = data[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_lib, parent, false)
                val r = data[p]
                v.findViewById<TextView>(R.id.libTitle).text = (if (r.watched) "\u2713 " else "") + r.title
                v.findViewById<TextView>(R.id.libSub).text = r.sub
                return v
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = data.getOrNull(pos) ?: return@setOnItemClickListener
            if (r.kind == 2) startActivity(Intent(this, MainActivity::class.java).setAction("portal.open.state").putExtra("id", r.id))
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url), this, MainActivity::class.java))
            finish()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val r = data.getOrNull(pos) ?: return@setOnItemLongClickListener true
            val e = r.entry
            val opts: Array<String> = when {
                r.kind == 2 -> arrayOf("Delete saved state")
                r.kind == 1 -> arrayOf("Remove from favorites")
                else -> arrayOf(if (r.watched) "Mark as not watched" else "Mark as watched",
                    if (Library.isFav(r.url)) "Remove from favorites" else "Add to favorites", "Delete from history")
            }
            MaterialAlertDialogBuilder(this).setTitle(r.title).setItems(opts) { _, i ->
                if (r.kind == 2) Snaps.delete(r.id)
                else if (r.kind == 1) Library.removeFav(r.url)
                else if (i == 0) Library.setWatched(r.url, !r.watched)
                else if (i == 1) { if (e != null) Library.toggleFav(e.url, e.title) }
                else Library.removeHistory(r.url)
                reload()
            }.show()
            true
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showFavs = tab.position == 1; reload() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        findViewById<EditText>(R.id.libSearch).doAfterTextChanged { query = it?.toString() ?: ""; reload() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (query.isNotBlank()) { Toast.makeText(this, "Clear the search first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            MaterialAlertDialogBuilder(this).setMessage(if (showFavs) "Clear all favorites?" else "Clear all history?")
                .setPositiveButton("Clear") { _, _ -> if (showFavs) Library.clearFavs() else Library.clearHistory(); reload() }
                .setNegativeButton("Cancel", null).show()
        }
        reload()
    }
}
