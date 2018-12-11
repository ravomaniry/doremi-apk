package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.ui.views.FileExplorerAdapter
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.commonUtils.FileManager
import org.jetbrains.anko.alert


class ExplorerTabManager(
        private val mainContext: Context,
        private val explorerTab: View,
        private val editorViewModel: EditorViewModel,
        private val drawerManager: DrawerManager) {

    private lateinit var filesListAdapter: FileExplorerAdapter


    init {
        setListAdapter()
        observeFilesList()
        handleSearch()
    }


    private fun setListAdapter() {
        fun deleteFile(filename: String) {
            mainContext.alert("Supprimer '$filename'?") {
                yesButton {
                    FileManager.delete(filename)
                    editorViewModel.refreshFileList()
                }
                noButton { }
            }.show()
        }

        fun clickHandler(type: Int, filename: String) {
            when (type) {
                ActionTypes.OPEN -> {
                    drawerManager.closeDrawer()
                    editorViewModel.loadFile(filename) { mainContext.alert(it) }
                }
                ActionTypes.SHARE -> FileManager.share(filename, mainContext)?.run { mainContext.alert(this) }
                ActionTypes.DELETE -> deleteFile(filename)
            }
        }

        explorerTab.findViewById<RecyclerView>(explorer_files_list).apply {
            layoutManager = LinearLayoutManager(mainContext, LinearLayoutManager.VERTICAL, false)
            adapter = FileExplorerAdapter(listOf(), ::clickHandler)
                    .also { filesListAdapter = it }
        }
    }


    private fun observeFilesList() {
        editorViewModel.fileList.observe(mainContext as MainActivity, Observer {
            explorerTab.findViewById<RecyclerView>(explorer_files_list).apply {
                it?.run { filesListAdapter.update(it) }
            }
        })
    }


    private fun handleSearch() {
        with(explorerTab) {
            val editText = findViewById<EditText>(explorer_search_view)
                    .apply {
                        onChange {
                            filesListAdapter.apply {
                                filterValue = it
                                search()
                            }
                        }
                    }

            findViewById<ImageView>(explorer_reset_btn).setOnClickListener {
                filesListAdapter.search("")
                editText.setText("")
            }
        }
    }
}
