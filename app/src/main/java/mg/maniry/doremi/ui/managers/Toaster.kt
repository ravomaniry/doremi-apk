package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.widget.Toast
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel


class Toaster(
        private val mainContext: Context,
        editorViewModel: EditorViewModel) {


    init {
        editorViewModel.message.observe(mainContext as MainActivity, Observer {
            it?.run { Toast.makeText(mainContext, it, Toast.LENGTH_SHORT).show() }
        })
    }
}