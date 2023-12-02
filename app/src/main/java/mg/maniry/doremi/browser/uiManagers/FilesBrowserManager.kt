package mg.maniry.doremi.browser.uiManagers

import android.arch.lifecycle.Observer
import android.content.Intent
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.BrowserActivity
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.browser.ActionTypes
import mg.maniry.doremi.browser.UploadSolfaActivity
import mg.maniry.doremi.editor.managers.onChange
import mg.maniry.doremi.browser.adapters.FileBrowserAdapter
import org.jetbrains.anko.alert


class FilesBrowserManager(
    val context: BrowserActivity, val mainView: View
) {

    private val listAdapter = FileBrowserAdapter(listOf(), ::clickHandler)
    private val viewModel = context.viewModel!!


    init {
        initFilesList()
        observeFilesList()
        handleSearch()
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


    private fun clickHandler(type: Int, filename: String) {
        when (type) {
            ActionTypes.OPEN -> openFile(filename)
            ActionTypes.SHARE -> shareFile(filename)
            ActionTypes.DELETE -> deleteFile(filename)
            ActionTypes.PUBLISH -> publishFile(filename)
        }
    }


    private fun openFile(filename: String) {
        val intent = Intent(context, EditorActivity::class.java).apply {
            putExtra("fileToOpen", filename)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        context.startActivity(intent)
    }


    private fun shareFile(filename: String) {
        FileManager.share(filename, context)?.run {
            context.alert(this)
        }
    }


    private fun deleteFile(filename: String) {
        context.alert("Supprimer '$filename'?") {
            yesButton {
                FileManager.delete(filename)
                listAdapter.deleteFile(filename)
            }
            noButton { }
        }.show()
    }


    private fun publishFile(name: String) {
        val intent =
            Intent(context, UploadSolfaActivity::class.java).apply { putExtra("filename", name) }
        context.startActivity(intent)
    }


    private fun handleSearch() {
        with(mainView) {
            val editText = findViewById<EditText>(R.id.local_search_view).apply {
                onChange {
                    listAdapter.apply {
                        filterValue = it
                        search()
                    }
                }
            }

            findViewById<ImageView>(R.id.browser_reset_search_btn).setOnClickListener {
                listAdapter.search("")
                editText.setText("")
            }
        }
    }
}