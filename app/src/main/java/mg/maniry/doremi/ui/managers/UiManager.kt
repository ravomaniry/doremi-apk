package mg.maniry.doremi.ui.managers

import android.content.Context
import android.view.View
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.partition.Player
import mg.maniry.doremi.viewModels.UiViewModel


class UiManager constructor(
        private val mainContext: Context,
        mainView: View,
        private val uiViewModel: UiViewModel,
        private var editorViewModel: EditorViewModel,
        private val player: Player) {

    private val drawerManager = DrawerManager(mainView)
    val changesDialogManager: ChangesDialogManager
    private val solfaDisplayManager: SolfaDisplayManager

    init {
        AppBarManager(mainContext, mainView, uiViewModel, editorViewModel, player)
        Toaster(mainContext, editorViewModel)
        PianoViewManager(mainContext, mainView, editorViewModel)
        solfaDisplayManager = SolfaDisplayManager(mainContext, mainView, editorViewModel)
        Toaster(mainContext, editorViewModel)
        LyricsDisplayManager(mainContext, mainView, uiViewModel, editorViewModel)

        TabsManager(mainContext, mainView, uiViewModel).also {
            EditorTabManager(mainContext, it.editorTab, editorViewModel, player)
            SongTabManager(mainContext, mainView, it.songTab, editorViewModel)
            ExplorerTabManager(mainContext, it.explorerTab, editorViewModel, drawerManager)
        }

        changesDialogManager = ChangesDialogManager(mainContext, mainView, editorViewModel)
        BottomButtonsManager(mainContext as MainActivity, mainView, editorViewModel)
    }


    fun handleButtonPress(keyCode: Int) = when {
        drawerManager.handleButtonPress(keyCode) -> true
        else -> false
    }


    fun kill() {
        solfaDisplayManager.movePlayerCursor(null)
        editorViewModel.run {
            save()
            resetCursors()
            playerCursorPosition = 0
            resetDialog()
        }
        with(uiViewModel.editorMode) {
            if (value != 1)
                value = 1
        }
    }
}
