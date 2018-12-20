package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.partition.Player
import mg.maniry.doremi.ui.views.AboutDialog
import mg.maniry.doremi.ui.views.HelpDialog
import mg.maniry.doremi.viewModels.UiViewModel


class AppBarManager(
        private val mainContext: Context,
        private val mainView: View,
        private val uiViewModel: UiViewModel,
        private val editorViewModel: EditorViewModel,
        private val player: Player) {

    private val supportFragMgr = with(mainContext as MainActivity) { supportFragmentManager }

    private val verticalBar = View.inflate(mainContext, R.layout.vertical_appbar, null)
    private val helpDialog = HelpDialog()
    private val aboutDialog = AboutDialog()
    private val editorViews = EditorViews(
            mainCont = mainView.findViewById(R.id.editor_body_content),
            keyboardCont = mainView.findViewById(R.id.keyboard_cont),
            viewerCont = mainView.findViewById(R.id.editor_viewer_cont),
            viewer = mainView.findViewById(R.id.editor_viewer),
            btn = mainView.findViewById(R.id.preview_btn))


    init {
        initVerticalBar()
        initPlayerButtons()
        initActionButtons()
        initEditorMode()
        initDialogButtons()
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


    private fun initActionButtons() {
        with(verticalBar) {
            findViewById<ImageView>(R.id.print_btn)
                    .setOnClickListener {
                        editorViewModel.print { filename ->
                            notify("Succès: export/$filename.html")
                        }
                    }

            findViewById<ImageView>(R.id.undo_btn)
                    .setOnClickListener { editorViewModel.restoreHistory(false) }

            findViewById<ImageView>(R.id.redo_btn)
                    .setOnClickListener { editorViewModel.restoreHistory(true) }
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

    private fun initDialogButtons() {
        with(verticalBar) {
            findViewById<ImageView>(R.id.help_btn)
                    .setOnClickListener {
                        helpDialog.show(supportFragMgr, "help_dialog")
                    }

            findViewById<ImageView>(R.id.about_btn)
                    .setOnClickListener {
                        aboutDialog.show(supportFragMgr, "about_dialog")
                    }
        }
    }


    private fun toggleViewMode(mode: Int?) {
        toggleVerticalBar()

//        with(editorViews) {
//            if (mode == 1) {
//                viewerCont.removeView(viewer)
//                mainCont.apply {
//                    removeAllViews()
//                    addView(viewer)
//                }
//
//                btn.setImageResource(R.drawable.ic_piano)
//            } else {
//                mainCont.removeAllViews()
//                viewerCont.apply {
//                    removeView(viewer)
//                    addView(viewer)
//                }
//                mainCont.apply {
//                    addView(viewerCont)
//                    addView(keyboardCont)
//                }
//
//                btn.setImageResource(R.drawable.ic_fullscreen)
//            }
//        }
    }


    private fun initVerticalBar() {
        verticalBar.visibility = View.GONE
        mainView.findViewById<FrameLayout>(R.id.editor_cont).addView(verticalBar)
    }


    private fun toggleVerticalBar() {
        verticalBar.visibility = when (verticalBar.visibility) {
            View.GONE -> View.VISIBLE
            else -> View.GONE
        }
    }

    private fun notify(s: String) {
        Toast.makeText(mainContext, s, Toast.LENGTH_LONG).show()
    }
}
