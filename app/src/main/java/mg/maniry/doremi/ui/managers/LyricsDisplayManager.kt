package mg.maniry.doremi.ui.managers


import android.arch.lifecycle.Observer
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import mg.maniry.doremi.R
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.viewModels.UiViewModel


class LyricsDisplayManager(
        private val mainContext: Context,
        private val mainView: View,
        private val uiViewModel: UiViewModel,
        private val editorViewModel: EditorViewModel) {

    private val lyrics = editorViewModel.partitionData.lyrics
    private val textView = mainView.findViewById<TextView>(R.id.lyrics_text_view)
    private val editText = mainView.findViewById<EditText>(R.id.lyrics_edit_text)
    private val editBtn = mainView.findViewById<ImageButton>(R.id.lyrics_edit_btn)
    private val saveBtn = mainView.findViewById<ImageButton>(R.id.lyrics_save_btn)

    init {
        initLyricsDisplay()
    }


    private fun initLyricsDisplay() {
        editorViewModel.lyricsEditMode.observe(mainContext as MainActivity, Observer {
            toggleViewMode(it == true)
        })


        lyrics.observe(mainContext, Observer {
            if (editorViewModel.lyricsEditMode.value == true)
                editText.setText(it)
            else
                textView.text = it
        })


        saveBtn.setOnClickListener {
            save()
            mainView.hideKeyboard()
            editorViewModel.lyricsEditMode.value = false
        }

        editBtn.setOnClickListener {
            editorViewModel.lyricsEditMode.value = true
            uiViewModel.editorMode.value = uiViewModel.modeView
        }
    }


    private fun toggleViewMode(editMode: Boolean) {
        val editVisibility = if (editMode) View.VISIBLE else View.GONE
        val viewVisibility = if (editMode) View.GONE else View.VISIBLE

        saveBtn.visibility = editVisibility
        editBtn.visibility = viewVisibility

        textView.apply {
            visibility = viewVisibility
            text = if (editMode) "" else lyrics.value
        }

        editText.apply {
            visibility = editVisibility
            setText(if (editMode) lyrics.value else "")
        }
    }


    fun save() {
        with(editText) {
            if (visibility == View.VISIBLE && text.toString() != "")
                lyrics.value = text.toString()
        }
    }


    fun handleButtonPress(keyCode: Int): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_BACK && editText.visibility == View.VISIBLE) {
            toggleViewMode(false)
            true
        } else {
            false
        }
    }
}