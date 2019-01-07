package mg.maniry.doremi.browser

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.networking.Networking
import mg.maniry.doremi.browser.uiManagers.UiManager
import mg.maniry.doremi.commonUtils.PermissionsManager


class BrowserActivity : AppCompatActivity() {
    var activeTab = 0
    var viewModel: BrowserViewModel? = null
    lateinit var networking: Networking
    private var prefs: SharedPreferences? = null
    private lateinit var permissionsManager: PermissionsManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainView = View.inflate(this, R.layout.browser_activity, null)

        setContentView(mainView)

        if (viewModel == null)
            viewModel = ViewModelProviders.of(this).get(BrowserViewModel::class.java)

        permissionsManager = PermissionsManager(this).apply { network() }
        networking = Networking(this)

        UiManager(this, mainView)

        prefs = getPreferences(Context.MODE_PRIVATE)
    }


    override fun onRequestPermissionsResult(reqCode: Int, permissions: Array<out String>, results: IntArray) {
        permissionsManager.grantPermission(reqCode, results) { }
    }


    override fun onStop() {
        networking.cancelAll()
        super.onStop()
    }
}
