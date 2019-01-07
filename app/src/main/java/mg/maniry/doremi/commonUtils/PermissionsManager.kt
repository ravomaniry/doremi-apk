package mg.maniry.doremi.commonUtils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat


class PermissionsManager(private var context: Activity) {

    private val storage = Manifest.permission.WRITE_EXTERNAL_STORAGE
    private val internet = Manifest.permission.INTERNET
    private val networkState = Manifest.permission.ACCESS_NETWORK_STATE
    private val granted = PackageManager.PERMISSION_GRANTED
    private var diskGranted = isGranted(storage)
    private var netGranted = isGranted(internet) && isGranted(networkState)
    private val diskReqCode = 1
    private val netReqCode = 2


    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == granted
    }


    fun disk() {
        if (!diskGranted) {
            ActivityCompat.requestPermissions(context, arrayOf(storage), diskReqCode)
        }
    }


    fun network() {
        if (!netGranted) {
            ActivityCompat.requestPermissions(context, arrayOf(internet, networkState), netReqCode)
        }
    }


    fun grantPermission(reqCode: Int, results: IntArray, onGranted: () -> Unit) {
        if (reqCode == diskReqCode) {
            if (results[0] == granted) {
                diskGranted = true
                onGranted()
            }

        } else if (reqCode == netReqCode) {
            if (results[0] == granted && results[1] == granted) {
                netGranted = true
            }
        }
    }
}
