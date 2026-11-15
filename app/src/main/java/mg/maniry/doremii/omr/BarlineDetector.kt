package mg.maniry.doremii.omr

import kotlin.math.max

object BarlineDetector {
    fun detect(ink: InkImage, top: Int, bottom: Int): List<Float> {
        val y0 = top.coerceIn(0, ink.height)
        val y1 = bottom.coerceIn(0, ink.height)
        val span = y1 - y0
        if (span < 8 || ink.width < 2) return emptyList()
        val minRun = (span * 0.62f).toInt().coerceAtLeast(6)
        val dark = BooleanArray(ink.width)
        for (x in 0 until ink.width) {
            var run = 0
            var best = 0
            for (y in y0 until y1) {
                if (ink.isDark(x, y)) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            dark[x] = best >= minRun
        }
        val maxThick = max(8, span / 25)
        val centers = mutableListOf<Float>()
        var x = 0
        while (x < dark.size) {
            if (!dark[x]) {
                x++
                continue
            }
            val start = x
            while (x < dark.size && dark[x]) x++
            val thick = x - start
            if (thick in 1..maxThick) {
                centers.add((start + x - 1) / 2f)
            }
        }
        return centers
    }
}
