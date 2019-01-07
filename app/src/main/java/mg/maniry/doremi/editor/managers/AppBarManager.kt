package mg.maniry.doremi.editor.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.BrowserActivity
import mg.maniry.doremi.commonUtils.Values
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.AboutActivity
import mg.maniry.doremi.editor.HelpActivity
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import mg.maniry.doremi.editor.partition.Player
import mg.maniry.doremi.editor.viewModels.UiViewModel


class AppBarManager(
        private val mainContext: Context,
        private val mainView: View,
        private val uiViewModel: UiViewModel,
        private val editorViewModel: EditorViewModel,
        private val player: Player) {


    private val verticalBar = View.inflate(mainContext, R.layout.vertical_appbar, null)
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
            findViewById<ImageView>(R.id.stop_btn)
                    .setOnClickListener { player.stop() }

            findViewById<ImageView>(R.id.additional_menu_btn)
                    .setOnClickListener { toggleVerticalBar() }

            findViewById<ImageView>(R.id.play_btn)
                    .setOnClickListener {
                        player.play()
                        editorViewModel.message.value = Values.playing
                    }

            findViewById<ImageView>(R.id.save_btn)
                    .setOnClickListener { editorViewModel.save() }
        }
    }


    private fun initActionButtons() {
        with(verticalBar) {
            findViewById<ImageView>(R.id.print_btn)
                    .setOnClickListener { editorViewModel.print() }

            findViewById<ImageView>(R.id.undo_btn)
                    .setOnClickListener { editorViewModel.restoreHistory(false) }

            findViewById<ImageView>(R.id.redo_btn)
                    .setOnClickListener { editorViewModel.restoreHistory(true) }

            findViewById<ImageView>(R.id.save_mid_btn)
                    .setOnClickListener { editorViewModel.exportMidiFile() }
        }
    }


    private fun initEditorMode() {
        mainView.findViewById<ImageView>(R.id.preview_btn).setOnClickListener {
            uiViewModel.toggleEditorMode()
        }

        uiViewModel.editorMode.observe(mainContext as EditorActivity, Observer {
            toggleViewMode(it)
        })
    }


    private fun initDialogButtons() {
        with(verticalBar) {
            findViewById<ImageView>(R.id.help_btn)
                    .setOnClickListener { startHelpActivity() }

            findViewById<ImageView>(R.id.about_btn)
                    .setOnClickListener { startAboutActivity() }

            findViewById<ImageView>(R.id.browse_btn)
                    .setOnClickListener { startBrowserActivity() }
        }
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


    private fun initVerticalBar() {
        with(verticalBar) {
            mainView.findViewById<FrameLayout>(R.id.editor_cont).addView(this)
            visibility = View.GONE
            findViewById<ImageView>(mg.maniry.doremi.R.id.reset_btn)
                    .setOnClickListener { editorViewModel.reset() }
        }
    }


    private fun toggleVerticalBar() {
        verticalBar.visibility = when (verticalBar.visibility) {
            View.GONE -> View.VISIBLE
            else -> View.GONE
        }
    }


    private fun startAboutActivity() {
        mainContext.startActivity(Intent(mainContext, AboutActivity::class.java))
    }


    private fun startHelpActivity() {
        mainContext.startActivity(Intent(mainContext, HelpActivity::class.java))
    }


    private fun startBrowserActivity() {
        mainContext.startActivity(Intent(mainContext, BrowserActivity::class.java))
    }


    fun handleBtnPress(keyCode: Int): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_BACK && verticalBar.visibility == View.VISIBLE) {
            toggleVerticalBar()
            true
        } else {
            false
        }
    }
}
