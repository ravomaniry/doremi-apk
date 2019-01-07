package mg.maniry.doremi.editor.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.support.v4.content.ContextCompat
import android.view.View
import android.widget.LinearLayout
import mg.maniry.doremi.R
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.viewModels.UiViewModel


class TabsManager(
        private val mainContext: Context,
        private val mainView: View,
        private val uiViewModel: UiViewModel) {


    private val contentContainer = mainView.findViewById<LinearLayout>(R.id.tab_body)
    val editorTab: View = View.inflate(mainContext, R.layout.editor_tab, null)
    val songTab: View = View.inflate(mainContext, R.layout.songs_tab, null)
    private lateinit var tabSelectors: List<View>

    private val primaryColor = ContextCompat.getColor(mainContext, R.color.colorAccent)
    private val darkColor = ContextCompat.getColor(mainContext, R.color.colorAccentDark)


    init {
        initTabs()
    }


    private fun initTabs() {
        tabSelectors = listOf(mainView.findViewById(R.id.editor_tab_selector),
                mainView.findViewById(R.id.song_tab_selector))

        uiViewModel.drawerTab.observe(mainContext as EditorActivity, Observer(this::changeTabHandler))

        tabSelectors.forEachIndexed { i, sel ->
            sel.setOnClickListener { uiViewModel.changeTab(i) }
        }
    }


    private fun changeTabHandler(index: Int?) {
        tabSelectors.forEachIndexed { i, selector -> selector.setBackgroundColor(if (i == index) darkColor else primaryColor) }

        val activeTab = when (index) {
            1 -> songTab
            else -> editorTab
        }

        contentContainer.apply {
            removeAllViews()
            addView(activeTab)
            hideKeyboard()
        }
    }
}
