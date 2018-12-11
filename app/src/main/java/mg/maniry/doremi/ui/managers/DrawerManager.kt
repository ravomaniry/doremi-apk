package mg.maniry.doremi.ui.managers


import android.support.v4.view.GravityCompat
import android.support.v4.widget.DrawerLayout
import android.view.KeyEvent
import android.view.View
import mg.maniry.doremi.R


class DrawerManager(private val mainView: View) {

    private var drawer = mainView.findViewById<DrawerLayout>(R.id.drawer_layout)


    init {
        handleDrawerClicks()
        handleKeyboardVisibility()
    }


    private fun handleDrawerClicks() {
        mainView.findViewById<View>(R.id.drawer_shower_btn).setOnClickListener { openDrawer() }
        mainView.findViewById<View>(R.id.drawer_hider).setOnClickListener { closeDrawer() }
    }


    private fun openDrawer(): Boolean {
        drawer.openDrawer(GravityCompat.START, true)
        return true
    }


    fun closeDrawer(): Boolean {
        drawer.closeDrawer(GravityCompat.START, true)
        return true
    }


    private fun handleKeyboardVisibility() {
        drawer.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerStateChanged(p0: Int) {}

            override fun onDrawerSlide(p0: View, p1: Float) {}

            override fun onDrawerOpened(p0: View) {}

            override fun onDrawerClosed(p0: View) {
                mainView.hideKeyboard()
            }
        })
    }


    fun handleButtonPress(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_MENU -> if (!drawer.isDrawerOpen(GravityCompat.START)) openDrawer() else closeDrawer()
        KeyEvent.KEYCODE_BACK -> if (drawer.isDrawerOpen(GravityCompat.START)) closeDrawer() else false
        else -> false
    }
}