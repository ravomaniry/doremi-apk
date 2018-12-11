package mg.maniry.doremi.ui.managers


import android.arch.lifecycle.Observer
import android.content.Context
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


    init {
        initLyricsDisplay()
    }


    private fun initLyricsDisplay() {
        val lyrics = editorViewModel.partitionData.lyrics
        val textView = mainView.findViewById<TextView>(R.id.lyrics_text_view)
        val editText = mainView.findViewById<EditText>(R.id.lyrics_edit_text)
        val editBtn = mainView.findViewById<ImageButton>(R.id.lyrics_edit_btn)
        val saveBtn = mainView.findViewById<ImageButton>(R.id.lyrics_save_btn)


        editorViewModel.lyricsEditMode.observe(mainContext as MainActivity, Observer {
            val editMode = it == true
            val editVisibility = if (editMode) View.VISIBLE else View.INVISIBLE
            val viewVisibility = if (editMode) View.INVISIBLE else View.VISIBLE

            editBtn.visibility = viewVisibility
            textView.apply {
                visibility = viewVisibility
                text = if (editMode) "" else lyrics.value
            }

            saveBtn.visibility = editVisibility
            editText.apply {
                visibility = editVisibility
                setText(if (editMode) lyrics.value else "")
            }
        })


        lyrics.observe(mainContext, Observer {
            if (editorViewModel.lyricsEditMode.value == true)
                editText.setText(it)
            else
                textView.text = it
        })


        saveBtn.setOnClickListener {
            mainView.hideKeyboard()
            lyrics.value = editText.text.toString()
            editorViewModel.apply {
                lyricsEditMode.value = false
                save()
            }
        }

        editBtn.setOnClickListener {
            editorViewModel.lyricsEditMode.value = true
            uiViewModel.editorMode.value = uiViewModel.modeView
        }
    }
}