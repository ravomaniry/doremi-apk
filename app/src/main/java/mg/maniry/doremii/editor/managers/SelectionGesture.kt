package mg.maniry.doremii.editor.managers

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ScrollView
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Press-and-drag selection on note cells. A move past the touch slop before
 * [MIN_PRESS_MS] is treated as a swipe and does not select. The parent scroller
 * is left free to intercept until the press has lasted long enough.
 */
class SelectionGesture(
    private val editorVM: EditorViewModel,
    private val score: ViewGroup,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(score.context).scaledTouchSlop
    private val density = score.resources.displayMetrics.density
    private val edgePx = 48f * density
    private val scrollStep = (24f * density).toInt().coerceAtLeast(1)
    private val nearestPx = 64f * density
    private val scroller: ScrollView? by lazy { findScrollView() }

    private var tracking = false
    private var selecting = false
    private var anchorVoice = 0
    private var anchorIndex = 0
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var activeView: View? = null
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val armSelection = Runnable {
        val view = activeView ?: return@Runnable
        beginSelection(view)
    }

    private val keepScrolling = object : Runnable {
        override fun run() {
            if (!selecting) return
            if (scrollToward(lastRawY)) {
                applyHit(lastRawX, lastRawY)
                handler.postDelayed(this, EDGE_SCROLL_MS)
            }
        }
    }

    fun onNoteTouch(voice: Int, index: Int, view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(armSelection)
                handler.removeCallbacks(keepScrolling)
                tracking = true
                selecting = false
                anchorVoice = voice
                anchorIndex = index
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
                activeView = view
                lastRawX = event.rawX
                lastRawY = event.rawY
                handler.postDelayed(armSelection, MIN_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> return onMove(view, event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTracking = tracking
                val wasSelecting = selecting
                val tap = event.actionMasked == MotionEvent.ACTION_UP &&
                    wasTracking && !wasSelecting && !pastSlop(event.rawX, event.rawY)
                if (wasSelecting) {
                    applyHit(event.rawX, event.rawY)
                } else if (tap) {
                    editorVM.moveCursor(anchorVoice, anchorIndex)
                }
                cancelTracking()
                return true
            }
        }
        return false
    }

    private fun onMove(view: View, event: MotionEvent): Boolean {
        if (!tracking) return false
        val elapsed = event.eventTime - downTime
        val moved = pastSlop(event.rawX, event.rawY)
        if (!selecting && moved && elapsed < MIN_PRESS_MS) {
            cancelTracking()
            return false
        }
        if (!selecting && elapsed >= MIN_PRESS_MS) {
            beginSelection(view)
        }
        if (selecting) {
            dragTo(view, event.rawX, event.rawY)
        }
        return true
    }

    private fun beginSelection(view: View) {
        if (!tracking || selecting) return
        selecting = true
        disallowScroll(view)
        editorVM.setSelection(anchorVoice, anchorIndex, anchorVoice, anchorIndex)
    }

    private fun dragTo(view: View, rawX: Float, rawY: Float) {
        disallowScroll(view)
        lastRawX = rawX
        lastRawY = rawY
        if (scrollToward(rawY)) {
            handler.removeCallbacks(keepScrolling)
            handler.postDelayed(keepScrolling, EDGE_SCROLL_MS)
        } else {
            handler.removeCallbacks(keepScrolling)
        }
        applyHit(rawX, rawY)
    }

    private fun applyHit(rawX: Float, rawY: Float) {
        val hit = noteAt(rawX, rawY) ?: return
        editorVM.setSelection(anchorVoice, anchorIndex, hit.voice, hit.index)
    }

    private fun pastSlop(rawX: Float, rawY: Float): Boolean {
        return abs(rawX - downX) > slop || abs(rawY - downY) > slop
    }

    private fun disallowScroll(view: View) {
        (view.parent as? ViewParent)?.requestDisallowInterceptTouchEvent(true)
        scroller?.requestDisallowInterceptTouchEvent(true)
    }

    private fun scrollToward(rawY: Float): Boolean {
        val scroll = scroller ?: return false
        val location = IntArray(2)
        scroll.getLocationOnScreen(location)
        val top = location[1].toFloat()
        val bottom = top + scroll.height
        val dy = when {
            rawY < top + edgePx -> -scrollStep
            rawY > bottom - edgePx -> scrollStep
            else -> return false
        }
        val before = scroll.scrollY
        scroll.scrollBy(0, dy)
        return scroll.scrollY != before
    }

    private fun noteAt(rawX: Float, rawY: Float): NoteCellRef? {
        var containing: NoteCellRef? = null
        var nearest: NoteCellRef? = null
        var nearestDist = Float.MAX_VALUE
        val x = rawX.toInt()
        val y = rawY.toInt()
        walk(score) { child ->
            val ref = child.tag as? NoteCellRef ?: return@walk
            if (!child.isShown) return@walk
            val rect = Rect()
            if (!child.getGlobalVisibleRect(rect) || rect.isEmpty) return@walk
            if (containing == null && rect.contains(x, y)) {
                containing = ref
            }
            val distance = hypot(rect.centerX() - rawX, rect.centerY() - rawY)
            if (distance < nearestDist) {
                nearestDist = distance
                nearest = ref
            }
        }
        if (containing != null) return containing
        if (nearest != null && nearestDist <= nearestPx) return nearest
        return null
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), visit)
            }
        }
    }

    private fun findScrollView(): ScrollView? {
        var parent = score.parent
        while (parent != null) {
            if (parent is ScrollView) return parent
            parent = parent.parent
        }
        return null
    }

    private fun cancelTracking() {
        handler.removeCallbacks(armSelection)
        handler.removeCallbacks(keepScrolling)
        tracking = false
        selecting = false
        activeView = null
    }

    private companion object {
        const val MIN_PRESS_MS = 200L
        const val EDGE_SCROLL_MS = 16L
    }
}
