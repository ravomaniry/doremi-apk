package mg.maniry.doremii.browser

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.maniry.doremii.commonUtils.FileManager


class BrowserViewModel : ViewModel() {
    val filesList = MutableLiveData<List<String>>()
    val remoteFilesList = MutableLiveData<List<RemoteSolfa>>()
    val isLoading = MutableLiveData<Boolean>()
    val solfaDownloading = MutableLiveData<Boolean>()

    fun refreshFilesList() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { FileManager.listFiles() }
            filesList.value = list ?: listOf()
        }
    }
}