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
import com.google.android.material.textfield.TextInputEditText
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
        findViewById<View>(R.id.custom_url_download).setOnClickListener { downloadCustom() }
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
        repository.downloads.observe(this) { render() }
        repository.failures.observe(this) { render() }
        repository.messages.observe(this) { message ->
            val text = message?.text ?: return@observe
            if (text.isNotBlank()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadCustom() {
        val url = findViewById<TextInputEditText>(R.id.custom_url_input).text?.toString().orEmpty().trim()
        repository.download(url, displayNameFromUrl(url))
    }

    private fun render() {
        val installed = repository.catalog.value.orEmpty()
        val downloads = repository.downloads.value.orEmpty()
        val failures = repository.failures.value.orEmpty()
        val installedContainer = findViewById<LinearLayout>(R.id.installed_container)
        val curatedContainer = findViewById<LinearLayout>(R.id.curated_container)
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
        val curatedRows = mutableListOf<View>()
        SoundFontRepository.curated.forEach { item ->
            val alreadyInstalled = installed.any { it.sourceUrl == item.url }
            val progress = downloads.firstOrNull { it.key == item.url }
            val failure = failures.firstOrNull { it.key == item.url }
            if (alreadyInstalled && progress == null && failure == null) return@forEach
            val primary = if (progress != null) {
                RowAction(getString(R.string.text_cancel), { repository.cancel(item.url) }, filled = false)
            } else {
                RowAction(getString(R.string.soundfonts_download), { repository.download(item.url, item.name) }, filled = true)
            }
            val detail = if (progress == null && failure != null) failure.message else item.detail
            curatedRows += row(item.name, detail, progress, primary, null)
        }
        downloads.filter { progress -> SoundFontRepository.curated.none { it.url == progress.key } }
            .forEach { progress ->
                curatedRows += row(
                    progress.label,
                    "",
                    progress,
                    RowAction(getString(R.string.text_cancel), { repository.cancel(progress.key) }, filled = false),
                    null,
                )
            }
        failures.filter { failure ->
            SoundFontRepository.curated.none { it.url == failure.key } &&
                downloads.none { it.key == failure.key }
        }.forEach { failure ->
            val retry = if (failure.key.startsWith("http://") || failure.key.startsWith("https://")) {
                RowAction(
                    getString(R.string.soundfonts_download),
                    { repository.download(failure.key, failure.label) },
                    filled = true,
                )
            } else {
                null
            }
            curatedRows += row(failure.label, failure.message, null, retry, null)
        }
        showRows(installedContainer, installedRows)
        showRows(curatedContainer, curatedRows)
        findViewById<View>(R.id.lists_divider).visibility =
            if (installedRows.isNotEmpty() && curatedRows.isNotEmpty()) View.VISIBLE else View.GONE
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
        progress: DownloadProgress?,
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

    private fun displayNameFromUrl(url: String): String {
        val name = Uri.parse(url).lastPathSegment?.substringBeforeLast('.').orEmpty()
        return name.replace('_', ' ').replace('-', ' ').ifBlank { "SoundFont" }
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
