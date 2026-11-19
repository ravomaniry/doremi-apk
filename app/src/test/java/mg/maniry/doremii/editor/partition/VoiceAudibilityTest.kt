package mg.maniry.doremii.editor.partition

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAudibilityTest {
    @Test
    fun everythingIsHeardUntilAVoiceIsMutedOrSoloed() {
        assertEquals(listOf(true, true, true, true), audibleVoices(4, emptyList(), emptyList()))
    }

    @Test
    fun muteSilencesThatVoiceOnly() {
        assertEquals(
            listOf(true, false, true, true),
            audibleVoices(4, listOf(false, true), emptyList()),
        )
    }

    @Test
    fun soloLeavesOutEveryOtherVoice() {
        assertEquals(
            listOf(false, true, false, false),
            audibleVoices(4, emptyList(), listOf(false, true)),
        )
    }

    @Test
    fun severalSolosStayAudibleTogether() {
        assertEquals(
            listOf(true, false, true, false),
            audibleVoices(4, emptyList(), listOf(true, false, true)),
        )
    }

    @Test
    fun muteStillSilencesASoloedVoice() {
        assertEquals(
            listOf(false, false, false, false),
            audibleVoices(4, listOf(true), listOf(true)),
        )
    }

    @Test
    fun aNewVoiceStaysUnmutedAndFollowsTheSolo() {
        assertEquals(
            listOf(false, true, false),
            audibleVoices(3, listOf(false), listOf(false, true)),
        )
    }
}
