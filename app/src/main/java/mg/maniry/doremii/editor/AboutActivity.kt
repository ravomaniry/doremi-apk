package mg.maniry.doremii.editor

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.ImageView
import mg.maniry.doremii.R

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_activity)
        handleClose()
    }

    private fun handleClose() {
        findViewById<ImageView>(R.id.dialog_close_btn).setOnClickListener {
            finish()
        }
    }
}
