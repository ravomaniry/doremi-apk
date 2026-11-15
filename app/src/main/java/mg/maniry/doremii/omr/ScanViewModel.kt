package mg.maniry.doremii.omr

import android.graphics.Bitmap
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

data class ReviewVoice(val id: String, val text: String, val inferred: List<IntRange>)

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

    var still: Bitmap? = null
        private set
    var crop: Bitmap? = null
        private set

    private var symbols: List<OcrSymbol> = emptyList()
    private var ink: InkImage? = null
    private var parsed: SolfaParse? = null
    private var parsedSignature = 4
    private var generation = 0

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
        parsed = null
        review.value = null
        phase.value = Phase.CAMERA
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
        if (symbols.isEmpty()) return
        val sig = signature.coerceIn(2, 6)
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) {
                SolfaSpatialParser.parse(symbols, sig, ink)
            }
            parsed = next
            parsedSignature = sig
            review.value = toState(next, sig)
        }
    }

    fun scoreVoices(signature: Int, edited: List<ParsedVoice>?): List<ParsedVoice> {
        if (edited != null) return edited
        val sig = signature.coerceIn(2, 6)
        val current = parsed
        if (current != null && parsedSignature == sig) return current.voices
        if (symbols.isEmpty()) return emptyList()
        val next = SolfaSpatialParser.parse(symbols, sig, ink)
        parsed = next
        parsedSignature = sig
        return next.voices
    }

    override fun onCleared() {
        recycle(still)
        recycle(crop)
        super.onCleared()
    }

    private suspend fun read(cropped: Bitmap, signature: Int): SolfaParse {
        val prepared = withContext(Dispatchers.Default) { ImagePreprocessor.prepare(cropped) }
        var bestSymbols = recognizeSymbols(prepared.ocr)
        var bestInk = prepared.ink
        recycle(prepared.ocr)
        val retry = bestSymbols.isNotEmpty() &&
            (SolfaSpatialParser.looksSideways(bestSymbols) ||
                SolfaSpatialParser.musicRowCount(bestSymbols) == 0)
        if (retry) {
            val rotated = withContext(Dispatchers.Default) { ImagePreprocessor.rotate(cropped, 90) }
            val second = withContext(Dispatchers.Default) { ImagePreprocessor.prepare(rotated) }
            if (rotated !== cropped) recycle(rotated)
            val symbols2 = recognizeSymbols(second.ocr)
            recycle(second.ocr)
            if (SolfaSpatialParser.musicRowCount(symbols2) > SolfaSpatialParser.musicRowCount(bestSymbols)) {
                bestSymbols = symbols2
                bestInk = second.ink
            }
        }
        symbols = bestSymbols
        ink = bestInk
        return withContext(Dispatchers.Default) {
            SolfaSpatialParser.parse(bestSymbols, signature, bestInk)
        }
    }

    private suspend fun recognizeSymbols(bitmap: Bitmap): List<OcrSymbol> =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { vision ->
                    val found = mutableListOf<OcrSymbol>()
                    for (block in vision.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                for (symbol in element.symbols) {
                                    val box = symbol.boundingBox ?: continue
                                    found.add(
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
                        }
                    }
                    recognizer.close()
                    if (continuation.isActive) continuation.resume(found)
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
