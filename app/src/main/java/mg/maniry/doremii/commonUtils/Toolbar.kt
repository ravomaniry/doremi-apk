package mg.maniry.doremii.commonUtils

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import mg.maniry.doremii.R


fun MaterialToolbar.navigateBack(activity: AppCompatActivity) {
    setNavigationOnClickListener { activity.finish() }
    navigationContentDescription = activity.getString(R.string.nav_back)
}
