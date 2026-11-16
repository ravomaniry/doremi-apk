package mg.maniry.doremii.omr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

class TesseractOcr(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var api: TessBaseAPI? = null

    fun recognize(bitmap: Bitmap): List<OcrLine> {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return emptyList()
        val canvases = ImagePreprocessor.recognitionCanvases(bitmap)
        if (canvases.isEmpty()) return emptyList()
        try {
            synchronized(lock) {
                val engine = ensureReady()
                val symbols = mutableListOf<OcrSymbol>()
                for (canvas in canvases) {
                    try {
                        symbols += readCanvas(engine, canvas)
                    } catch (_: Exception) {
                        engine.clear()
                    }
                }
                return toLines(symbols)
            }
        } finally {
            for (canvas in canvases) {
                if (!canvas.bitmap.isRecycled) canvas.bitmap.recycle()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            api?.recycle()
            api = null
        }
    }

    private fun readCanvas(engine: TessBaseAPI, canvas: OcrCanvas): List<OcrSymbol> {
        val mode = if (canvas.singleLine) {
            TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        } else {
            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        }
        val found = readSymbols(engine, canvas, mode)
        if (found.isNotEmpty() || !canvas.singleLine) return found
        return readSymbols(engine, canvas, TessBaseAPI.PageSegMode.PSM_RAW_LINE)
    }

    private fun readSymbols(engine: TessBaseAPI, canvas: OcrCanvas, mode: Int): List<OcrSymbol> {
        val image = canvas.bitmap
        if (image.isRecycled) return emptyList()
        engine.setPageSegMode(mode)
        engine.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "")
        val dpi = if (canvas.singleLine) OcrBands.resolutionDpi(canvas.contentHeight) else 120
        engine.setVariable("user_defined_dpi", dpi.toString())
        engine.setVariable("tessedit_do_invert", "0")
        engine.setImage(image)
        engine.getUTF8Text()
        val iterator = engine.getResultIterator()
        if (iterator == null) {
            engine.clear()
            return emptyList()
        }
        try {
            iterator.begin()
            val symbols = mutableListOf<OcrSymbol>()
            val level = TessBaseAPI.PageIteratorLevel.RIL_SYMBOL
            do {
                val text = OcrGlyphs.accept(iterator.getUTF8Text(level).orEmpty())
                if (text.isEmpty()) continue
                val box = iterator.getBoundingBox(level) ?: continue
                if (box.size < 4) continue
                val left = (box[0] - canvas.pad) / canvas.scale + canvas.originX
                val top = (box[1] - canvas.pad) / canvas.scale + canvas.originY
                val right = (box[2] - canvas.pad) / canvas.scale + canvas.originX
                val bottom = (box[3] - canvas.pad) / canvas.scale + canvas.originY
                if (right <= left || bottom <= top) continue
                symbols += OcrGlyphs.split(
                    OcrSymbol(
                        text = text,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        confidence = iterator.confidence(level) / 100f,
                    )
                )
            } while (iterator.next(level))
            return symbols
        } finally {
            iterator.delete()
            engine.clear()
        }
    }

    private fun toLines(symbols: List<OcrSymbol>): List<OcrLine> {
        if (symbols.isEmpty()) return emptyList()
        val left = symbols.minOf { it.left }
        val top = symbols.minOf { it.top }
        val right = symbols.maxOf { it.right }
        val bottom = symbols.maxOf { it.bottom }
        val text = symbols.sortedWith(compareBy({ it.centerY }, { it.centerX })).joinToString("") { it.plain() }
        return listOf(OcrLine(text, left, top, right, bottom, symbols))
    }

    private fun ensureReady(): TessBaseAPI {
        api?.let { return it }
        val created = TessBaseAPI()
        val ready = created.init(
            prepareData(),
            "eng",
            TessBaseAPI.OEM_LSTM_ONLY,
            mapOf(
                "load_system_dawg" to "0",
                "load_freq_dawg" to "0",
                "load_punc_dawg" to "0",
                "load_number_dawg" to "0",
                "load_unambig_dawg" to "0",
                "load_bigram_dawg" to "0",
                "load_fixed_length_dawgs" to "0",
            ),
        )
        if (!ready) {
            created.recycle()
            throw IllegalStateException("Could not start text recognition")
        }
        created.setVariable("tessedit_enable_dict_correction", "0")
        created.setVariable("textord_noise_rejwords", "0")
        created.setVariable("textord_noise_rejrows", "0")
        created.setVariable("textord_min_xheight", "4")
        api = created
        return created
    }

    private fun prepareData(): String {
        val root = File(appContext.filesDir, "tesseract")
        val dir = File(root, "tessdata")
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, "eng.traineddata")
        if (!dest.exists() || dest.length() < 1_000_000L) {
            appContext.assets.open("tessdata/eng.traineddata").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return root.absolutePath
    }
}
