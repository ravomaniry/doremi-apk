package mg.maniry.doremi.editor.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.widget.Toast
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.viewModels.EditorViewModel


class Toaster(
        private val mainContext: Context,
        editorViewModel: EditorViewModel) {


    init {
        editorViewModel.message.observe(mainContext as EditorActivity, Observer {
            it?.run { Toast.makeText(mainContext, it, Toast.LENGTH_SHORT).show() }
        })
    }
}