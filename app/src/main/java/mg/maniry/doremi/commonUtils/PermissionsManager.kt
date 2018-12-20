package mg.maniry.doremi.commonUtils

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat


class PermissionsManager(private var context: Activity) {

    private val storage = Manifest.permission.WRITE_EXTERNAL_STORAGE
    private val granted = PackageManager.PERMISSION_GRANTED
    private var diskGranted = isGranted(storage)
    private val diskReqCode = 1


    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == granted
    }

    init {
        disk()
    }


    private fun disk() {
        if (!diskGranted) {
            ActivityCompat.requestPermissions(context, arrayOf(storage), diskReqCode)
        }
    }


    fun grantPermission(reqCode: Int, results: IntArray, onGranted: () -> Unit) {
        if (reqCode == diskReqCode) {
            if (results[0] == granted) {
                diskGranted = true
                onGranted()
            }
        }
    }
}
