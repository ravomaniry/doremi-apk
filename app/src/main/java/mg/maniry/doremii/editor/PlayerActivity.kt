package mg.maniry.doremii.editor

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import mg.maniry.doremii.R
import mg.maniry.doremii.commonUtils.navigateBack

class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.help_activity)
        findViewById<MaterialToolbar>(R.id.screen_toolbar).navigateBack(this)
    }
}
