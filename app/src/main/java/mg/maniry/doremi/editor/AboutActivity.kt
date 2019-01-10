package mg.maniry.doremi.editor


import android.graphics.Color
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.android.synthetic.main.changelog_item.view.*
import mg.maniry.doremi.R
import mg.maniry.doremi.commonUtils.changelog


class AboutActivity : AppCompatActivity() {
    private var activeTab = 0
    private val light = Color.rgb(197, 41, 37)
    private val dark = Color.rgb(190, 190, 190)
    private lateinit var logsRView: RecyclerView
    private lateinit var aboutBtn: LinearLayout
    private lateinit var changelogBtn: LinearLayout


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_activity)

        initTabs()
        handleClose()
    }


    private fun handleClose() {
        findViewById<ImageView>(R.id.dialog_close_btn).setOnClickListener {
            finish()
        }
    }


    private fun initTabs() {
        aboutBtn = findViewById<LinearLayout>(R.id.about_tab_sel).apply {
            setOnClickListener {
                if (activeTab != 0) {
                    activeTab = 0
                    updateTabs()
                }
            }
        }

        changelogBtn = findViewById<LinearLayout>(R.id.changelog_tab_sel).apply {
            setOnClickListener {
                if (activeTab != 1) {
                    activeTab = 1
                    updateTabs()
                }
            }
        }

        updateTabs()
        logsRView.apply {
            layoutManager = LinearLayoutManager(this@AboutActivity, LinearLayoutManager.VERTICAL, false)
            adapter = ChangelogAdapter()
        }
    }


    private fun updateTabs() {
        findViewById<TextView>(R.id.about_tab_tv).setTextColor(if (activeTab == 0) light else dark)
        aboutBtn.setBackgroundColor(if (activeTab == 0) light else dark)
        findViewById<TextView>(R.id.changelog_tab_tv).setTextColor(if (activeTab == 0) dark else light)
        changelogBtn.setBackgroundColor(if (activeTab == 0) dark else light)


        findViewById<LinearLayout>(R.id.about_cont).visibility = when (activeTab) {
            0 -> View.VISIBLE
            else -> View.GONE
        }

        logsRView = findViewById<RecyclerView>(R.id.changelog_cont).apply {
            visibility = when (activeTab) {
                0 -> View.GONE
                else -> View.VISIBLE
            }
        }
    }


    class ChangelogAdapter : RecyclerView.Adapter<ChangelogAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.changelog_item, parent, false))

        override fun getItemCount() = changelog.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.apply {
                versionName.text = changelog[position].version
                logDetails.text = changelog[position].logs.joinToString("\n") { "- $it" }
            }
        }

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val versionName: TextView = itemView.version_name_tv
            val logDetails: TextView = itemView.changelog_details
        }
    }
}