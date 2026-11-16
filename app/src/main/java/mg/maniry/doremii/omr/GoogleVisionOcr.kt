package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.roundToInt

class GoogleVisionOcr {
    private val lock = Any()
    private var recognizer: TextRecognizer? = null

    fun symbols(bitmap: Bitmap): List<OcrSymbol> {
        val vision = recognize(bitmap) ?: return emptyList()
        return symbolsOf(vision)
    }

    fun readCell(bitmap: Bitmap): String {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return ""
        val canvas = try {
            cellCanvas(bitmap)
        } catch (_: Exception) {
            return ""
        }
        try {
            val vision = recognize(canvas) ?: return ""
            return symbolsOf(vision).joinToString("") { it.plain() }.filter { it !in barMarks }
        } catch (_: Exception) {
            return ""
        } finally {
            if (!canvas.isRecycled) canvas.recycle()
        }
    }

    fun close() {
        val current = synchronized(lock) {
            val engine = recognizer
            recognizer = null
            engine
        }
        current?.close()
    }

    private fun recognize(bitmap: Bitmap): Text? {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return null
        return try {
            synchronized(lock) {
                val image = InputImage.fromBitmap(bitmap, 0)
                Tasks.await(client().process(image))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun client(): TextRecognizer {
        recognizer?.let { return it }
        val created = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer = created
        return created
    }

    private fun symbolsOf(vision: Text): List<OcrSymbol> {
        val found = mutableListOf<OcrSymbol>()
        for (block in vision.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val glyphs = element.symbols
                    if (glyphs.isEmpty()) {
                        found += symbol(
                            element.text,
                            element.boundingBox,
                            confidenceOf(element.confidence),
                        )
                    } else {
                        for (glyph in glyphs) {
                            found += symbol(
                                glyph.text,
                                glyph.boundingBox ?: element.boundingBox,
                                confidenceOf(glyph.confidence ?: element.confidence),
                            )
                        }
                    }
                }
            }
        }
        return found
    }

    private fun symbol(raw: String, box: Rect?, confidence: Float): List<OcrSymbol> {
        val text = OcrGlyphs.accept(raw)
        if (text.isEmpty() || box == null) return emptyList()
        if (box.right <= box.left || box.bottom <= box.top) return emptyList()
        return OcrGlyphs.split(
            OcrSymbol(
                text = text,
                left = box.left.toFloat(),
                top = box.top.toFloat(),
                right = box.right.toFloat(),
                bottom = box.bottom.toFloat(),
                confidence = confidence,
            )
        )
    }

    private fun confidenceOf(value: Float?): Float {
        val score = value ?: return 1f
        return if (score < 0f) 1f else score
    }

    private fun cellCanvas(source: Bitmap): Bitmap {
        val contentHeight = source.height.coerceAtLeast(1)
        var scale = if (contentHeight >= 96) 1f else (96f / contentHeight).coerceAtMost(4f)
        if (source.width * scale > 1600f) scale = (1600f / source.width).coerceAtLeast(1f)
        val pad = 32
        val destWidth = max(1, (source.width * scale).roundToInt())
        val destHeight = max(1, (contentHeight * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(destWidth + pad * 2, destHeight + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            source,
            null,
            Rect(pad, pad, pad + destWidth, pad + destHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return bitmap
    }

    private companion object {
        const val barMarks = ":;|"
    }
}
