package mg.maniry.doremii.editor.managers

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.partition.Player
import mg.maniry.doremii.editor.viewModels.UiViewModel


class UiManager(
    mainContext: Context,
    mainView: View,
    private val uiViewModel: UiViewModel,
    private var editorViewModel: EditorViewModel,
    player: Player
) {

    private val drawerManager = DrawerManager(mainView)
    val changesDialogManager: ChangesDialogManager
    private val solfaDisplayManager: SolfaDisplayManager
    private val lyricsDisplayManager: LyricsDisplayManager
    private val playbackFollow: PlaybackFollow

    init {
        AppBarManager(mainContext, mainView, uiViewModel, editorViewModel, player)
        Toaster(mainContext, editorViewModel)
        PianoViewManager(mainContext, mainView, editorViewModel)
        solfaDisplayManager = SolfaDisplayManager(mainContext, mainView, editorViewModel)
        playbackFollow = PlaybackFollow(mainContext as LifecycleOwner, mainView, editorViewModel, player, solfaDisplayManager)
        Toaster(mainContext, editorViewModel)
        lyricsDisplayManager =
            LyricsDisplayManager(mainContext, mainView, uiViewModel, editorViewModel)
        val tabsM = TabsManager(mainContext, mainView)
        EditorTabManager(mainContext, tabsM.editorTab, editorViewModel, player, mainView)
        changesDialogManager = ChangesDialogManager(mainContext, mainView, editorViewModel)
    }


    fun handleButtonPress(keyCode: Int) = when {
        drawerManager.handleButtonPress(keyCode) -> true
        lyricsDisplayManager.handleButtonPress(keyCode) -> true
        else -> false
    }


    fun kill() {
        playbackFollow.stop()
        lyricsDisplayManager.save()

        editorViewModel.run {
            save()
            resetCursors()
            onPlayerCursorPosChanged(0)
            resetDialog()
        }

        with(uiViewModel.editorMode) {
            if (value != 1) {
                value = 1
            }
        }
    }
}
