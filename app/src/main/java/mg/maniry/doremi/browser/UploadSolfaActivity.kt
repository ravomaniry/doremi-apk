package mg.maniry.doremi.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.networking.Networking
import mg.maniry.doremi.browser.uiManagers.LoginUiManager
import mg.maniry.doremi.browser.uiManagers.UploadSolfaUiManager


class UploadSolfaActivity : AppCompatActivity() {
    var userId = -1L
    lateinit var internet: Networking
    private var prefs: SharedPreferences? = null
    private lateinit var mainView: View


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mainView = View.inflate(this, R.layout.upload_solfa_activity, null)
        setContentView(mainView)

        prefs = getPreferences(Context.MODE_PRIVATE)
        internet = Networking(this)
        decideActions()
    }


    private fun isLoggedIn(): Boolean {
        return if (userId > 0) {
            true
        } else {
            userId = prefs?.getLong("userId", -1L) ?: -1L
            userId > 0
        }
    }


    private fun decideActions() {
        if (isLoggedIn()) {
            val name = intent.getStringExtra("filename")
            if (name != null) {
                UploadSolfaUiManager(this, mainView).upload(name)
            } else {
                finish()
            }
        } else {
            LoginUiManager(this, mainView)
        }
    }


    fun saveUser(id: Long) {
        prefs?.edit()?.apply {
            putLong("userId", id)
            apply()
        }
        userId = id
        decideActions()
    }


    override fun onStop() {
        internet.run {
            cancelSolfaUpload()
            cancelLogin()
        }

        super.onStop()
    }
}