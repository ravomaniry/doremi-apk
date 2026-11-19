package mg.maniry.doremii.browser.uiManagers

import android.content.Context
import android.view.View
import mg.maniry.doremii.browser.BrowserActivity


class UiManager(
    val context: Context,
    val mainView: View
) {

    init {
        FilesBrowserManager(context as BrowserActivity, mainView)
    }
}
