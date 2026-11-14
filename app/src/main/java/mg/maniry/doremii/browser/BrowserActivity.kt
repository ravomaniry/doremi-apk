package mg.maniry.doremii.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.networking.Networking
import mg.maniry.doremii.browser.uiManagers.UiManager
import mg.maniry.doremii.commonUtils.FileManager


class BrowserActivity : AppCompatActivity() {
    var activeTab = 0
    var viewModel: BrowserViewModel? = null
    lateinit var networking: Networking
    private var prefs: SharedPreferences? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileManager.initDir(this)
        FileManager.copyDemoFiles(assets)

        val mainView = View.inflate(this, R.layout.browser_activity, null)
        setContentView(mainView)

        if (viewModel == null) {
            viewModel = ViewModelProvider(this)[BrowserViewModel::class.java]
        }

        networking = Networking(this)
        UiManager(this, mainView)
        prefs = getPreferences(Context.MODE_PRIVATE)
    }


    override fun onResume() {
        super.onResume()
        viewModel?.refreshFilesList()
    }


    override fun onStop() {
        networking.cancelAll()
        super.onStop()
    }
}
