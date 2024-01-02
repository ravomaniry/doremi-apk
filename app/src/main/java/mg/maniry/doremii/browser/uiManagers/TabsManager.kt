package mg.maniry.doremii.browser.uiManagers

import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.LinearLayout
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.BrowserActivity


class TabsManager(
    val context: BrowserActivity,
    val mainView: View,
    private val remoteBrowserManager: RemoteBrowserManager
) {
    private val tabCont = mainView.findViewById<LinearLayout>(R.id.browser_tab_body_cont)
    private val localSearchForm = mainView.findViewById<LinearLayout>(R.id.local_search_form)
    private val remoteTabCont = remoteBrowserManager.remoteTab
    private val remoteSearchForm = mainView.findViewById<LinearLayout>(R.id.remote_search_form)
    private val localTabCont = mainView.findViewById<RecyclerView>(R.id.browser_files_list)
    // private val remoteTabBtn = mainView.findViewById<LinearLayout>(R.id.browser_online_tab_btn)
    // private val localTabBtn = mainView.findViewById<LinearLayout>(R.id.browser_local_tab_btn)

    init {
        refreshTab()
        //  listenBtnClick()
    }

    //    private fun listenBtnClick() {
    //        localTabBtn.setOnClickListener {
    //            if (context.activeTab != 0) {
    //                context.activeTab = 0
    //                refreshTab(true)
    //            }
    //        }
    // Remote server does not exist anymore
    //        remoteTabBtn.setOnClickListener {
    //            if (context.activeTab != 1) {
    //                context.activeTab = 1
    //                refreshTab(true)
    //            }
    //        }
    //    }


    private fun refreshTab() {
        tabCont.removeAllViews()
        val activeTab = context.activeTab
//        searchFormsCont.setBackgroundColor(if (activeTab == 0) primDark else secDark)
        localSearchForm.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
        remoteSearchForm.visibility = if (activeTab == 1) View.VISIBLE else View.GONE

        if (activeTab == 0) {
            tabCont.addView(localTabCont)
            remoteBrowserManager.deactivate()
            context.viewModel?.refreshFilesList()
        } else {
            tabCont.addView(remoteTabCont)
            remoteBrowserManager.activate()
        }
    }
}