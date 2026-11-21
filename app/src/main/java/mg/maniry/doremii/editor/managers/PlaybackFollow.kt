package mg.maniry.doremii.editor.managers

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.LifecycleOwner
import mg.maniry.doremii.R
import mg.maniry.doremii.editor.partition.Player
import mg.maniry.doremii.editor.partition.measureAtPosition
import mg.maniry.doremii.editor.partition.soundingVoices
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.views.PlaybackPianoView

/** Moves the score bar and the piano lights with the playback clock. */
class PlaybackFollow(
    owner: LifecycleOwner,
    mainView: View,
    private val editorViewModel: EditorViewModel,
    private val player: Player,
    private val score: SolfaDisplayManager,
) {
    private val piano = mainView.findViewById<PlaybackPianoView>(R.id.playback_piano)
    private val handler = Handler(Looper.getMainLooper())
    private var shownNotes: List<*>? = null
    private val tick = object : Runnable {
        override fun run() {
            if (editorViewModel.playSession.value != true) return
            follow()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    init {
        editorViewModel.playSession.observe(owner) { active ->
            handler.removeCallbacks(tick)
            if (active == true) {
                shownNotes = null
                handler.post(tick)
            } else {
                shownNotes = null
                piano.clear()
                score.setPlayheadMeasure(null)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    private fun follow() {
        val clock = player.playbackClock() ?: return
        if (shownNotes !== clock.notes) {
            shownNotes = clock.notes
            piano.show(clock.notes)
        }
        val position = player.positionMs()
        score.setPlayheadMeasure(measureAtPosition(clock.visits, position, clock.tempo, clock.signature))
        piano.setActive(soundingVoices(clock.notes, position, clock.tempo))
    }

    private companion object {
        const val FRAME_MS = 32L
    }
}
