package mg.maniry.doremi.browser

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import mg.maniry.doremi.browser.RemoteSolfa
import mg.maniry.doremi.commonUtils.FileManager
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class BrowserViewModel : ViewModel() {
    val filesList = MutableLiveData<List<String>>()
    val remoteFilesList = MutableLiveData<List<RemoteSolfa>>()
    val isLoading = MutableLiveData<Boolean>()
    val solfaDownloading = MutableLiveData<Boolean>()


    fun refreshFilesList() {
        doAsync {
            val list = FileManager.listFiles()
            uiThread { filesList.value = list }
        }
    }


    fun importDoremiFiles() {
        FileManager.importAllDoremiFiles(::refreshFilesList)
    }
}