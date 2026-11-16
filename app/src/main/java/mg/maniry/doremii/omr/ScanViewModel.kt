package mg.maniry.doremii.omr

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.maniry.doremii.R

data class ReviewVoice(val id: String, val text: String, val inferred: List<IntRange>)

data class ReviewState(
    val generation: Int,
    val signature: Int,
    val keyIndex: Int,
    val voices: List<ReviewVoice>,
    val empty: Boolean,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    enum class Phase { CAMERA, ADJUST, REVIEW }

    val phase = MutableLiveData(Phase.CAMERA)
    val busy = MutableLiveData(false)
    val captureMessage = MutableLiveData<String?>(null)
    val review = MutableLiveData<ReviewState?>(null)
    val preview = MutableLiveData<Bitmap?>(null)

    var still: Bitmap? = null
        private set
    var crop: Bitmap? = null
        private set

    private var draft: ScanDraft? = null
    private var parsed: SolfaParse? = null
    private var parsedSignature = 4
    private var generation = 0
    private var quarterTurn = false
    private var annotated: Bitmap? = null

    fun setStill(bitmap: Bitmap) {
        if (still != null && still !== bitmap && still?.isRecycled == false) still?.recycle()
        still = bitmap
        phase.value = Phase.ADJUST
    }

    fun retake() {
        recycle(still)
        recycle(crop)
        still = null
        crop = null
        draft = null
        parsed = null
        review.value = null
        quarterTurn = false
        phase.value = Phase.CAMERA
        preview.value = null
        recycle(annotated)
        annotated = null
    }

    fun recognize(cropped: Bitmap, signature: Int) {
        viewModelScope.launch {
            busy.value = true
            captureMessage.value = null
            try {
                if (crop != null && crop !== cropped) recycle(crop)
                crop = cropped
                val result = read(cropped)
                draft = result.draft
                parsed = result.parse
                val shown = if (result.draft.boxes.isEmpty()) signature.coerceIn(2, 6) else result.signature
                parsedSignature = shown
                publishPreview(result.parse.debug)
                review.value = toState(result.parse, shown)
                phase.value = Phase.REVIEW
            } catch (error: Exception) {
                captureMessage.value = error.message?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.scan_failed)
            } finally {
                busy.value = false
            }
        }
    }

    fun reparse(signature: Int) {
        val stored = draft ?: return
        val sig = signature.coerceIn(2, 6)
        viewModelScope.launch {
            val finished = withContext(Dispatchers.Default) { MeasureDelimiters.finish(stored, sig) }
            parsed = finished.parse
            parsedSignature = finished.signature
            publishPreview(finished.parse.debug)
            review.value = toState(finished.parse, finished.signature)
        }
    }

    fun scoreVoices(signature: Int, edited: List<ParsedVoice>?): List<ParsedVoice> {
        if (edited != null) return edited
        val sig = signature.coerceIn(2, 6)
        val current = parsed
        if (current != null && parsedSignature == sig) return current.voices
        val stored = draft ?: return current?.voices ?: emptyList()
        val finished = MeasureDelimiters.finish(stored, sig)
        parsed = finished.parse
        parsedSignature = finished.signature
        return finished.parse.voices
    }

    override fun onCleared() {
        recycle(still)
        recycle(crop)
        recycle(annotated)
        super.onCleared()
    }

    private suspend fun read(cropped: Bitmap): ScanResult {
        quarterTurn = false
        draft = null
        if (!OpenCvSolfa.ensureLoaded()) {
            throw IllegalStateException(getApplication<Application>().getString(R.string.scan_failed))
        }
        val scan = withContext(Dispatchers.Default) { OpenCvSolfa.read(cropped) }
        val previous = crop
        crop = scan.page
        if (previous != null && previous !== scan.page && previous !== still) recycle(previous)
        return scan.result
    }

    private suspend fun publishPreview(debug: ScanDebug) {
        val source = crop ?: return
        if (source.isRecycled) return
        val turn = quarterTurn
        val next = withContext(Dispatchers.Default) {
            if (source.isRecycled) source else ScanDebugPainter.paint(source, debug, turn)
        }
        val previous = annotated
        annotated = if (next === source) null else next
        preview.value = next
        if (previous != null && previous !== next && previous !== source) recycle(previous)
    }

    private fun toState(parse: SolfaParse, signature: Int): ReviewState {
        generation += 1
        val empty = parse.empty || parse.voices.isEmpty()
        val voices = if (empty) {
            val blank = PrintedSolfaFormatter.format(emptyList(), signature)
            listOf(ReviewVoice("S", blank.text, emptyList()))
        } else {
            parse.voices.map { voice ->
                val formatted = PrintedSolfaFormatter.format(
                    voice.cells,
                    signature,
                    voice.inferredBeats.toSet(),
                )
                ReviewVoice(voice.id, formatted.text, formatted.inferredRanges)
            }
        }
        return ReviewState(
            generation = generation,
            signature = signature,
            keyIndex = parse.keyIndex ?: 0,
            voices = voices,
            empty = empty,
        )
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }
}
