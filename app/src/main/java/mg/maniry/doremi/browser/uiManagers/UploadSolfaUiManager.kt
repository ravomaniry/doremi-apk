package mg.maniry.doremi.browser.uiManagers


import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.UploadSolfaActivity
import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.editor.partition.KeyValue
import mg.maniry.doremi.editor.partition.Labels
import org.json.JSONObject


class UploadSolfaUiManager(val context: UploadSolfaActivity, mainView: View) {
    private val filenameTv = mainView.findViewById<TextView>(R.id.upload_filename_tv)
    private val uploadDoneTv = mainView.findViewById<TextView>(R.id.upload_done_tv)
    private val errorTv = mainView.findViewById<TextView>(R.id.upload_error_tv)
    private val loader = mainView.findViewById<ProgressBar>(R.id.upload_loader_view)
    private val cancelBtn = mainView.findViewById<Button>(R.id.upload_cancel_btn)
    private val doneBtn = mainView.findViewById<Button>(R.id.upload_done_btn)
    private val retryBtn = mainView.findViewById<Button>(R.id.upload_retry_btn)
    private lateinit var body: JSONObject
    private var filename = ""


    init {
        listenClicks()
        mainView.findViewById<LinearLayout>(R.id.login_mode_view).visibility = View.GONE
        mainView.findViewById<LinearLayout>(R.id.upload_mode_view).visibility = View.VISIBLE
    }


    fun upload(name: String) {
        with(FileManager.read(name)) {
            if (error == null) {
                filename = name
                body = JSONObject().apply {
                    put("user", context.userId)
                    put("name", getFileName(content))
                    put("content", content)
                }

                performRequest()
            } else {
                errorTv.text = error
            }
        }
    }


    private fun getFileName(content: String): String {
        val mainParts = content.split("__!!__").toMutableList()
        val info = mainParts[0]
                .split(";")
                .map { KeyValue(it.trim().split(':')) }

        var title = ""
        var singer = ""

        info.forEach {
            if (it.mKey == Labels.TITLE)
                title = it.mValue
            else if (it.mKey == Labels.SINGER)
                singer = it.mValue
        }

        return title + if (singer == "") "" else " - $singer"
    }


    private fun listenClicks() {
        cancelBtn.setOnClickListener { cancel() }
        doneBtn.setOnClickListener { done() }
        retryBtn.setOnClickListener { performRequest() }
    }


    private fun performRequest() {
        context.internet.uploadSolfa(body, ::onSuccess, ::onError)
        filenameTv.text = filename
        errorTv.text = ""

        loader.visibility = View.VISIBLE
        cancelBtn.visibility = View.VISIBLE
        retryBtn.visibility = View.GONE
        doneBtn.visibility = View.GONE
    }


    private fun onSuccess(res: JSONObject) {
        res.getBoolean("uploaded")
        errorTv.text = ""
        uploadDoneTv.visibility = View.VISIBLE
        doneBtn.visibility = View.VISIBLE
        loader.visibility = View.GONE
        cancelBtn.visibility = View.GONE
        retryBtn.visibility = View.GONE
    }


    private fun onError(reason: String) {
        errorTv.text = if (reason == "") "Network error :(" else reason
        retryBtn.visibility = View.VISIBLE
        doneBtn.visibility = View.VISIBLE
        cancelBtn.visibility = View.GONE
        loader.visibility = View.GONE
    }


    private fun done() {
        context.finish()
    }


    private fun cancel() {
        context.internet.apply {
            cancelSolfaUpload()
            cancelSolfaUpload()
        }

        retryBtn.visibility = View.VISIBLE
        doneBtn.visibility = View.VISIBLE
        loader.visibility = View.GONE
        cancelBtn.visibility = View.GONE
    }

}