package mg.maniry.doremi.browser.adapters

import android.annotation.SuppressLint
import android.support.v7.view.menu.MenuBuilder
import android.view.MenuItem


@SuppressLint("RestrictedApi")
fun MenuBuilder.onSelect(cb: ((MenuItem) -> Unit)) {
    setCallback(object : MenuBuilder.Callback {
        override fun onMenuModeChange(p0: MenuBuilder?) {}

        override fun onMenuItemSelected(menu: MenuBuilder?, item: MenuItem?): Boolean {
            if (item == null)
                return false
            cb(item)
            return true
        }
    })
}