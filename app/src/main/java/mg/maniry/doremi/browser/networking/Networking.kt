package mg.maniry.doremi.browser.networking

import android.content.Context
import android.net.ConnectivityManager
import com.android.volley.AuthFailureError
import com.android.volley.toolbox.Volley
import mg.maniry.doremi.browser.RemoteSolfa
import mg.maniry.doremi.commonUtils.Values
import org.json.JSONArray
import org.json.JSONObject


class Networking(val context: Context) {
    private val parsers = Parsers()
    private val endpoints = Endpoints()
    private val requestGen = RequestGen()
    private val queue = Volley.newRequestQueue(context)

    private val solfaSearchTag = "SFS"
    private val solfaDownloadTag = "SD"
    private val solfaDownIncTag = "SDI"
    private val loginReqTag = "LGN"
    private val solfaUploadTag = "SU"
    private var connected = false


    fun isConnected(): Boolean {
        return if (connected) {
            true
        } else {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            connected = manager is ConnectivityManager &&
                    (manager.activeNetworkInfo?.isConnected ?: false)

            connected
        }
    }


    fun mostDownloaded(onSuccess: ((MutableList<RemoteSolfa>) -> Unit), onError: (() -> Unit)) {
        queue.run {
            cancelAll(solfaSearchTag)
            add(requestGen.jsonArrayGet(endpoints.mostDownloaded, solfaSearchTag) {
                when (it) {
                    is JSONArray -> onSuccess(parsers.solfaSearchResult(it))
                    else -> onError()
                }
            })
        }
    }


    fun searchSolfa(text: String,
                    onSuccess: ((MutableList<RemoteSolfa>) -> Unit),
                    onError: (() -> Unit)) {
        queue.run {
            cancelAll(solfaSearchTag)
            add(requestGen.jsonArrayGet(endpoints.getSearchSolfaUrl(text), solfaSearchTag) {
                when (it) {
                    is JSONArray -> onSuccess(parsers.solfaSearchResult(it))
                    else -> onError()
                }
            })
        }
    }


    fun downloadSolfa(solfa: RemoteSolfa, onSuccess: (String, String) -> Unit, onError: () -> Unit) {
        queue.run {
            cancelAll(solfaDownloadTag)
            add(requestGen.stringReq(endpoints.driveDldUrl(solfa.driveId), solfaDownloadTag) {
                when (it) {
                    is String -> {
                        onSuccess(solfa.name, it)
                        markSolfaDownload(solfa.id)
                    }
                    else -> onError()
                }
            })
        }
    }


    private fun markSolfaDownload(id: Int) {
        queue.run {
            cancelAll(solfaDownIncTag)
            add(requestGen.jsonPost(endpoints.incrementDld, solfaDownIncTag, JSONObject("{\"id\":$id}")))
        }
    }


    fun login(body: JSONObject, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        post(endpoints.login, loginReqTag, body, onSuccess, onError)
    }


    fun uploadSolfa(body: JSONObject, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        post(endpoints.uploadSolfa, solfaUploadTag, body, onSuccess, onError)
    }


    private fun post(url: String, tag: String, body: JSONObject, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        queue.run {
            cancelAll(loginReqTag)
            add(requestGen.jsonPost(url, tag, body) {
                when (it) {
                    is JSONObject -> onSuccess(it)
                    is AuthFailureError -> onError(Values.invalidLogin)
                    else -> onError(Values.netError)
                }
            })
        }
    }


    fun cancelDownload() {
        queue.run {
            cancelAll(solfaDownloadTag)
            cancelAll(solfaDownIncTag)
        }
    }


    fun cancelSearch() {
        queue.cancelAll(solfaSearchTag)
    }


    fun cancelLogin() {
        queue.cancelAll(loginReqTag)
    }

    fun cancelSolfaUpload() {
        queue.cancelAll(solfaUploadTag)
    }


    fun cancelAll() {
        cancelDownload()
        cancelSearch()
        cancelLogin()
        cancelSolfaUpload()
    }
}