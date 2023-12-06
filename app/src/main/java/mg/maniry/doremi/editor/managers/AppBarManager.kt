package mg.maniry.doremi.editor.managers

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.BrowserActivity
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
    private val player: Player
) {

    private lateinit var playBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private val editorViews = EditorViews(
        mainCont = mainView.findViewById(R.id.editor_body_content),
        keyboardCont = mainView.findViewById(R.id.keyboard_cont),
        viewerCont = mainView.findViewById(R.id.editor_viewer_cont),
        viewer = mainView.findViewById(R.id.editor_viewer),
        btn = mainView.findViewById(R.id.preview_btn)
    )


    init {
        initButtons()
        initEditorMode()
        observePlayerState()
    }


    private fun initButtons() {
        with(mainView) {
            playBtn = findViewById(R.id.play_btn)
            playBtn.setOnClickListener { player.play() }
            stopBtn = findViewById(R.id.stop_btn)
            stopBtn.setOnClickListener { player.stop() }
            findViewById<ImageView>(R.id.print_btn).setOnClickListener { editorViewModel.print() }
            findViewById<ImageView>(R.id.undo_btn).setOnClickListener {
                editorViewModel.restoreHistory(false)
            }
            findViewById<ImageView>(R.id.redo_btn).setOnClickListener {
                editorViewModel.restoreHistory(true)
            }
            findViewById<ImageView>(R.id.save_mid_btn).setOnClickListener { editorViewModel.exportMidiFile() }
            findViewById<ImageView>(R.id.help_btn).setOnClickListener { startHelpActivity() }
            findViewById<ImageView>(R.id.about_btn).setOnClickListener { startAboutActivity() }
            findViewById<ImageView>(R.id.browse_btn).setOnClickListener { startBrowserActivity() }
            findViewById<ImageView>(R.id.save_btn).setOnClickListener { editorViewModel.save() }
            findViewById<ImageView>(R.id.copy_btn).setOnClickListener { editorViewModel.toggleSelectMode() }
            findViewById<ImageView>(R.id.paste_btn).setOnClickListener { editorViewModel.paste() }
            findViewById<ImageView>(R.id.new_btn).setOnClickListener { editorViewModel.createNew() }
        }
    }


    private fun initEditorMode() {
        mainView.findViewById<ImageView>(R.id.preview_btn).setOnClickListener {
            uiViewModel.toggleEditorMode()
        }
        uiViewModel.editorMode.observe(mainContext as EditorActivity) {
            toggleViewMode(it)
        }
    }

    private fun observePlayerState() {
        editorViewModel.playerIsPlaying.observe(mainContext as EditorActivity) {
            if (it == true) {
                playBtn.visibility = View.GONE
                stopBtn.visibility = View.VISIBLE
            } else {
                playBtn.visibility = View.VISIBLE
                stopBtn.visibility = View.GONE
            }
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

    private fun startAboutActivity() {
        mainContext.startActivity(Intent(mainContext, AboutActivity::class.java))
    }


    private fun startHelpActivity() {
        mainContext.startActivity(Intent(mainContext, HelpActivity::class.java))
    }

    private fun startBrowserActivity() {
        mainContext.startActivity(Intent(mainContext, BrowserActivity::class.java))
    }
}
