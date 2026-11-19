package mg.maniry.doremii.editor.partition

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test


class HtmlExportTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun renderKeepsScoreLayout() {
        val html = HtmlExport(sampleScore()).render()

        assertTrue(html.contains("<title>Hira &amp; Feo</title>"))
        assertTrue(html.contains("text-align:center;font-size:24px;font-weight:bold;"))
        assertTrue(html.contains("Hira &amp; Feo"))
        assertTrue(html.contains(">4/4<"))
        assertTrue(html.contains("Do dia C"))
        assertTrue(html.contains("Tempo: 96 bpm"))
        assertTrue(html.contains(">Swing<"))
        assertTrue(html.contains("(vakiana hoe &quot;d.-.d&quot; ny &quot;d.d&quot; sy ny &quot;d.,d&quot;)"))
        assertTrue(html.contains("Rakoto"))
        assertTrue(html.contains("> mf<"))
        assertTrue(html.contains("<b>Do=G</b>"))
        assertTrue(html.contains("T=80"))
        assertTrue(html.contains("<sup style=\"line-height:0;font-size:8px;font-style:italic;\">1</sup>"))
        assertTrue(html.contains("<sub style=\"line-height:0;font-size:8px;font-style:italic;\">1</sub>"))
        assertTrue(html.contains("|</td>"))
        assertTrue(html.contains(":</td>"))
        assertTrue(html.contains("Line one"))
        assertTrue(html.contains("Line two"))
        assertFalse(html.contains("<two>"))
        assertFalse(html.contains("&lt;two&gt;"))
        assertTrue(html.contains("Tonony: Tonon"))
        assertTrue(html.contains("Feony: Feo"))
        assertTrue(html.contains(">2020<"))
    }
}
