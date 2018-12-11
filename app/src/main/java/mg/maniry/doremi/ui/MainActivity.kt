package mg.maniry.doremi.ui

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import mg.maniry.doremi.ui.managers.UiManager
import mg.maniry.doremi.ui.managers.inflateMainView
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.partition.Player
import mg.maniry.doremi.viewModels.UiViewModel


class MainActivity : AppCompatActivity() {
    private lateinit var mainView: View
    var uiManager: UiManager? = null
    private var editorViewModel: EditorViewModel? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    val player = Player(this, it)
                    uiManager = UiManager(this@MainActivity, mainView, uiVM, it, player)
                }
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?) =
            if (uiManager?.handleButtonPress(keyCode) == true) {
                true
            } else {
                super.onKeyDown(keyCode, event)
            }


    override fun onStop() {
        uiManager?.kill()
        super.onStop()
    }
}
