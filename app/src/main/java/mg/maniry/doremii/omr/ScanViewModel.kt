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
import mg.maniry.doremii.editor.partition.PartitionData
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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
    val progress = MutableLiveData(0)
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
    private var ocrEngine: GoogleVisionOcr? = null
    private val shapes = SolfaGlyphReader()

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
            progress.value = 0
            busy.value = true
            captureMessage.value = null
            try {
                if (crop != null && crop !== cropped) recycle(crop)
                crop = cropped
                val result = read(cropped, signature)
                draft = result.draft
                parsed = result.parse
                parsedSignature = result.signature
                publishPreview(result.parse.debug)
                review.value = toState(result.parse, result.signature)
                phase.value = Phase.REVIEW
            } catch (error: Exception) {
                captureMessage.value = error.message
            } finally {
                busy.value = false
            }
        }
    }

    fun reparse(signature: Int) {
        val current = parsed ?: return
        val sig = signature.coerceIn(2, 6)
        if (sig == parsedSignature) return
        parsedSignature = sig
        review.value = toState(current, sig)
    }

    fun scoreVoices(signature: Int, edited: List<ParsedVoice>?): List<ParsedVoice> {
        if (edited != null) return edited
        val current = parsed ?: return emptyList()
        parsedSignature = signature.coerceIn(2, 6)
        return current.voices
    }

    override fun onCleared() {
        ocrEngine?.close()
        ocrEngine = null
        recycle(still)
        recycle(crop)
        recycle(annotated)
        super.onCleared()
    }

    private fun cellBoxes(
        systems: List<List<List<NoteCell>>>,
        recognized: List<String>,
    ): List<DebugNoteBox> {
        var index = 0
        return systems.flatMap { voices ->
            voices.mapIndexed { voiceIndex, cells ->
                val voiceId = PartitionData.voiceIds[voiceIndex % PartitionData.voiceIds.size]
                cells.map { cell ->
                    DebugNoteBox(
                        left = cell.left,
                        top = cell.top,
                        right = cell.right,
                        bottom = cell.bottom,
                        voiceIndex = voiceIndex,
                        voiceId = voiceId,
                        musical = true,
                        label = recognized.getOrElse(index++) { "" },
                    )
                }
            }.flatten()
        }
    }

    private fun report(percent: Int) {
        progress.postValue(percent.coerceIn(0, 100))
    }

    private fun readCellText(
        ink: InkImage,
        source: Bitmap,
        cell: NoteCell,
    ): String {
        val shaped = shapes.read(ink, cell)
        if (shaped.isNotEmpty()) return shaped
        if (!cellHasInk(ink, cell)) return ""
        return readCell(source, cell)
    }

    private fun cellHasInk(ink: InkImage, cell: NoteCell): Boolean {
        if (ink.width < 2 || ink.height < 2) return false
        val left = cell.left.roundToInt().coerceIn(0, ink.width - 1)
        val top = cell.top.roundToInt().coerceIn(0, ink.height - 1)
        val right = cell.right.roundToInt().coerceIn(left + 1, ink.width)
        val bottom = cell.bottom.roundToInt().coerceIn(top + 1, ink.height)
        val stepX = ((right - left) / 24).coerceIn(1, 2)
        val stepY = ((bottom - top) / 12).coerceIn(1, 2)
        var dark = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (ink.isDark(x, y)) {
                    dark++
                    if (dark >= 3) return true
                }
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun readCell(source: Bitmap, cell: NoteCell): String {
        val crop = cropCell(source, cell) ?: return ""
        return try {
            engine().readCell(crop)
        } catch (_: Exception) {
            ""
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun cropCell(source: Bitmap, cell: NoteCell): Bitmap? {
        if (source.isRecycled || source.width < 2 || source.height < 2) return null
        val gap = cell.right - cell.left
        val inset = min(2f, gap * 0.05f).coerceAtLeast(0f)
        val left = (cell.left + inset).roundToInt()
        val right = (cell.right - inset).roundToInt()
        val top = cell.top.roundToInt()
        val bottom = cell.bottom.roundToInt()
        val l = left.coerceIn(0, source.width - 1)
        val t = top.coerceIn(0, source.height - 1)
        val r = right.coerceIn(l + 1, source.width)
        val b = bottom.coerceIn(t + 1, source.height)
        if (r - l < 2 || b - t < 2) return null
        val out = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, Rect(l, t, r, b), Rect(0, 0, r - l, b - t), null)
        return out
    }

    private fun recognizeSymbols(bitmap: Bitmap): List<OcrSymbol> {
        return try {
            engine().symbols(bitmap)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun engine(): GoogleVisionOcr {
        return ocrEngine ?: GoogleVisionOcr().also { ocrEngine = it }
    }

    private suspend fun read(cropped: Bitmap, signature: Int): ScanResult {
        quarterTurn = false
        draft = null
        val prepared = withContext(Dispatchers.Default) { ImagePreprocessor.prepare(cropped) }
        report(5)
        val plan = withContext(Dispatchers.Default) { PageRectifier.plan(prepared.ink) }
        report(10)
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
        report(15)
        if (turned !== cropped && turned !== active) recycle(turned)
        val activePrepared = if (active === cropped) {
            prepared
        } else {
            prepared.release()
            withContext(Dispatchers.Default) { ImagePreprocessor.prepare(active) }
        }
        report(20)
        val result = try {
            withContext(Dispatchers.Default) {
                val symbols = recognizeSymbols(activePrepared.ocr)
                report(25)
                val glyphs = activePrepared.ink.copy()
                val ink = HorizontalRules.strip(activePrepared.ink, symbols)
                report(30)
                val lines = TextLineDetector.detect(ink, symbols)
                report(35)
                val blocks = TextBlockDetector.detect(ink, lines)
                report(40)
                val marks = LineMarks.find(ink, lines, blocks)
                report(45)
                val classified = MusicalLineClassifier.classify(lines, blocks, marks)
                report(50)
                val divided = BeatDividers.find(ink, classified.lines, classified.blocks, marks)
                report(55)
                val stored = ScanDraft(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    divided.lines,
                    classified.blocks,
                    marks,
                    divided.beats,
                )
                val detected = BeatSignature.detect(
                    divided.lines,
                    classified.blocks,
                    marks,
                    divided.beats,
                )
                report(60)
                val shown = detected ?: signature.coerceIn(2, 6)
                val systems = NoteCells.grid(divided.lines, classified.blocks, divided.beats)
                val total = systems.sumOf { system -> system.sumOf { it.size } }
                val recognized = mutableListOf<String>()
                var done = 0
                val voices = NoteCells.read(systems) { cell ->
                    val text = readCellText(glyphs, activePrepared.ocr, cell)
                    recognized += text
                    done += 1
                    report(if (total == 0) 100 else 60 + done * 40 / total)
                    text
                }
                if (total == 0) report(100)
                val empty = voices.isEmpty() || voices.all { voice -> voice.cells.all { it.isBlank() } }
                val parse = SolfaParse(
                    voices = voices,
                    keyIndex = null,
                    empty = empty,
                    debug = ScanDebug(
                        notes = cellBoxes(systems, recognized),
                        beats = divided.beats,
                        lines = divided.lines,
                        blocks = classified.blocks,
                        marks = marks,
                    ),
                )
                ScanResult(parse, shown, stored)
            }
        } finally {
            activePrepared.release()
        }
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
