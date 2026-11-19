package mg.maniry.doremii.editor

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.editor.managers.UiManager
import mg.maniry.doremii.editor.managers.inflateMainView
import mg.maniry.doremii.editor.partition.Player
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.viewModels.UiViewModel
import mg.maniry.doremii.editor.xlsxExport.ExcelExport


class EditorActivity : AppCompatActivity() {
    private lateinit var mainView: View
    var uiManager: UiManager? = null
    private var editorViewModel: EditorViewModel? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        mainView = inflateMainView(this)
        setContentView(mainView)
        FileManager.initDir(baseContext)
        initUi()
    }


    private fun initUi() {
        val uiVM = ViewModelProvider(this)[UiViewModel::class.java]
        editorViewModel = ViewModelProvider(this)[EditorViewModel::class.java]
            .apply {
                start(getPreferences(Context.MODE_PRIVATE), intent)
                applyPlaybackSettings(this@EditorActivity)
            }.also {
                val player = it.player ?: Player(this, it)
                it.player = player
                it.cancelPlayerRelease()
                uiManager = UiManager(this@EditorActivity, mainView, uiVM, it, player)
                it.xlsExport = ExcelExport()
            }
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (uiManager?.handleButtonPress(keyCode) == true) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }


    override fun onStop() {
        uiManager?.kill()
        editorViewModel?.releasePlayer()
        super.onStop()
    }
}
