package mg.maniry.doremi.ui.managers

import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import mg.maniry.doremi.R
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.ui.views.AboutDialog
import mg.maniry.doremi.ui.views.HelpDialog
import mg.maniry.doremi.viewModels.EditorViewModel


class BottomButtonsManager(
        private val mainContext: MainActivity,
        mainView: View,
        private val editorViewModel: EditorViewModel) {

    private val helpDialog = HelpDialog()
    private val aboutDialog = AboutDialog()


    init {
        val buttonsCont = View.inflate(mainContext, R.layout.bottom_buttons, null)
        mainView.findViewById<LinearLayout>(R.id.lyrics_cont)
                .addView(buttonsCont)

        with(buttonsCont) {
            findViewById<ImageButton>(R.id.help_btn)
                    .setOnClickListener {
                        helpDialog.show(mainContext.supportFragmentManager, "help_dialog")
                    }

            findViewById<ImageButton>(R.id.about_btn)
                    .setOnClickListener {
                        aboutDialog.show(mainContext.supportFragmentManager, "about_dialog")
                    }

            findViewById<ImageButton>(R.id.print_btn)
                    .setOnClickListener {
                        editorViewModel.print { filename ->
                            notify("Succès: /doremi_export/$filename.html")
                        }
                    }
        }
    }


    private fun notify(s: String) {
        Toast.makeText(mainContext, s, Toast.LENGTH_LONG).show()
    }
}