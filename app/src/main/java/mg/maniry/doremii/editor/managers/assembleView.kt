package mg.maniry.doremii.editor.managers

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import mg.maniry.doremii.R


fun inflateMainView(context: Context): View {
    val mainView = View.inflate(context, R.layout.editor_activity, null)
    val editorView = View.inflate(context, R.layout.editor_body_view, null)
    val appBarCont = editorView.findViewById<LinearLayout>(R.id.appbar_cont)
    val appBarView = View.inflate(context, R.layout.bottom_app_bar, null)
    val drawerHeaderView = View.inflate(context, R.layout.menu_header, null)
    appBarCont.addView(appBarView)
    mainView.findViewById<FrameLayout>(R.id.editor_cont).addView(editorView)
    mainView.findViewById<LinearLayout>(R.id.drawer_inner_cont).addView(drawerHeaderView)
    return mainView
}
