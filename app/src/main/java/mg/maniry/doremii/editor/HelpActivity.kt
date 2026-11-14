package mg.maniry.doremii.editor

import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import mg.maniry.doremii.R


class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.help_activity)

        findViewById<ImageView>(R.id.help_close_btn).setOnClickListener {
            finish()
        }
    }
}