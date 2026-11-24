package mg.maniry.doremii.editor.managers

import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.LifecycleOwner
import mg.maniry.doremii.R
import mg.maniry.doremii.editor.partition.Player
import mg.maniry.doremii.editor.partition.playheadSpan
import mg.maniry.doremii.editor.partition.soundingVoices
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.views.PlaybackPianoView

/** Moves the score playhead and the piano lights with the playback clock. */
class PlaybackFollow(
    owner: LifecycleOwner,
    mainView: View,
    private val editorViewModel: EditorViewModel,
    private val player: Player,
    private val score: SolfaDisplayManager,
) {
    private val piano = mainView.findViewById<PlaybackPianoView>(R.id.playback_piano)
    private val choreographer = Choreographer.getInstance()
    private var shownNotes: List<*>? = null
    private var anchoredPosition = 0L
    private var anchoredAt = 0L
    private var tracking = false
    private var running = false
    private val frame: Choreographer.FrameCallback = Choreographer.FrameCallback {
        if (!running || editorViewModel.playSession.value != true) return@FrameCallback
        follow()
        if (running) choreographer.postFrameCallback(frame)
    }

    init {
        editorViewModel.playSession.observe(owner) { active ->
            choreographer.removeFrameCallback(frame)
            tracking = false
            running = active == true
            if (running) {
                shownNotes = null
                choreographer.postFrameCallback(frame)
            } else {
                shownNotes = null
                piano.clear()
                score.setPlayhead(null)
            }
        }
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frame)
    }

    private fun follow() {
        val clock = player.playbackClock() ?: return
        if (shownNotes !== clock.notes) {
            shownNotes = clock.notes
            piano.show(clock.notes)
        }
        val position = heardPosition()
        score.setPlayhead(playheadSpan(clock.visits, position, clock.tempo, clock.signature))
        piano.setActive(soundingVoices(clock.notes, position, clock.tempo))
    }

    /**
     * Score time minus speaker delay. Between audio buffers the display keeps moving
     * with the wall clock, but never more than one buffer ahead, and it snaps when
     * playback is paused, still silent, or jumped by a seek.
     */
    private fun heardPosition(): Long {
        val heard = (player.positionMs() - player.outputLatencyMs()).coerceAtLeast(0L)
        val now = SystemClock.uptimeMillis()
        if (!tracking || player.isPlaybackPaused() || heard == 0L) {
            tracking = true
            anchoredPosition = heard
            anchoredAt = now
            return heard
        }
        val step = heard - anchoredPosition
        if (step < -WOBBLE_MS || step > SEEK_JUMP_MS) {
            anchoredPosition = heard
            anchoredAt = now
            return heard
        }
        if (step > 0L) {
            val shown = anchoredPosition + elapsedSince(now)
            val ahead = (shown - heard).coerceIn(0L, BUFFER_MS)
            anchoredPosition = heard
            anchoredAt = now - ahead
            return heard + ahead
        }
        return anchoredPosition + elapsedSince(now)
    }

    private fun elapsedSince(now: Long): Long {
        return (now - anchoredAt).coerceIn(0L, BUFFER_MS)
    }

    private companion object {
        /** Matches the synth output buffer. Glide at most this far past the last sample. */
        const val BUFFER_MS = 40L
        const val SEEK_JUMP_MS = BUFFER_MS * 2
        const val WOBBLE_MS = 15L
    }
}
