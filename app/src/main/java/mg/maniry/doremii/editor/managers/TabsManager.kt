package mg.maniry.doremii.editor.managers

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import mg.maniry.doremii.R

class TabsManager(mainContext: Context, mainView: View) {
    private val contentContainer = mainView.findViewById<LinearLayout>(R.id.tab_body)
    val editorTab: View = View.inflate(mainContext, R.layout.editor_details_drawer, null)

    init {
        contentContainer.addView(editorTab)
    }
}
