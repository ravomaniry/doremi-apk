package mg.maniry.doremi.browser.uiManagers

import android.graphics.Color
import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.LinearLayout
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.BrowserActivity


class TabsManager(
        val context: BrowserActivity,
        val mainView: View,
        private val remoteBrowserManager: RemoteBrowserManager) {

    private val primDark = Color.rgb(48, 63, 159)
    private val secDark = Color.rgb(197, 41, 37)

    private val searchFormsCont = mainView.findViewById<LinearLayout>(R.id.search_forms_cont)
    private val tabCont = mainView.findViewById<LinearLayout>(R.id.browser_tab_body_cont)

    private val localSearchForm = mainView.findViewById<LinearLayout>(R.id.local_search_form)
    private val localTabBtn = mainView.findViewById<LinearLayout>(R.id.browser_local_tab_btn)
    private val localTabCont = mainView.findViewById<RecyclerView>(R.id.browser_files_list)

    private val remoteSearchForm = mainView.findViewById<LinearLayout>(R.id.remote_search_form)
    private val remoteTabBtn = mainView.findViewById<LinearLayout>(R.id.browser_online_tab_btn)
    private val remoteTabCont = remoteBrowserManager.remoteTab


    init {
        refreshTab()
        listenBtnClick()
    }


    private fun listenBtnClick() {
        localTabBtn.setOnClickListener {
            if (context.activeTab != 0) {
                context.activeTab = 0
                refreshTab(true)
            }
        }

        remoteTabBtn.setOnClickListener {
            if (context.activeTab != 1) {
                context.activeTab = 1
                refreshTab(true)
            }
        }
    }


    private fun refreshTab(refreshList: Boolean = false) {
        tabCont.removeAllViews()
        val activeTab = context.activeTab

        searchFormsCont.setBackgroundColor(if (activeTab == 0) primDark else secDark)
        localSearchForm.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
        remoteSearchForm.visibility = if (activeTab == 1) View.VISIBLE else View.GONE

        if (activeTab == 0) {
            tabCont.addView(localTabCont)
            remoteBrowserManager.deactivate()

            if (refreshList)
                context.viewModel?.refreshFilesList()
        } else {
            tabCont.addView(remoteTabCont)
            remoteBrowserManager.activate()
        }
    }

}