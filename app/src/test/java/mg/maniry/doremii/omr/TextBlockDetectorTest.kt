package mg.maniry.doremii.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBlockDetectorTest {
    @Test
    fun leftPageBarGroupsTheLinesItCovers() {
        val ink = BlockInk(400, 220)
        writeLine(ink, 16, 40)
        writeLine(ink, 16, 78)
        writeLine(ink, 16, 150)
        vertical(ink, 10, 34, 96, 3)

        val lines = TextLineDetector.detect(ink)
        val blocks = TextBlockDetector.detect(ink, lines)

        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertTrue(block.top <= 40f)
        assertTrue(block.bottom >= 90f)
        assertTrue(block.bottom < 150f)
        assertTrue(block.left <= 12f)
    }

    @Test
    fun twoMiddleBarsMakeABlockAndOneDoesNot() {
        val paired = BlockInk(400, 160)
        writeLine(paired, 20, 30)
        writeLine(paired, 20, 68)
        vertical(paired, 110, 24, 86, 2)
        vertical(paired, 240, 24, 86, 2)
        val pairedBlocks = TextBlockDetector.detect(paired, TextLineDetector.detect(paired))
        assertEquals(1, pairedBlocks.size)
        assertTrue(pairedBlocks.single().top <= 30f)
        assertTrue(pairedBlocks.single().bottom >= 80f)

        val single = BlockInk(400, 160)
        writeLine(single, 20, 30)
        writeLine(single, 20, 68)
        vertical(single, 180, 24, 86, 2)
        assertEquals(0, TextBlockDetector.detect(single, TextLineDetector.detect(single)).size)
    }

    @Test
    fun rightPageBarGroupsTheLinesItCovers() {
        val ink = BlockInk(400, 180)
        writeLine(ink, 24, 28)
        writeLine(ink, 24, 66)
        writeLine(ink, 24, 120)
        vertical(ink, 372, 22, 84, 3)

        val blocks = TextBlockDetector.detect(ink, TextLineDetector.detect(ink))

        assertEquals(1, blocks.size)
        assertTrue(blocks.single().top <= 28f)
        assertTrue(blocks.single().bottom >= 78f)
        assertTrue(blocks.single().bottom < 120f)
        assertTrue(blocks.single().right >= 372f)
    }

    @Test
    fun separateSystemsStaySeparateBlocks() {
        val ink = BlockInk(420, 300)
        writeLine(ink, 30, 8)
        writeLine(ink, 24, 48)
        writeLine(ink, 24, 84)
        writeLine(ink, 30, 130)
        writeLine(ink, 24, 180)
        writeLine(ink, 24, 216)
        vertical(ink, 100, 42, 102, 2)
        vertical(ink, 250, 42, 102, 2)
        vertical(ink, 80, 174, 234, 2)
        vertical(ink, 280, 174, 234, 2)

        val blocks = TextBlockDetector.detect(ink, TextLineDetector.detect(ink))

        assertEquals(2, blocks.size)
        assertTrue(blocks[0].top > 20f)
        assertTrue(blocks[0].bottom < 130f)
        assertTrue(blocks[1].top > 140f)
        assertTrue(blocks[0].bottom < blocks[1].top)
    }

    private fun writeLine(ink: BlockInk, left: Int, top: Int) {
        for (x in listOf(left, left + 36, left + 80, left + 140, left + 210, left + 280)) {
            glyph(ink, x, top, 8, 12)
        }
    }

    private fun glyph(ink: BlockInk, left: Int, top: Int, width: Int, height: Int) {
        for (y in top until top + height) {
            for (x in left until left + width) ink.on(x, y)
        }
    }

    private fun vertical(ink: BlockInk, x: Int, top: Int, bottom: Int, width: Int) {
        for (y in top until bottom) {
            for (dx in 0 until width) ink.on(x + dx, y)
        }
    }
}

private class BlockInk(override val width: Int, override val height: Int) : InkImage {
    private val dark = Array(height) { BooleanArray(width) }

    fun on(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        dark[y][x] = true
    }

    override fun isDark(x: Int, y: Int): Boolean {
        return x in 0 until width && y in 0 until height && dark[y][x]
    }
}
