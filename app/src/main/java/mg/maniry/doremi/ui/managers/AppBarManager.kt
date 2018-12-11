package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.view.View
import android.widget.ImageView
import mg.maniry.doremi.R
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.partition.Player
import mg.maniry.doremi.viewModels.UiViewModel


class AppBarManager(
        private val mainContext: Context,
        private val mainView: View,
        private val uiViewModel: UiViewModel,
        private val editorViewModel: EditorViewModel,
        private val player: Player) {


    private val editorViews = EditorViews(
            mainCont = mainView.findViewById(R.id.editor_body_content),
            keyboardCont = mainView.findViewById(R.id.keyboard_cont),
            viewerCont = mainView.findViewById(R.id.editor_viewer_cont),
            viewer = mainView.findViewById(R.id.editor_viewer),
            btn = mainView.findViewById(R.id.preview_btn))


    init {
        initPlayerButtons()
        initEditorMode()
    }


    private fun initPlayerButtons() {
        with(mainView) {
            findViewById<ImageView>(mg.maniry.doremi.R.id.stop_btn).setOnClickListener { player.stop() }
            findViewById<ImageView>(mg.maniry.doremi.R.id.reset_btn).setOnClickListener { editorViewModel.reset() }
            findViewById<ImageView>(mg.maniry.doremi.R.id.play_btn).setOnClickListener {
                player.play()
                editorViewModel.message.value = "Lecture en cours"
            }
            findViewById<ImageView>(mg.maniry.doremi.R.id.save_btn).setOnClickListener {
                editorViewModel.save()
            }
        }
    }


    private fun initEditorMode() {
        mainView.findViewById<ImageView>(R.id.preview_btn).setOnClickListener {
            uiViewModel.toggleEditorMode()
        }

        uiViewModel.editorMode.observe(mainContext as MainActivity, Observer {
            toggleViewMode(it)
        })
    }


    private fun toggleViewMode(mode: Int?) {
        with(editorViews) {
            if (mode == 1) {
                viewerCont.removeView(viewer)
                mainCont.apply {
                    removeAllViews()
                    addView(viewer)
                }

                btn.setImageResource(R.drawable.ic_piano)
            } else {
                mainCont.removeAllViews()
                viewerCont.apply {
                    removeView(viewer)
                    addView(viewer)
                }
                mainCont.apply {
                    addView(viewerCont)
                    addView(keyboardCont)
                }

                btn.setImageResource(R.drawable.ic_fullscreen)
            }
        }
    }
}