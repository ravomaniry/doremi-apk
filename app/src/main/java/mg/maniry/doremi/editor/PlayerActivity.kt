package mg.maniry.doremi.editor

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.widget.ImageView
import mg.maniry.doremi.R

class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.help_activity)

        findViewById<ImageView>(R.id.help_close_btn).setOnClickListener {
            finish()
        }
    }
}
