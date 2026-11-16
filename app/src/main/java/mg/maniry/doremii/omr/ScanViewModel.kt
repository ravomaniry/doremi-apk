package mg.maniry.doremii.omr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class ReviewVoice(val id: String, val text: String, val inferred: List<IntRange>)

private data class WindowCrop(val bitmap: Bitmap, val left: Int, val top: Int)

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

    private val ocr = TesseractOcr(application)
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
                captureMessage.value = error.message
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
        ocr.close()
        recycle(still)
        recycle(crop)
        recycle(annotated)
        super.onCleared()
    }

    private suspend fun read(cropped: Bitmap): ScanResult {
        quarterTurn = false
        draft = null
        val prepared = withContext(Dispatchers.Default) { ImagePreprocessor.prepare(cropped) }
        val plan = withContext(Dispatchers.Default) { PageRectifier.plan(prepared.ink) }
        val turned = if (abs(plan.rotationDegrees) < 0.5f) {
            cropped
        } else {
            withContext(Dispatchers.Default) { ImagePreprocessor.rotate(cropped, plan.rotationDegrees) }
        }
        val quad = plan.quad
        val active = if (quad == null) {
            turned
        } else {
            withContext(Dispatchers.Default) {
                ImagePreprocessor.warp(turned, quad, plan.outputWidth, plan.outputHeight)
            }
        }
        if (turned !== cropped && turned !== active) recycle(turned)
        val activePrepared = if (active === cropped) {
            prepared
        } else {
            recycle(prepared.ocr)
            withContext(Dispatchers.Default) { ImagePreprocessor.prepare(active) }
        }
        val readable = ImagePreprocessor.software(active)
        val borrowed = readable !== active
        val result = try {
            withContext(Dispatchers.Default) {
                SolfaGridDetector.scan(activePrepared.ink, null) { window ->
                    val slice = cropWindow(readable, window)
                    if (slice == null) {
                        emptyList()
                    } else {
                        try {
                            recognizeLines(slice.bitmap).map { line ->
                                shiftLine(line, slice.left.toFloat(), slice.top.toFloat())
                            }
                        } finally {
                            recycle(slice.bitmap)
                        }
                    }
                }
            }
        } finally {
            if (borrowed) recycle(readable)
        }
        recycle(activePrepared.ocr)
        val previous = crop
        crop = active
        if (previous != null && previous !== active && previous !== still) recycle(previous)
        quarterTurn = false
        return result
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

    private fun cropWindow(source: Bitmap, window: DebugWindow): WindowCrop? {
        if (source.isRecycled || source.width < 2 || source.height < 2) return null
        val left = window.left.roundToInt().coerceIn(0, source.width - 1)
        val top = window.top.roundToInt().coerceIn(0, source.height - 1)
        val right = window.right.roundToInt().coerceIn(left + 1, source.width)
        val bottom = window.bottom.roundToInt().coerceIn(top + 1, source.height)
        if (right - left < 8 || bottom - top < 8) return null
        val bitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, Rect(left, top, right, bottom), Rect(0, 0, right - left, bottom - top), null)
        }
        return WindowCrop(bitmap, left, top)
    }

    private fun shiftLine(line: OcrLine, dx: Float, dy: Float): OcrLine {
        return line.copy(
            left = line.left + dx,
            top = line.top + dy,
            right = line.right + dx,
            bottom = line.bottom + dy,
            symbols = line.symbols.map { symbol ->
                symbol.copy(
                    left = symbol.left + dx,
                    top = symbol.top + dy,
                    right = symbol.right + dx,
                    bottom = symbol.bottom + dy,
                )
            },
        )
    }

    private fun recognizeLines(bitmap: Bitmap): List<OcrLine> = ocr.recognize(bitmap)

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
