package mg.maniry.doremii.browser.networking

import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import org.json.JSONObject


class RequestGen {
    private val getRetryPolicy = DefaultRetryPolicy(60000,
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)

    private val postRetryPolicy = DefaultRetryPolicy(6000,
            0,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)


    fun jsonArrayGet(url: String, reqTag: String, then: ((Any) -> Unit)): JsonArrayRequest {
        return JsonArrayRequest(
                Request.Method.GET,
                url,
                null,
                Response.Listener { then(it) },
                Response.ErrorListener { then(it) }
        ).apply {
            tag = reqTag
            retryPolicy = getRetryPolicy
        }
    }


    fun stringReq(url: String, reqTag: String, then: (Any) -> Unit): StringRequest {
        return StringRequest(
                Request.Method.GET,
                url,
                Response.Listener { then(it) },
                Response.ErrorListener { then(it) }
        ).apply {
            tag = reqTag
            retryPolicy = getRetryPolicy
        }
    }


    fun jsonPost(url: String, reqTag: String, body: JSONObject, then: ((Any) -> Unit)? = null): JsonObjectRequest {
        return JsonObjectRequest(Request.Method.POST,
                url,
                body,
                Response.Listener { res -> then?.run { then(res) } },
                Response.ErrorListener { res -> then?.run { then(res) } }
        ).apply {
            tag = reqTag
            retryPolicy = postRetryPolicy
        }
    }
}