package mg.maniry.doremii.browser

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.commonUtils.SongFile


class BrowserViewModel : ViewModel() {
    val filesList = MutableLiveData<List<SongFile>>()
    val remoteFilesList = MutableLiveData<List<RemoteSolfa>>()
    val isLoading = MutableLiveData<Boolean>()
    val solfaDownloading = MutableLiveData<Boolean>()

    fun refreshFilesList() {
        viewModelScope.launch {
            filesList.value = withContext(Dispatchers.IO) { FileManager.listFiles() }
        }
    }
}