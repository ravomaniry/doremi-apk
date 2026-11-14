package mg.maniry.doremii.editor.viewModels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel


class UiViewModel : ViewModel() {
    val drawerTab = MutableLiveData<Int>().apply { value = 0 }
    val editorMode = MutableLiveData<Int>().apply { value = 1 }
    val modeView = 1


    fun changeTab(index: Int) {
        if (index != drawerTab.value)
            drawerTab.value = index
    }


    fun toggleEditorMode() {
        editorMode.value = if (editorMode.value == 0) 1 else 0
    }
}