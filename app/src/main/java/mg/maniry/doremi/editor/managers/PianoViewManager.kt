package mg.maniry.doremi.editor.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ImageView
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.views.PianoView
import mg.maniry.doremi.editor.viewModels.EditorViewModel


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
            findViewById<Button>(kb_dash).setOnClickListener { editorVm.addNote("-") }
            findViewById<Button>(kb_dot).setOnClickListener { editorVm.addNote(".") }
            findViewById<Button>(kb_comma).setOnClickListener { editorVm.addNote(",") }
            findViewById<Button>(kb_semicolon).setOnClickListener { editorVm.addNote(".,") }
            findViewById<Button>(kb_space).setOnClickListener { editorVm.addNote(" ") }
        }

        pianoView.onPressed = { editorVm.addNote(it ?: "") }
    }


    private fun observeOctaveChange() {
        editorVm.octave.observe(mainContext as EditorActivity, Observer {
            pianoView.changeOctave(it ?: 0)
        })
    }
}