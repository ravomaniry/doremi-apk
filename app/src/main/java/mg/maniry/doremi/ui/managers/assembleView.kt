package mg.maniry.doremi.ui.managers

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import mg.maniry.doremi.R


fun inflateMainView(context: Context): View {
    val mainView = View.inflate(context, R.layout.activity_main, null)

    val editorView = View.inflate(context, R.layout.editor_body_view, null)
    val appBarCont = editorView.findViewById<LinearLayout>(R.id.appbar_cont)
    val appBarView = View.inflate(context, R.layout.app_bar, null)
    val drawerHeaderView = View.inflate(context, R.layout.menu_header, null)
    val tabsView = View.inflate(context, R.layout.menu_tabs, null)

    appBarCont.addView(appBarView)
    mainView.findViewById<LinearLayout>(R.id.editor_cont).addView(editorView)
    mainView.findViewById<LinearLayout>(R.id.drawer_inner_cont).apply {
        addView(drawerHeaderView)
        addView(tabsView)
    }

    return mainView
}
