package mg.maniry.doremi.browser.uiManagers

import android.arch.lifecycle.Observer
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.BrowserActivity
import mg.maniry.doremi.browser.RemoteSolfa
import mg.maniry.doremi.browser.adapters.RemoteBrowserAdapter
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.commonUtils.Values


class RemoteBrowserManager(
        val context: BrowserActivity,
        val mainView: View,
        val remoteTab: View) {

    private val viewModel = context.viewModel!!
    private val net = context.networking
    private var isConnected = context.networking.isConnected()

    private val listAdapter = RemoteBrowserAdapter(::download)
    private val downloadLoader = View.inflate(context, R.layout.solfa_download_loader, null)
            .also { mainView.findViewById<FrameLayout>(R.id.main_parent).addView(it) }
    private val cancelDownloadBtn = downloadLoader.findViewById<Button>(R.id.cancel_solfa_dld_btn)
    private val downloadFilename = downloadLoader.findViewById<TextView>(R.id.downloading_filename)

    private var searchView = mainView.findViewById<EditText>(R.id.remote_search_view)
    private val searchBtn = mainView.findViewById<ImageView>(R.id.remote_search_btn)
    private val loader = remoteTab.findViewById<ProgressBar>(R.id.browser_loader)
    private val netStatusTv = remoteTab.findViewById<TextView>(R.id.browser_network_status)


    init {
        initFilesList()
        observeLoaders()
        listenClicks()
        observeFilesList()
    }


    private fun initFilesList() {
        remoteTab.findViewById<RecyclerView>(R.id.remote_files_list).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = listAdapter
        }
    }


    private fun observeLoaders() {
        with(viewModel) {
            isLoading.observe(context, Observer {
                loader.visibility = if (it == true) {
                    netStatusTv.text = ""
                    View.VISIBLE
                } else {
                    View.GONE
                }
            })

            solfaDownloading.observe(context, Observer {
                downloadLoader.visibility = if (it == true) View.VISIBLE else View.GONE
            })
        }
    }


    private fun observeFilesList() {
        viewModel.remoteFilesList.observe(context, Observer {
            if (it != null)
                listAdapter.update(it.toMutableList())
        })
    }


    private fun listenClicks() {
        searchBtn.setOnClickListener { search() }
        cancelDownloadBtn.setOnClickListener { cancelDownload() }
        downloadLoader.setOnClickListener { }
    }


    fun activate() {
        if (!isConnected && !context.networking.isConnected()) {
            showMessage(Values.notConnected)
        } else {
            viewModel.remoteFilesList.run {
                if (value == null || (value as List<RemoteSolfa>).isEmpty()) {
                    search()
                }
            }
        }

        netStatusTv.run {
            text = ""
            visibility = View.GONE
        }
    }


    fun deactivate() {
        cancelSearch()
    }


    fun search() {
        val text = searchView.text.toString().trim()
        viewModel.isLoading.value = true
        viewModel.remoteFilesList.value = listOf()
        netStatusTv.visibility = View.GONE

        if (text != "") {
            net.searchSolfa(text, ::saveFilesList, ::saveError)
        } else {
            net.mostDownloaded(::saveFilesList, ::saveError)
        }
    }


    private fun cancelSearch() {
        net.cancelSearch()
        net.cancelDownload()
        viewModel.isLoading.value = false
    }


    private fun cancelDownload() {
        net.cancelDownload()
        viewModel.solfaDownloading.value = false
    }


    private fun saveFilesList(list: MutableList<RemoteSolfa>) {
        viewModel.apply {
            remoteFilesList.value = list
            isLoading.value = false
            if (list.isEmpty()) {
                showMessage(Values.noSolfaFound)
            }
        }
    }


    private fun saveError() {
        viewModel.apply {
            isLoading.value = false
            solfaDownloading.value = false
        }
        showMessage(Values.netError)
    }


    private fun download(id: Int) {
        val solfa = viewModel.remoteFilesList.value?.find { it.id == id }
        if (solfa != null) {
            context.networking.downloadSolfa(solfa, ::onDownloadSuccess, ::saveError)
            viewModel.solfaDownloading.value = true
            downloadFilename.text = solfa.name
        }
    }


    private fun onDownloadSuccess(name: String, content: String) {
        FileManager.run { write(getCopy(name).name, content) }
        viewModel.solfaDownloading.value = false
    }


    private fun showMessage(m: String) {
        netStatusTv.run {
            text = m
            visibility = View.VISIBLE
        }
    }
}