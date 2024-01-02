package mg.maniry.doremii.browser.uiManagers

import android.view.View
import android.widget.*
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.UploadSolfaActivity
import org.json.JSONObject


class LoginUiManager(val context: UploadSolfaActivity, mainView: View) {
    private val nameET = mainView.findViewById<EditText>(R.id.login_name_et)
    private val codeET = mainView.findViewById<EditText>(R.id.login_code_et)
    private val loginBtn = mainView.findViewById<ImageButton>(R.id.login_submit_btn)
    private val cancelBtn = mainView.findViewById<Button>(R.id.login_cancel_btn)
    private val loginLoader = mainView.findViewById<ProgressBar>(R.id.login_loader)
    private val errorTv = mainView.findViewById<TextView>(R.id.login_error_tv)


    init {
        listenClicks()
        mainView.findViewById<LinearLayout>(R.id.login_mode_view).visibility = View.VISIBLE
        mainView.findViewById<LinearLayout>(R.id.upload_mode_view).visibility = View.GONE
    }


    private fun listenClicks() {
        loginBtn.setOnClickListener { submit() }
        cancelBtn.setOnClickListener { cancel() }
    }


    private fun submit() {
        val name = nameET.text.toString().trim()
        val code = codeET.text.toString()

        if (name != "" && code != "") {
            val body = JSONObject().apply {
                put("name", name)
                put("code", code)
            }

            context.internet.login(body, ::onLoggedIn, ::onError)
            show(listOf(loginLoader, cancelBtn))
            hide(listOf(loginBtn, errorTv))
        }
    }


    private fun cancel() {
        context.internet.cancelLogin()
        hide(listOf(cancelBtn, loginLoader, errorTv))
        show(loginBtn)
    }


    private fun onLoggedIn(user: JSONObject) {
        context.saveUser(user.getLong("id"))
    }


    private fun onError(reason: String) {
        errorTv.text = reason
        show(listOf(loginBtn, errorTv, errorTv))
        hide(listOf(cancelBtn, loginLoader))
    }


    private fun hide(views: Any) {
        when (views) {
            is View -> views.visibility = View.GONE
            is List<*> -> views.forEach { (it as View).visibility = View.GONE }
        }
    }


    private fun show(views: Any) {
        when (views) {
            is View -> views.visibility = View.VISIBLE
            is List<*> -> views.forEach { (it as View).visibility = View.VISIBLE }
        }
    }
}
