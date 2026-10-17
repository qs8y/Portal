package com.portal.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SavedStatesActivity : AppCompatActivity() {
    private var data: List<Snap> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: View

    private fun reload() {
        data = Snaps.list()
        adapter.notifyDataSetChanged()
        empty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Style.apply(this)
        super.onCreate(savedInstanceState)
        Snaps.init(this)
        setContentView(R.layout.activity_states)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)

        adapter = object : BaseAdapter() {
            override fun getCount() = data.size
            override fun getItem(p: Int): Any = data[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_state, parent, false)
                val s = data[p]
                val img = v.findViewById<ImageView>(R.id.stThumb)
                val f = Snaps.thumbFile(s.id)
                if (f.exists()) { img.setImageURI(Uri.fromFile(f)); img.visibility = View.VISIBLE } else img.visibility = View.GONE
                v.findViewById<TextView>(R.id.stTitle).text = s.title
                val where = if (s.type == "player") "App player" else (Uri.parse(s.url).host ?: s.url)
                v.findViewById<TextView>(R.id.stSub).text = where + "  \u2022  " + df.format(java.util.Date(s.at)) +
                    (if (s.t > 0) "  \u2022  " + Snaps.fmt(s.t) else "")
                return v
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val s = data.getOrNull(pos) ?: return@setOnItemClickListener
            startActivity(Intent(this, MainActivity::class.java).setAction("portal.open.state").putExtra("id", s.id))
            finish()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val s = data.getOrNull(pos) ?: return@setOnItemLongClickListener true
            MaterialAlertDialogBuilder(this).setTitle(s.title).setItems(arrayOf("Rename", "Delete")) { _, i ->
                if (i == 0) {
                    val et = EditText(this)
                    et.setText(s.title)
                    val box = FrameLayout(this)
                    val pad = (24 * resources.displayMetrics.density).toInt()
                    box.setPadding(pad, pad / 3, pad, 0)
                    box.addView(et)
                    MaterialAlertDialogBuilder(this).setTitle("Rename").setView(box)
                        .setPositiveButton("Save") { _, _ -> Snaps.rename(s.id, et.text.toString().ifBlank { s.title }); reload() }
                        .setNegativeButton("Cancel", null).show()
                } else {
                    Snaps.delete(s.id); reload()
                }
            }.show()
            true
        }
        reload()
    }
}
