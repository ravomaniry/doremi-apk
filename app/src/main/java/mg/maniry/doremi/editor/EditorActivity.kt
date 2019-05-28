package mg.maniry.doremi.editor

import android.app.Activity
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import mg.maniry.doremi.editor.managers.UiManager
import mg.maniry.doremi.editor.managers.inflateMainView
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.commonUtils.PermissionsManager
import mg.maniry.doremi.editor.partition.Player
import mg.maniry.doremi.editor.viewModels.UiViewModel
import mg.maniry.doremi.editor.xlsxExport.ExcelExport
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class EditorActivity : AppCompatActivity() {
    private lateinit var mainView: View
    var uiManager: UiManager? = null
    private var editorViewModel: EditorViewModel? = null
    private lateinit var permissionsManager: PermissionsManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsManager = PermissionsManager(this as Activity).apply { disk() }
        mainView = inflateMainView(this)
        setContentView(mainView)
        FileManager.copyDemoFiles(assets)
        initUi()
    }


    private fun initUi() {
        val uiVM = ViewModelProviders.of(this).get(UiViewModel::class.java)
        editorViewModel = ViewModelProviders.of(this).get(EditorViewModel::class.java)
                .apply { start(getPreferences(Context.MODE_PRIVATE), intent) }
                .also {
                    val player = it.player ?: Player(this, it)
                    it.player = player
                    it.cancelPlayerRelease()
                    uiManager = UiManager(this@EditorActivity, mainView, uiVM, it, player)
                    it.xlsExport = ExcelExport(assets)
                }
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (uiManager?.handleButtonPress(keyCode) == true) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }


    override fun onStop() {
        uiManager?.kill()
        super.onStop()
        editorViewModel?.releasePlayer()
    }


    override fun onRequestPermissionsResult(reqCode: Int, permissions: Array<out String>, results: IntArray) {
        permissionsManager.grantPermission(reqCode, results) {
            FileManager.copyDemoFiles(assets)
        }
    }
}
