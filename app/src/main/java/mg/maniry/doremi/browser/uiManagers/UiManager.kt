package mg.maniry.doremi.browser.uiManagers

import android.content.Context
import android.view.View
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.BrowserActivity


class UiManager(
        val context: Context,
        val mainView: View) {

    init {
        FilesBrowserManager(context as BrowserActivity, mainView)
        val remoteTab = View.inflate(context, R.layout.remote_browser_view, null)
        val remote = RemoteBrowserManager(context, mainView, remoteTab)
        TabsManager(context, mainView, remote)
    }
}