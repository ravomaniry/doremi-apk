package mg.maniry.doremii.omr

import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.min

class TrainingNotesTest {
    @Test
    fun matchesCopiedNoteText() {
        val dir = trainingDir()
        val reader = SolfaGlyphReader(desktopPatterns())
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for (page in listOf(2, 3, 5, 9, 10)) {
            val expected = parseSystems(File(dir, "$page.txt").readText())
            val ink = loadInk(File(dir, "$page.png"))
            try {
                val glyphs = ink.copy()
                val stripped = HorizontalRules.strip(ink)
                val lines = TextLineDetector.detect(stripped)
                val blocks = TextBlockDetector.detect(stripped, lines)
                val marks = LineMarks.find(stripped, lines, blocks)
                val classified = MusicalLineClassifier.classify(lines, blocks, marks)
                val divided = BeatDividers.find(stripped, classified.lines, classified.blocks, marks)
                val grid = NoteCells.grid(divided.lines, classified.blocks, divided.beats)
                failures += score(page, glyphs, grid, expected, reader, report)
            } finally {
                ink.release()
            }
        }
        val text = report.toString().trimEnd()
        assertTrue(text + "\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun score(
        page: Int,
        ink: InkImage,
        grid: List<List<List<NoteCell>>>,
        expected: List<List<List<String>>>,
        reader: SolfaGlyphReader,
        report: StringBuilder,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (grid.size < expected.size) {
            failures += "page $page systems ${grid.size} < ${expected.size}"
        }
        var cells = 0
        var misses = 0
        expected.forEachIndexed { systemIndex, voices ->
            val predicted = grid.getOrNull(systemIndex)
            if (predicted == null) {
                failures += "page $page system ${systemIndex + 1} missing"
                return@forEachIndexed
            }
            if (predicted.size != voices.size) {
                failures += "page $page system ${systemIndex + 1} voices ${predicted.size} != ${voices.size}"
            }
            val voiceCount = min(predicted.size, voices.size)
            for (voiceIndex in 0 until voiceCount) {
                val got = predicted[voiceIndex].map { reader.read(ink, it) }
                val want = voices[voiceIndex]
                if (got.size != want.size) {
                    failures += "page $page system ${systemIndex + 1} voice ${voiceIndex + 1} cells ${got.size} != ${want.size}"
                }
                val cellCount = maxOf(got.size, want.size)
                for (cellIndex in 0 until cellCount) {
                    cells++
                    val actual = got.getOrElse(cellIndex) { "" }
                    val target = want.getOrElse(cellIndex) { "" }
                    if (actual == target) continue
                    misses++
                    val cell = predicted[voiceIndex].getOrNull(cellIndex)
                    val where = "p${page}-s${systemIndex + 1}-v${voiceIndex + 1}-c${cellIndex + 1}"
                    if (cell != null) saveCell(ink, cell, where)
                    failures += "page $page system ${systemIndex + 1} voice ${voiceIndex + 1} cell ${cellIndex + 1} got '$actual' want '$target'"
                }
            }
        }
        report.append("page $page systems ${expected.size}/${grid.size} cells $cells mismatches $misses\n")
        return failures
    }

    private fun parseSystems(text: String): List<List<List<String>>> {
        val systems = mutableListOf<List<List<String>>>()
        val voices = mutableListOf<List<String>>()
        for (line in text.replace("\r\n", "\n").lines()) {
            if (line.isBlank()) {
                if (voices.isNotEmpty()) {
                    systems += voices.toList()
                    voices.clear()
                }
            } else {
                voices += splitCells(line.trim())
            }
        }
        if (voices.isNotEmpty()) systems += voices.toList()
        return systems
    }

    private fun splitCells(line: String): List<String> {
        val parts = line.split(':')
        return if (line.endsWith(':') && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    private fun loadInk(file: File): MatInk {
        OpenCvImages.ensure()
        val color = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        check(!color.empty()) { "unreadable ${file.absolutePath}" }
        val gray = Mat()
        return try {
            Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
            Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
            val ink = OpenCvImages.adaptiveInk(gray)
            MatInk(gray, ink)
        } catch (error: Throwable) {
            gray.release()
            throw error
        } finally {
            color.release()
        }
    }

    private fun saveCell(ink: InkImage, cell: NoteCell, name: String) {
        val pad = 8
        val left = (cell.left.toInt() - pad).coerceAtLeast(0)
        val top = (cell.top.toInt() - pad).coerceAtLeast(0)
        val right = (cell.right.toInt() + pad).coerceAtMost(ink.width)
        val bottom = (cell.bottom.toInt() + pad).coerceAtMost(ink.height)
        val width = right - left
        val height = bottom - top
        if (width < 2 || height < 2) return
        val mat = Mat(height, width, CvType.CV_8UC1)
        val bytes = ByteArray(width * height)
        var index = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val inside = x.toFloat() >= cell.left && x.toFloat() < cell.right &&
                    y.toFloat() >= cell.top && y.toFloat() < cell.bottom
                bytes[index++] = when {
                    ink.isDark(x, y) -> 0
                    !inside -> 210
                    else -> 255
                }.toByte()
            }
        }
        mat.put(0, 0, bytes)
        val file = File("/tmp/doremi-crops", "$name.png")
        file.parentFile.mkdirs()
        Imgcodecs.imwrite(file.absolutePath, mat)
        mat.release()
    }

    private fun trainingDir(): File {
        val candidates = listOf(File("training-data"), File("../training-data"))
        return candidates.firstOrNull { File(it, "2.txt").isFile }
            ?: error("training-data not found from ${File(".").absolutePath}")
    }

    private fun desktopPatterns(): List<GlyphPattern> {
        val fonts = listOf(
            Font("Serif", Font.PLAIN, 84),
            Font("SansSerif", Font.PLAIN, 84),
            Font("Serif", Font.ITALIC, 84),
            Font("SansSerif", Font.ITALIC, 84),
            Font("Serif", Font.BOLD, 84),
            Font("SansSerif", Font.BOLD, 84),
        )
        val letters = charArrayOf('d', 'r', 'm', 'f', 's', 'l', 't', 'i', 'a')
        val built = ArrayList<GlyphPattern>()
        for (font in fonts) {
            for (letter in letters) {
                val raster = SolfaPatterns.cropped(render(letter, font)) ?: continue
                built += SolfaPatterns.fromRaster(letter, raster) ?: continue
            }
        }
        return built
    }

    private fun render(letter: Char, font: Font): GlyphRaster {
        val width = 280
        val height = 280
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            graphics.font = font
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            graphics.drawString(letter.toString(), 30, 190)
        } finally {
            graphics.dispose()
        }
        val dark = BooleanArray(width * height)
        val raster = image.raster
        for (y in 0 until height) {
            for (x in 0 until width) dark[y * width + x] = raster.getSample(x, y, 0) < 128
        }
        return GlyphRaster(width, height, dark)
    }
}
