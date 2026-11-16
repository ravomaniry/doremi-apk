package mg.maniry.doremii.omr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenCvSolfaTest {
    @Test
    fun readsAPrintedLineIntoCells() {
        val bitmap = printedLine()
        try {
            val cells = OpenCvSolfa.read(bitmap).result.parse.voices.single().cells
            assertEquals(listOf("d", "r", "m", "f", "-", "s"), cells)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun printedLine(): Bitmap {
        val bitmap = Bitmap.createBitmap(1400, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            typeface = Typeface.SANS_SERIF
            textSize = 84f
            isAntiAlias = false
        }
        val baseline = 150f
        listOf(
            "d" to 70f,
            ":" to 190f,
            "r" to 310f,
            ":" to 430f,
            "m" to 550f,
            "f" to 860f,
            ":" to 980f,
            "-" to 1100f,
            ":" to 1220f,
            "s" to 1320f,
        ).forEach { (glyph, x) ->
            canvas.drawText(glyph, x, baseline, paint)
        }
        canvas.drawRect(690f, 28f, 698f, 214f, Paint().apply { color = Color.BLACK })
        return bitmap
    }
}
