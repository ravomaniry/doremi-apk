package mg.maniry.doremii.editor.managers

import androidx.lifecycle.Observer
import android.content.Context
import android.view.View
import android.widget.ImageView
import mg.maniry.doremii.R.id.*
import mg.maniry.doremii.editor.EditorActivity
import mg.maniry.doremii.editor.views.PianoView
import mg.maniry.doremii.editor.viewModels.EditorViewModel


class PianoViewManager constructor(
        private val mainContext: Context,
        private val mainView: View,
        private val editorVm: EditorViewModel) {

    private val pianoView = mainView.findViewById<PianoView>(piano_view)

    init {
        attachListeners()
        observeOctaveChange()
    }


    private fun attachListeners() {
        with(mainView) {
            findViewById<ImageView>(reduce_octave_btn).setOnClickListener { editorVm.changeOctave(false) }
            findViewById<ImageView>(increment_octave_btn).setOnClickListener { editorVm.changeOctave(true) }
            findViewById<ImageView>(delete_notes_btn).setOnClickListener { editorVm.deleteNotes() }
            findViewById<View>(kb_dash).setOnClickListener { editorVm.addNote("-") }
            findViewById<View>(kb_dot).setOnClickListener { editorVm.addNote(".") }
            findViewById<View>(kb_comma).setOnClickListener { editorVm.addNote(",") }
            findViewById<View>(kb_semicolon).setOnClickListener { editorVm.addNote(".,") }
            findViewById<View>(kb_space).setOnClickListener { editorVm.addNote(" ") }
        }

        pianoView.onPressed = { editorVm.addNote(it ?: "") }
    }


    private fun observeOctaveChange() {
        editorVm.octave.observe(mainContext as EditorActivity, Observer {
            pianoView.changeOctave(it ?: 0)
        })
    }
}