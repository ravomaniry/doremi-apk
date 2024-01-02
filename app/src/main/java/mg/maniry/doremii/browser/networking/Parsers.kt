package mg.maniry.doremii.browser.networking

import mg.maniry.doremii.browser.RemoteSolfa
import org.json.JSONArray
import org.json.JSONObject

class Parsers {

    fun solfaSearchResult(res: JSONArray): MutableList<RemoteSolfa> {
        val files = mutableListOf<RemoteSolfa>()

        for (i in 0 until res.length()) {
            (res.get(i) as JSONObject).run {
                files.add(RemoteSolfa(getInt("id"),
                        getString("driveId"),
                        getString("name"),
                        getInt("dld"))
                )
            }
        }

        return files
    }


}