package mg.maniry.doremii.editor.managers

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.BrowserActivity
import mg.maniry.doremii.editor.EditorActivity
import mg.maniry.doremii.editor.AboutActivity
import mg.maniry.doremii.editor.HelpActivity
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.partition.Player
import mg.maniry.doremii.editor.viewModels.SelectMode
import mg.maniry.doremii.editor.viewModels.UiViewModel


class AppBarManager(
    private val mainContext: Context,
    private val mainView: View,
    private val uiViewModel: UiViewModel,
    private val editorViewModel: EditorViewModel,
    private val player: Player
) {

    private lateinit var playBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var copyBtn: View
    private lateinit var editGroup: View
    private lateinit var moveLeftBtn: View
    private lateinit var moveRightBtn: View
    private lateinit var moveUpBtn: View
    private lateinit var moveDownBtn: View
    private val trayHandler = Handler(Looper.getMainLooper())
    private var openSection: Pair<View, View>? = null
    private val collapseOpenSection = Runnable {
        openSection?.let { (chip, tray) ->
            tray.visibility = View.GONE
            chip.isSelected = false
        }
        openSection = null
    }
    private val editorViews = EditorViews(
        mainCont = mainView.findViewById(R.id.editor_body_content),
        keyboardCont = mainView.findViewById(R.id.keyboard_cont),
        viewerCont = mainView.findViewById(R.id.editor_viewer_cont),
        viewer = mainView.findViewById(R.id.editor_viewer),
        btn = mainView.findViewById(R.id.preview_btn)
    )


    init {
        initButtons()
        initGroups()
        initEditorMode()
        observePlayerState()
        observePlaybackLoading()
        observeSelectMode()
        mainView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                cancelSectionCollapse()
            }
        })
    }


    private fun initButtons() {
        with(mainView) {
            playBtn = findViewById(R.id.play_btn)
            playBtn.setOnClickListener { player.play() }
            stopBtn = findViewById(R.id.stop_btn)
            stopBtn.setOnClickListener { player.stop() }
            findViewById<View>(R.id.print_btn).onSectionAction { editorViewModel.print() }
            findViewById<ImageView>(R.id.undo_btn).setOnClickListener {
                editorViewModel.restoreHistory(false)
            }
            findViewById<ImageView>(R.id.redo_btn).setOnClickListener {
                editorViewModel.restoreHistory(true)
            }
            findViewById<View>(R.id.save_mid_btn).onSectionAction { editorViewModel.exportMidiFile() }
            findViewById<View>(R.id.help_btn).onSectionAction { startHelpActivity() }
            findViewById<View>(R.id.about_btn).onSectionAction { startAboutActivity() }
            findViewById<View>(R.id.browse_btn).onSectionAction { startBrowserActivity() }
            findViewById<View>(R.id.save_btn).onSectionAction { editorViewModel.save() }
            copyBtn = findViewById(R.id.copy_btn)
            copyBtn.onSectionAction { editorViewModel.toggleSelectMode() }
            findViewById<View>(R.id.paste_btn).onSectionAction { editorViewModel.paste() }
            findViewById<View>(R.id.select_all_btn).onSectionAction { editorViewModel.selectAll() }
            moveLeftBtn = findViewById(R.id.move_left_btn)
            moveRightBtn = findViewById(R.id.move_right_btn)
            moveUpBtn = findViewById(R.id.move_up_btn)
            moveDownBtn = findViewById(R.id.move_down_btn)
            moveLeftBtn.onSectionAction { editorViewModel.moveSelection(0, -1) }
            moveRightBtn.onSectionAction { editorViewModel.moveSelection(0, 1) }
            moveUpBtn.onSectionAction { editorViewModel.moveSelection(-1, 0) }
            moveDownBtn.onSectionAction { editorViewModel.moveSelection(1, 0) }
            findViewById<View>(R.id.new_btn).onSectionAction { editorViewModel.createNew() }
        }
    }


    private fun initGroups() {
        val groups = listOf(
            R.id.group_file_btn to R.id.tray_file,
            R.id.group_edit_btn to R.id.tray_edit,
            R.id.group_export_btn to R.id.tray_export,
            R.id.group_more_btn to R.id.tray_more,
        ).map { (chipId, trayId) ->
            mainView.findViewById<View>(chipId) to mainView.findViewById<View>(trayId)
        }
        editGroup = groups[1].first
        groups.forEach { (chip, tray) ->
            chip.setOnClickListener { toggleTray(chip, tray, groups) }
        }
    }


    private fun toggleTray(chip: View, tray: View, groups: List<Pair<View, View>>) {
        val opening = tray.visibility != View.VISIBLE
        groups.forEach { (groupChip, groupTray) ->
            groupTray.visibility = View.GONE
            groupChip.isSelected = false
        }
        cancelSectionCollapse()
        openSection = null
        if (opening) {
            tray.visibility = View.VISIBLE
            chip.isSelected = true
            openSection = chip to tray
            scheduleSectionCollapse()
        }
    }


    private fun View.onSectionAction(action: () -> Unit) {
        setOnClickListener {
            noteSectionAction()
            action()
        }
    }


    private fun noteSectionAction() {
        if (openSection != null) scheduleSectionCollapse()
    }


    private fun scheduleSectionCollapse() {
        trayHandler.removeCallbacks(collapseOpenSection)
        trayHandler.postDelayed(collapseOpenSection, SECTION_IDLE_MS)
    }


    private fun cancelSectionCollapse() {
        trayHandler.removeCallbacks(collapseOpenSection)
    }


    private fun observeSelectMode() {
        val activity = mainContext as EditorActivity
        editorViewModel.selectModeState.observe(activity) { refreshEditActions() }
        editorViewModel.reRenderNotifier.observe(activity) { refreshEditActions() }
    }


    private fun refreshEditActions() {
        val copying = editorViewModel.selectMode == SelectMode.COPY
        copyBtn.isSelected = copying
        editGroup.isActivated = copying
        listOf(
            moveLeftBtn to editorViewModel.canMoveSelection(0, -1),
            moveRightBtn to editorViewModel.canMoveSelection(0, 1),
            moveUpBtn to editorViewModel.canMoveSelection(-1, 0),
            moveDownBtn to editorViewModel.canMoveSelection(1, 0),
        ).forEach { (button, enabled) ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.35f
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


    private fun observePlaybackLoading() {
        val overlay = mainView.findViewById<View>(R.id.playback_loading)
        val drawer = mainView.findViewById<DrawerLayout>(R.id.drawer_layout)
        overlay.findViewById<View>(R.id.cancel_playback_loading).setOnClickListener {
            player.stop()
        }
        editorViewModel.playbackLoading.observe(mainContext as EditorActivity) { loading ->
            val busy = loading == true
            overlay.visibility = if (busy) View.VISIBLE else View.GONE
            drawer.setDrawerLockMode(
                if (busy) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED
            )
            if (busy) drawer.closeDrawer(GravityCompat.START)
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
        mainContext.startActivity(Intent(mainContext, BrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private companion object {
        const val SECTION_IDLE_MS = 30_000L
    }
}
