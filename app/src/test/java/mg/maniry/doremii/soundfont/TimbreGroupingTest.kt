package mg.maniry.doremii.soundfont

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimbreGroupingTest {
    @Test
    fun smallFontStaysFlat() {
        val rows = TimbreGrouping.rows(
            listOf(
                DiscoveredPreset(0, 0, "Piano"),
                DiscoveredPreset(0, 1, "Bright"),
            )
        )

        assertTrue(rows.all { it is TimbreRow.Preset })
        assertEquals(2, rows.size)
    }

    @Test
    fun generalMidiFontIsGrouped() {
        val presets = (0 until 40).map { DiscoveredPreset(0, it, "Prog $it") } +
            DiscoveredPreset(128, 0, "Standard Kit") +
            DiscoveredPreset(2, 0, "Extra")
        val rows = TimbreGrouping.rows(presets)

        assertTrue(rows.any { it is TimbreRow.Header && it.title == "Piano" })
        assertTrue(rows.any { it is TimbreRow.Header && it.title == "Bass" })
        assertTrue(rows.any { it is TimbreRow.Header && it.title == "Drums" })
        assertTrue(rows.any { it is TimbreRow.Header && it.title == "Bank 2" })
    }

    @Test
    fun searchDropsEmptyGroups() {
        val presets = (0 until 40).map { DiscoveredPreset(0, it, "Prog $it") }
        val rows = TimbreGrouping.filter(TimbreGrouping.rows(presets), "Prog 0")

        assertEquals(listOf("Piano", "Prog 0"), rows.map {
            when (it) {
                is TimbreRow.Header -> it.title
                is TimbreRow.Preset -> it.preset.name
            }
        })
    }
}
