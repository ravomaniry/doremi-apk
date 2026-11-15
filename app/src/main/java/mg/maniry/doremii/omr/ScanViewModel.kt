package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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

class ScanViewModel : ViewModel() {
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

    private var symbols: List<OcrSymbol> = emptyList()
    private var ink: InkImage? = null
    private var detected: DetectedGrid? = null
    private val cellText = mutableMapOf<CellKey, String>()
    private val regionText = mutableMapOf<CellKey, String>()
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
        symbols = emptyList()
        ink = null
        detected = null
        cellText.clear()
        regionText.clear()
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
                val result = read(cropped, signature)
                parsed = result
                parsedSignature = signature.coerceIn(2, 6)
                publishPreview(result.debug)
                review.value = toState(result, parsedSignature)
                phase.value = Phase.REVIEW
            } catch (error: Exception) {
                captureMessage.value = error.message
            } finally {
                busy.value = false
            }
        }
    }

    fun reparse(signature: Int) {
        val grid = detected
        val source = crop
        val sig = signature.coerceIn(2, 6)
        if (grid != null && source != null && !source.isRecycled) {
            viewModelScope.launch {
                val prepared = withContext(Dispatchers.Default) { ImagePreprocessor.prepare(source) }
                try {
                    val next = readCells(prepared.ocr, prepared.ink, grid, sig)
                    ink = prepared.ink
                    parsed = next
                    parsedSignature = sig
                    publishPreview(next.debug)
                    review.value = toState(next, sig)
                } finally {
                    recycle(prepared.ocr)
                }
            }
            return
        }
        if (symbols.isEmpty()) return
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) {
                SolfaSpatialParser.parse(symbols, sig, ink)
            }
            parsed = next
            parsedSignature = sig
            publishPreview(next.debug)
            review.value = toState(next, sig)
        }
    }

    fun scoreVoices(signature: Int, edited: List<ParsedVoice>?): List<ParsedVoice> {
        if (edited != null) return edited
        val sig = signature.coerceIn(2, 6)
        val current = parsed
        if (current != null && parsedSignature == sig) return current.voices
        val grid = detected
        val inkImage = ink
        if (grid != null && inkImage != null) {
            val next = GridScore.fromLayout(grid.layout(inkImage, sig), cellText, regionText)
            parsed = next
            parsedSignature = sig
            return next.voices
        }
        if (symbols.isEmpty()) return emptyList()
        val next = SolfaSpatialParser.parse(symbols, sig, ink)
        parsed = next
        parsedSignature = sig
        return next.voices
    }

    override fun onCleared() {
        recycle(still)
        recycle(crop)
        recycle(annotated)
        super.onCleared()
    }

    private suspend fun read(cropped: Bitmap, signature: Int): SolfaParse {
        quarterTurn = false
        detected = null
        cellText.clear()
        regionText.clear()
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
        val debug = try {
            withContext(Dispatchers.Default) {
                SolfaGridDetector.debug(activePrepared.ink) { window ->
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
        detected = null
        ink = activePrepared.ink
        symbols = emptyList()
        quarterTurn = false
        return SolfaParse(emptyList(), null, empty = true, debug = debug)
    }

    private suspend fun readCells(
        source: Bitmap,
        inkImage: InkImage,
        grid: DetectedGrid,
        signature: Int,
    ): SolfaParse {
        return withContext(Dispatchers.Default) {
            CellReader.read(source, inkImage, grid, signature, cellText, regionText, ::recognizeLines)
        }
    }

    private suspend fun legacy(cropped: Bitmap, signature: Int, existing: PreparedImage?): SolfaParse {
        quarterTurn = false
        val prepared = existing ?: withContext(Dispatchers.Default) { ImagePreprocessor.prepare(cropped) }
        var bestSymbols = recognizeSymbols(prepared.ocr)
        var bestInk = prepared.ink
        recycle(prepared.ocr)
        val retry = bestSymbols.isNotEmpty() &&
            (SolfaSpatialParser.looksSideways(bestSymbols) ||
                SolfaSpatialParser.musicRowCount(bestSymbols) == 0)
        if (retry) {
            val rotated = withContext(Dispatchers.Default) { ImagePreprocessor.rotate(cropped, 90) }
            val second = withContext(Dispatchers.Default) { ImagePreprocessor.prepare(rotated) }
            val symbols2 = recognizeSymbols(second.ocr)
            recycle(second.ocr)
            if (SolfaSpatialParser.musicRowCount(symbols2) > SolfaSpatialParser.musicRowCount(bestSymbols)) {
                bestSymbols = symbols2
                bestInk = second.ink
                quarterTurn = true
            }
            if (rotated !== cropped) recycle(rotated)
        }
        symbols = bestSymbols
        ink = bestInk
        detected = null
        return withContext(Dispatchers.Default) {
            SolfaSpatialParser.parse(bestSymbols, signature, bestInk)
        }
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

    private suspend fun recognizeSymbols(bitmap: Bitmap): List<OcrSymbol> {
        return recognizeLines(bitmap).flatMap { it.symbols }
    }

    private suspend fun recognizeLines(bitmap: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { vision ->
                    val lines = mutableListOf<OcrLine>()
                    for (block in vision.textBlocks) {
                        for (line in block.lines) {
                            val symbols = mutableListOf<OcrSymbol>()
                            for (element in line.elements) {
                                for (symbol in element.symbols) {
                                    val box = symbol.boundingBox ?: continue
                                    symbols.add(
                                        OcrSymbol(
                                            text = symbol.text,
                                            left = box.left.toFloat(),
                                            top = box.top.toFloat(),
                                            right = box.right.toFloat(),
                                            bottom = box.bottom.toFloat(),
                                            confidence = symbol.confidence,
                                        )
                                    )
                                }
                            }
                            val box = line.boundingBox
                            val left = box?.left?.toFloat() ?: symbols.minOfOrNull { it.left } ?: 0f
                            val top = box?.top?.toFloat() ?: symbols.minOfOrNull { it.top } ?: 0f
                            val right = box?.right?.toFloat() ?: symbols.maxOfOrNull { it.right } ?: left
                            val bottom = box?.bottom?.toFloat() ?: symbols.maxOfOrNull { it.bottom } ?: top
                            lines.add(OcrLine(line.text, left, top, right, bottom, symbols))
                        }
                    }
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(lines)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(emptyList())
                }
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
