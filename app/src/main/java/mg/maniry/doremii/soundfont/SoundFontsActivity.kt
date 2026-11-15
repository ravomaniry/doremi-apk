package mg.maniry.doremii.soundfont

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import mg.maniry.doremii.DoremiApp
import mg.maniry.doremii.R
import mg.maniry.doremii.commonUtils.navigateBack
import java.util.Locale

class SoundFontsActivity : AppCompatActivity() {
    private val repository by lazy { DoremiApp.from(this).soundFonts }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) repository.import(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.soundfonts_activity)
        findViewById<MaterialToolbar>(R.id.screen_toolbar).navigateBack(this)
        findViewById<View>(R.id.import_soundfont).setOnClickListener {
            openDocument.launch(arrayOf("audio/*", "application/octet-stream", "*/*"))
        }
        findViewById<View>(R.id.browse_artifacts).setOnClickListener {
            openLink("https://musical-artifacts.com/artifacts?formats=sf2")
        }
        findViewById<View>(R.id.browse_general_user).setOnClickListener {
            openLink("https://www.schristiancollins.com/generaluser")
        }
        repository.catalog.observe(this) { render() }
        repository.imports.observe(this) { render() }
        repository.messages.observe(this) { message ->
            val text = message?.text ?: return@observe
            if (text.isNotBlank()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val installed = repository.catalog.value.orEmpty()
        val imports = repository.imports.value.orEmpty()
        val installedContainer = findViewById<LinearLayout>(R.id.installed_container)
        val progressContainer = findViewById<LinearLayout>(R.id.import_progress)
        val installedRows = installed.map { font ->
            val primary = if (font.active) {
                RowAction(getString(R.string.soundfonts_default), null, filled = false)
            } else {
                RowAction(getString(R.string.soundfonts_use_default), { repository.activate(font.id) }, filled = false)
            }
            val secondary = if (font.canDelete) {
                RowAction(getString(R.string.soundfonts_delete), { repository.delete(font.id) }, filled = false)
            } else {
                null
            }
            row(font.displayName, formatSize(font.sizeBytes), null, primary, secondary)
        }
        val progressRows = imports.map { progress ->
            row(
                progress.label,
                "",
                progress,
                RowAction(getString(R.string.text_cancel), { repository.cancel(progress.key) }, filled = false),
                null,
            )
        }
        showRows(installedContainer, installedRows)
        showRows(progressContainer, progressRows)
        progressContainer.visibility = if (progressRows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showRows(container: LinearLayout, rows: List<View>) {
        container.removeAllViews()
        rows.forEachIndexed { index, row ->
            if (index > 0) container.addView(hairline())
            container.addView(row)
        }
    }

    private fun hairline(): View {
        val height = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
            setBackgroundColor(getColor(R.color.gray))
        }
    }

    private fun row(
        name: String,
        detail: String,
        progress: ImportProgress?,
        primary: RowAction?,
        secondary: RowAction?,
    ): View {
        val view = layoutInflater.inflate(R.layout.soundfont_row, null)
        view.findViewById<TextView>(R.id.soundfont_name).text = name
        view.findViewById<TextView>(R.id.soundfont_detail).apply {
            text = detail
            visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        }
        val bar = view.findViewById<ProgressBar>(R.id.soundfont_progress)
        if (progress == null) {
            bar.visibility = View.GONE
        } else {
            bar.visibility = View.VISIBLE
            if (progress.checking || progress.total <= 0) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                bar.max = 1000
                bar.progress = ((progress.received * 1000) / progress.total).toInt().coerceIn(0, 1000)
            }
        }
        view.findViewById<MaterialButton>(R.id.soundfont_action).apply {
            if (primary == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = primary.label
                isEnabled = primary.onClick != null
                setOnClickListener { primary.onClick?.invoke() }
                applyLook(primary.filled)
            }
        }
        view.findViewById<MaterialButton>(R.id.soundfont_secondary).apply {
            if (secondary == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = secondary.label
                isEnabled = secondary.onClick != null
                setOnClickListener { secondary.onClick?.invoke() }
                applyLook(secondary.filled)
            }
        }
        return view
    }

    private fun MaterialButton.applyLook(filled: Boolean) {
        val primary = context.getColor(R.color.colorPrimary)
        val white = context.getColor(R.color.white)
        val disabled = context.getColor(R.color.dark_gray)
        if (filled) {
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(disabled, primary),
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(white, white),
                )
            )
            strokeWidth = 0
        } else {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(disabled, primary),
                )
            )
            strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            strokeColor = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(disabled, primary),
            )
        }
    }

    private fun openLink(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun formatSize(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", megabytes)
    }

    private data class RowAction(
        val label: String,
        val onClick: (() -> Unit)?,
        val filled: Boolean,
    )
}
