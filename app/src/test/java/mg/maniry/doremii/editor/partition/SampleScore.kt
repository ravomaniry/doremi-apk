package mg.maniry.doremii.editor.partition

fun sampleScore(): PartitionData {
    return PartitionData().apply {
        songInfo.title.value = "Hira & Feo"
        songInfo.singer.value = "Rakoto"
        songInfo.author.value = "Tonon"
        songInfo.compositor.value = "Feo"
        songInfo.releaseDate.value = "2020"
        signature.value = 4
        tempo = 96
        key.value = 0
        swing.value = true
        notes[0] = mutableListOf("d1", "r", "m", "f-1")
        notes[1] = mutableListOf("s", "", "", "")
        changeEvents.add(ChangeEvent(0, ChangeEvent.VELOCITY, "mf"))
        changeEvents.add(ChangeEvent(0, ChangeEvent.MOD, "G"))
        changeEvents.add(ChangeEvent(0, ChangeEvent.TEMPO, "80"))
        lyrics.value = "Line one\n\nLine <two>"
    }
}
