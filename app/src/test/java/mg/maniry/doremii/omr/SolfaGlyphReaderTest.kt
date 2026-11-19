package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class SolfaGlyphReaderTest {
    @Test
    fun readsEachSolfaLetter() {
        for (letter in "drmfslt") {
            val ink = CellInk(260, 180)
            stamp(ink, glyph(letter), 40, 36)
            assertCell(ink, whole(ink), letter.toString(), letter.toString(), "letter $letter")
        }
    }

    @Test
    fun readsTwoLetterNotes() {
        val di = pair("di")
        val ta = pair("ta")
        assertCell(di, whole(di), "di", "di", "di")
        assertCell(ta, whole(ta), "ta", "ta", "ta")
    }

    @Test
    fun apostropheRaisesTheOctave() {
        val once = CellInk(260, 180)
        val letter = stamp(once, glyph('d'), 48, 48)
        block(once, letter.right - letter.width / 3, letter.top - 12, 5, 7)
        assertCell(once, whole(once), "d'", "d1", "one apostrophe")

        val twice = CellInk(260, 180)
        val again = stamp(twice, glyph('d'), 48, 48)
        block(twice, again.right - again.width / 3, again.top - 12, 5, 7)
        block(twice, again.right - again.width / 3 + 9, again.top - 14, 4, 6)
        assertCell(twice, whole(twice), "d''", "d2", "two apostrophes")
    }

    @Test
    fun trailingCommaLowersTheOctave() {
        val single = CellInk(260, 180)
        val letter = stamp(single, glyph('d'), 40, 40)
        block(single, letter.right + 3, letter.bottom + 2, 4, 7)
        assertCell(single, whole(single), "d,", "d-1", "octave comma")

        val joined = pair("di")
        val box = union(joined)
        block(joined, box.right + 3, box.bottom + 2, 4, 7)
        assertCell(joined, whole(joined), "di,", "di-1", "two-letter octave comma")
    }

    @Test
    fun dotAndCommaDivideNotes() {
        val dotted = CellInk(320, 180)
        val left = stamp(dotted, glyph('d'), 30, 40)
        val right = stamp(dotted, glyph('r'), left.right + 24, 40 + glyph('d').height - glyph('r').height)
        block(dotted, left.right + 9, (left.top + left.bottom) / 2 - 2, 4, 4)
        assertCell(dotted, whole(dotted), "d.r", "d.r", "dot")
        assertTrue(right.left > left.right)

        val joined = CellInk(320, 180)
        val first = stamp(joined, glyph('d'), 30, 40)
        stamp(joined, glyph('r'), first.right + 24, 40 + glyph('d').height - glyph('r').height)
        block(joined, first.right + 9, first.bottom + 2, 4, 6)
        assertCell(joined, whole(joined), "d,r", "d-1.r", "join comma")

        val bare = CellInk(320, 180)
        val head = stamp(bare, glyph('d'), 30, 40)
        stamp(bare, glyph('r'), head.right + 24, 40 + glyph('d').height - glyph('r').height)
        assertCell(bare, whole(bare), "dr", "d.r", "no mark")

        val dottedComma = CellInk(340, 180)
        val lead = stamp(dottedComma, glyph('d'), 24, 36)
        stamp(dottedComma, glyph('r'), lead.right + 36, 36 + glyph('d').height - glyph('r').height)
        block(dottedComma, lead.right + 8, (lead.top + lead.bottom) / 2 - 2, 4, 4)
        block(dottedComma, lead.right + 16, lead.bottom + 2, 4, 6)
        assertCell(dottedComma, whole(dottedComma), "d.,r", "d.,r", "dot comma")
    }

    @Test
    fun blankAndEdgeBarAreEmpty() {
        val blank = CellInk(80, 100)
        assertCell(blank, whole(blank), "", "", "blank")

        val barred = CellInk(80, 100)
        for (y in 0 until barred.height) {
            barred.on(0, y)
            barred.on(1, y)
        }
        assertCell(barred, whole(barred), "", "", "edge bar")
    }

    @Test
    fun aDamagedLetterStillMatches() {
        val ink = CellInk(260, 180)
        val letter = glyph('d')
        val box = stamp(ink, letter, 40, 36)
        var cleared = false
        for (y in 1 until letter.height - 2) {
            for (x in 1 until letter.width - 2) {
                if (!filled(letter, x, y)) continue
                ink.off(box.left + x, box.top + y)
                ink.off(box.left + x + 1, box.top + y)
                ink.off(box.left + x, box.top + y + 1)
                ink.off(box.left + x + 1, box.top + y + 1)
                cleared = true
                break
            }
            if (cleared) break
        }
        assertTrue(cleared)
        assertCell(ink, whole(ink), "d", "d", "damaged d")
    }

    @Test
    fun cellsAreReadSeparately() {
        val ink = CellInk(420, 180)
        stamp(ink, glyph('d'), 46, 48)
        val other = stamp(ink, glyph('d'), 250, 48)
        block(ink, other.right - other.width / 3, other.top - 12, 5, 7)
        val first = NoteCell(0f, 0f, 200f, 180f)
        val second = NoteCell(200f, 0f, 420f, 180f)
        assertCell(ink, first, "d", "d", "left cell")
        assertCell(ink, second, "d'", "d1", "right cell")
    }

    private fun pair(text: String): CellInk {
        val ink = CellInk(280, 180)
        val first = glyph(text[0])
        val second = glyph(text[1])
        val bottom = 130
        val left = stamp(ink, first, 36, bottom - first.height)
        stamp(ink, second, left.right + 5, bottom - second.height)
        return ink
    }

    private fun union(ink: CellInk): Box {
        var left = ink.width
        var top = ink.height
        var right = 0
        var bottom = 0
        for (y in 0 until ink.height) {
            for (x in 0 until ink.width) {
                if (!ink.isDark(x, y)) continue
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
        return Box(left, top, right, bottom)
    }

    private fun assertCell(ink: InkImage, cell: NoteCell, raw: String, mapped: String, label: String) {
        val actual = reader.read(ink, cell)
        assertEquals(label, raw, actual)
        assertEquals(label, mapped, SolfaCellMapper.fromOcrText(actual))
    }

    private fun whole(ink: InkImage): NoteCell {
        return NoteCell(0f, 0f, ink.width.toFloat(), ink.height.toFloat())
    }

    private fun stamp(ink: CellInk, glyph: GlyphRaster, left: Int, top: Int): Box {
        for (y in 0 until glyph.height) {
            for (x in 0 until glyph.width) {
                if (glyph.dark[y * glyph.width + x]) ink.on(left + x, top + y)
            }
        }
        return Box(left, top, left + glyph.width - 1, top + glyph.height - 1)
    }

    private fun block(ink: CellInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }

    private fun filled(glyph: GlyphRaster, x: Int, y: Int): Boolean {
        if (x !in 0 until glyph.width || y !in 0 until glyph.height) return false
        if (!glyph.dark[y * glyph.width + x]) return false
        return glyph.dark[y * glyph.width + x + 1] &&
            glyph.dark[(y + 1) * glyph.width + x] &&
            glyph.dark[(y + 1) * glyph.width + x + 1]
    }

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left + 1
    }

    private class CellInk(override val width: Int, override val height: Int) : InkImage {
        private val dark = Array(height) { BooleanArray(width) }

        fun on(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) dark[y][x] = true
        }

        fun off(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) dark[y][x] = false
        }

        override fun isDark(x: Int, y: Int): Boolean {
            return x in 0 until width && y in 0 until height && dark[y][x]
        }
    }

    companion object {
        private val fonts = listOf(
            "serif-plain" to Font("Serif", Font.PLAIN, 84),
            "sans-plain" to Font("SansSerif", Font.PLAIN, 84),
            "serif-italic" to Font("Serif", Font.ITALIC, 84),
            "sans-italic" to Font("SansSerif", Font.ITALIC, 84),
        )
        private val letters = charArrayOf('d', 'r', 'm', 'f', 's', 'l', 't', 'i', 'a')
        private val drawn: Map<String, GlyphRaster> by lazy { renderAll() }
        private val patterns: List<GlyphPattern> by lazy {
            val built = ArrayList<GlyphPattern>()
            for ((name, _) in fonts) {
                for (letter in letters) {
                    built += SolfaPatterns.fromRaster(letter, drawn.getValue("$name-$letter")) ?: continue
                }
            }
            built
        }
        private val reader: SolfaGlyphReader by lazy { SolfaGlyphReader(patterns) }

        private fun glyph(letter: Char): GlyphRaster = drawn.getValue("serif-plain-$letter")

        private fun renderAll(): Map<String, GlyphRaster> {
            val found = HashMap<String, GlyphRaster>()
            for ((name, font) in fonts) {
                for (letter in letters) {
                    val tight = SolfaPatterns.cropped(render(letter, font))
                    val ink = tight?.dark?.count { it } ?: 0
                    check(tight != null && tight.height > 16 && ink > 12) {
                        "$name $letter was not drawn"
                    }
                    found["$name-$letter"] = tight
                }
            }
            return found
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
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
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
}
