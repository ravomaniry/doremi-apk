package mg.maniry.doremii.browser.uiManagers

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.ActionTypes
import mg.maniry.doremii.browser.BrowserActivity
import mg.maniry.doremii.browser.UploadSolfaActivity
import mg.maniry.doremii.browser.adapters.FileBrowserAdapter
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.editor.EditorActivity
import mg.maniry.doremii.editor.managers.onChange
import mg.maniry.doremii.omr.ScanSolfaActivity
import mg.maniry.doremii.settings.SettingsActivity


class FilesBrowserManager(
    val context: BrowserActivity, val mainView: View
) {

    private val emptyView = mainView.findViewById<TextView>(R.id.home_empty)
    private val listAdapter = FileBrowserAdapter(
        songs = listOf(),
        untitledLabel = context.getString(R.string.home_untitled),
        songsLabel = context.getString(R.string.home_songs),
        onClick = ::clickHandler,
        onVisibleCount = ::showEmptyState,
    )
    private val viewModel = context.viewModel!!


    init {
        initChrome()
        initFilesList()
        observeFilesList()
        handleSearch()
    }


    private fun initChrome() {
        mainView.findViewById<MaterialToolbar>(R.id.home_toolbar).apply {
            inflateMenu(R.menu.home_menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_settings) {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                    true
                } else {
                    false
                }
            }
        }
        mainView.findViewById<View>(R.id.home_new_song).setOnClickListener { createSong() }
        mainView.findViewById<View>(R.id.home_scan_song).setOnClickListener {
            context.startActivity(Intent(context, ScanSolfaActivity::class.java))
        }
    }


    private fun initFilesList() {
        mainView.findViewById<RecyclerView>(R.id.browser_files_list).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = listAdapter
        }
    }


    private fun observeFilesList() {
        viewModel.filesList.observe(context) {
            it?.run { listAdapter.update(it) }
        }
    }


    private fun showEmptyState(count: Int) {
        emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
        emptyView.setText(
            if (listAdapter.filterValue.isBlank()) R.string.home_empty else R.string.home_empty_search
        )
    }


    private fun clickHandler(type: Int, filename: String) {
        when (type) {
            ActionTypes.OPEN -> openFile(filename)
            ActionTypes.SHARE -> shareFile(filename)
            ActionTypes.DELETE -> deleteFile(filename)
            ActionTypes.PUBLISH -> publishFile(filename)
        }
    }


    private fun createSong() {
        context.startActivity(Intent(context, EditorActivity::class.java).apply {
            putExtra("createNew", true)
        })
    }


    private fun openFile(filename: String) {
        context.startActivity(Intent(context, EditorActivity::class.java).apply {
            putExtra("fileToOpen", filename)
        })
    }


    private fun shareFile(filename: String) {
        FileManager.share(filename, context)?.let { message ->
            MaterialAlertDialogBuilder(context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }


    private fun deleteFile(filename: String) {
        MaterialAlertDialogBuilder(context)
            .setMessage(context.getString(R.string.delete_confirm, filename))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                FileManager.delete(filename)
                listAdapter.deleteFile(filename)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    private fun publishFile(name: String) {
        val intent =
            Intent(context, UploadSolfaActivity::class.java).apply { putExtra("filename", name) }
        context.startActivity(intent)
    }


    private fun handleSearch() {
        mainView.findViewById<EditText>(R.id.local_search_view).onChange {
            listAdapter.apply {
                filterValue = it
                search()
            }
        }
    }
}
