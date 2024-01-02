package mg.maniry.doremii.browser.networking

import java.net.URLEncoder


class Endpoints {
    // private val localBaseUrl = "http://10.0.2.2:8080/api/"
    private val baseUrl = "https://doremi-apk.herokuapp.com/api/"
    private val gUrl = "https://docs.google.com/uc?export=download"
    private val searchSolfa = baseUrl + "solfa/search"
    val incrementDld = baseUrl + "solfa/download"
    val uploadSolfa = baseUrl + "solfa/upload"
    val login = baseUrl + "user/login"
    val mostDownloaded = baseUrl + "solfa"


    fun getSearchSolfaUrl(name: String): String {
        return searchSolfa + "?q=" + URLEncoder.encode(name, "UTF-8")
    }


    fun driveDldUrl(id: String): String {
        return "$gUrl&id=$id"
    }
}