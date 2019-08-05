package mg.maniry.doremi.editor.partition


import java.util.regex.Pattern
import kotlin.math.max


class NotesParser {
    private val scale = arrayOf("d", "di", "r", "ri", "m", "f", "fi", "s", "si", "l", "ta", "t")
    private val modulationKeys = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val velocityLetters = listOf("pp", "p", "mp", "mf", "f", "ff")
    private val velocityNumbers = listOf(78, 84, 90, 100, 110, 120)
    private val noteP = Pattern.compile("^[a-z]+")
    private val octaveP = Pattern.compile("[0-9-]+$")
    private val release = 5
    private var baseVelocity = 110
    private val timeUnit = 480L
    private val commonDelay = 120L
    private var tmpChangeEvents = mutableListOf<ParserStructureEvent>()
    private lateinit var notesToParse: Array<MutableList<String>>
    private var ticks = mutableListOf<Long>()
    private var currentTempo = 0

    private var tmpStart = 0
    var voiceIds = mutableListOf<String>()
    var playedVoices = mutableListOf<Boolean>()
    var changeEvents = mutableListOf<ChangeEvent>()
    var start = 0
    var key = 0
    var tempo = 120
    var swing = false
    var signature = 4
    var enableVelocity = true
    var loopsNumber = 0


    fun parse(notes: MutableList<MutableList<String>>): MutableList<Note> {
        ticks = notes.map { commonDelay }.toMutableList()

        handleRepetitions(notes)
        handleStartAndLoops()

        val parsedNotes = mutableListOf<Note>()
        val voicesNum = voiceIds.size

        val perVoiceNotes = notesToParse.mapIndexed { voice, voiceNotes ->
            ticks[voice] = commonDelay
            val parsed = mutableListOf<Note>()

            if (voice < voicesNum && playedVoices.size > voice && playedVoices[voice]) {
                cleanNotes(voiceNotes).forEachIndexed { index, timeNotes ->
                    parsed.addAll(parseSingleTimeNotes(timeNotes, voice, index))
                }

                var lastNoteIndex = -1
                parsed.forEachIndexed { index, note ->
                    if (note.pitch == 0 && lastNoteIndex >= 0) {
                        parsed[lastNoteIndex].addDuration(note.duration)
                    } else {
                        lastNoteIndex = index
                    }
                }
            }

            return@mapIndexed parsed.filter { it.pitch > 0 }
        }

        perVoiceNotes.forEach {
            parsedNotes.addAll(it)
        }

        return parsedNotes
    }


    private fun handleRepetitions(notes: MutableList<MutableList<String>>) {
        notesToParse = voiceIds.map { mutableListOf<String>() }.toTypedArray()
        tmpChangeEvents = mutableListOf()
        changeEvents = changeEvents
                .asSequence()
                .filter { it.isValid() }
                .toMutableList()
                .also { changes -> changes.forEach { it.treated = false } }
        val lastDalIndex = changeEvents
                .asSequence()
                .filter { it.type == ChangeEvent.DAL }
                .map { it.position }
                .toList()
                .let { if (it.isNotEmpty()) it.reduce(::max) else 0 }

        var nextIndex = 0
        var realIndex = 0
        var initTempoKey = true
        var reachedEnd = false
        tmpStart = if (start == 0) 0 else -1
        var maxIndex = notes.map { it.size }.reduce(::max)

        if (changeEvents.isNotEmpty()) {
            maxIndex = max(maxIndex, changeEvents.asSequence().map { it.position }.reduce(::max) + 1)
        }

        if (maxIndex % signature > 0) {
            maxIndex += signature - (maxIndex % signature)
        }


        while (nextIndex < maxIndex) {
            if (nextIndex == start && tmpStart == -1)
                tmpStart = realIndex

            // TEMPO && KEY: INIT + CHANGES
            if (nextIndex == 0) {
                val tmpEvent = ParserStructureEvent(realIndex, tempo, key, 110)
                tmpChangeEvents.add(tmpEvent)

                changeEvents
                        .find { it.position == 0 && it.type == ChangeEvent.VELOCITY }
                        ?.run { tmpEvent.velocity = velocityNumbers[velocityLetters.indexOf(value)] }

                initTempoKey = false

            } else {
                if (initTempoKey) {
                    initTempoKey = false
                    var i = nextIndex
                    var tmpTempo = -1
                    var tmpKey = -1
                    var tmpVelo = -1

                    while (i > 0 && (tmpTempo == -1 || tmpKey == -1)) {
                        if (tmpTempo == -1)
                            changeEvents
                                    .find { it.position == i && it.type == ChangeEvent.TEMPO }
                                    ?.run { tmpTempo = value.toInt() }

                        if (tmpKey == -1)
                            changeEvents
                                    .find { it.position == i && it.type == ChangeEvent.MOD }
                                    ?.run { tmpKey = modulationKeys.indexOf(value) }

                        if (tmpKey == -1)
                            changeEvents
                                    .find { it.position == i && it.type == ChangeEvent.VELOCITY }
                                    ?.run { tmpVelo = velocityNumbers[velocityLetters.indexOf(value)] }

                        i -= 1
                    }

                    if (tmpTempo == -1) tmpTempo = tempo
                    if (tmpKey == -1) tmpKey = key
                    if (tmpVelo == -1) tmpVelo = 110

                    tmpChangeEvents.add(ParserStructureEvent(realIndex, tmpTempo, tmpKey, tmpVelo))

                } else {
                    val changes = changeEvents.filter {
                        it.position == nextIndex &&
                                arrayOf(ChangeEvent.MOD, ChangeEvent.TEMPO, ChangeEvent.VELOCITY).contains(it.type)
                    }

                    if (changes.isNotEmpty()) {
                        var tmpCh = tmpChangeEvents.find { it.position == realIndex }
                        if (tmpCh == null) {
                            tmpCh = ParserStructureEvent(position = realIndex)
                                    .also { tmpChangeEvents.add(it) }
                        }

                        changes.forEach { ch ->
                            when (ch.type) {
                                ChangeEvent.MOD -> tmpCh.key = modulationKeys.indexOf(ch.value)
                                ChangeEvent.TEMPO -> tmpCh.tempo = ch.value.toInt()
                                ChangeEvent.VELOCITY -> tmpCh.velocity = velocityNumbers[velocityLetters.indexOf(ch.value)]
                            }
                        }
                    }
                }
            }

            // NOTES
            notesToParse.forEachIndexed { voice, voiceNotes ->
                if (notes[voice].size > nextIndex) {
                    voiceNotes.add(notes[voice][nextIndex])
                } else {
                    voiceNotes.add("")
                }
            }

            realIndex++

            // Fine
            val fine = changeEvents.find {
                it.type == ChangeEvent.SIGN && it.position == nextIndex && it.value == "Fin"
            }

            if (reachedEnd && fine != null) {
                return
            } else if (nextIndex == lastDalIndex && nextIndex > 0) {
                reachedEnd = true
            }

            // D.C, D.S
            val dal = changeEvents.find { it.type == ChangeEvent.DAL && it.position == nextIndex && !it.treated }

            if (dal == null) {
                nextIndex++
            } else {
                dal.treated = true
                initTempoKey = true

                if (dal.value == "DC") {
                    nextIndex = 0
                } else {
                    val target = changeEvents.find {
                        it.type == ChangeEvent.SIGN &&
                                it.position < nextIndex &&
                                dal.value.endsWith(it.value)
                    }

                    if (target != null) {
                        nextIndex = target.position
                    } else {
                        nextIndex++
                    }
                }
            }
        }
    }


    private fun handleStartAndLoops() {
        val tmpNotesToParse: Array<MutableList<String>>
        val newChangeEvents: MutableList<ParserStructureEvent>

        if (tmpStart > 0) {
            tmpNotesToParse = voiceIds.map { mutableListOf<String>() }.toTypedArray()
            val chEvZero = ParserStructureEvent(0)
            var index = tmpStart

            while (index >= 0 && (chEvZero.key == -1 || chEvZero.tempo == -1 || chEvZero.velocity == -1)) {
                tmpChangeEvents.find { it.position == index }?.run {
                    if (chEvZero.tempo == -1 && tempo != -1)
                        chEvZero.tempo = tempo
                    if (chEvZero.key == -1 && key != -1)
                        chEvZero.key = key
                    if (chEvZero.velocity == -1 && velocity != -1)
                        chEvZero.velocity = velocity
                }

                index--
            }

            newChangeEvents = mutableListOf(chEvZero).apply {
                addAll(tmpChangeEvents.asSequence()
                        .filter { it.position > tmpStart }
                        .map { it.copy(position = it.position - tmpStart) }
                        .toList())
            }

            notesToParse.forEachIndexed { v, notesList ->
                tmpNotesToParse[v] = notesList.slice(tmpStart until notesList.size).toMutableList()
            }

        } else {
            tmpNotesToParse = notesToParse.map { it.toMutableList() }.toTypedArray()
            newChangeEvents = tmpChangeEvents.toMutableList()
        }


        var loopIndex = 0
        var positionOffset = tmpNotesToParse[0].size
        while (loopsNumber > loopIndex) {
            newChangeEvents.addAll(tmpChangeEvents.map { it.copy(position = it.position + positionOffset) })
            notesToParse.forEachIndexed { v, voiceNotes ->
                tmpNotesToParse[v].addAll(voiceNotes)
            }

            loopIndex++
            positionOffset = tmpNotesToParse[0].size
        }

        tmpChangeEvents = newChangeEvents
        notesToParse = tmpNotesToParse
    }


    private fun parseSingleTimeNotes(note: String, voice: Int, index: Int): MutableList<Note> {
        val notes = mutableListOf<Note>()

        tmpChangeEvents
                .find { it.position == index }
                ?.also {
                    if (it.key >= 0) {
                        key = it.key
                    }
                    if (it.tempo >= 0) {
                        currentTempo = it.tempo
                    }
                    if (enableVelocity && it.velocity >= 0) {
                        baseVelocity = it.velocity
                    }
                }

        val currentTU = timeUnit * tempo / currentTempo
        val nextTick = ticks[voice] + currentTU
        val velocity = baseVelocity - when (voice) {
            1 -> 12
            2 -> 9
            3 -> 6
            else -> 0
        }

        if (note == "") {
            ticks[voice] += currentTU
        } else if (!note.contains('.') && !note.contains(',')) {
            val pitch = noteToPitch(note, voiceIds[voice])
            val duration = currentTU - getCorrectRelease(pitch)
            return mutableListOf(Note(voice, pitch, velocity, ticks[voice], duration))
                    .also { ticks[voice] += currentTU }
        } else {
            val subNotes = note.split(".")
            val noteWeight = subNotes.size

            subNotes.forEach { subNote ->
                if (!subNote.contains(',')) {
                    if (subNote != "") {
                        val pitch = noteToPitch(subNote, voiceIds[voice])
                        val duration = currentTU / noteWeight - getCorrectRelease(pitch)
                        notes.add(Note(voice, pitch, velocity, ticks[voice], duration))
                    }
                    ticks[voice] += currentTU / noteWeight
                } else {
                    val finalNotes = subNote.split(",")
                    val finalNoteWeight = finalNotes.size

                    finalNotes.forEach { finalNote ->
                        if (finalNote != "") {
                            val pitch = noteToPitch(finalNote, voiceIds[voice])
                            val duration = currentTU / (noteWeight * finalNoteWeight) - getCorrectRelease(pitch)
                            notes.add(Note(voice, pitch, velocity, ticks[voice], duration))
                        }
                        ticks[voice] += currentTU / (noteWeight * finalNoteWeight)
                    }
                }
            }
        }

        ticks[voice] = nextTick

        return notes
    }


    private fun cleanNotes(voiceNotes: List<String>) = voiceNotes.map {
        val cleaned = it.replace(" ", "")
        when {
            cleaned == "" -> ""
            swing -> when {
                cleaned.contains('.') && !cleaned.contains(',') && cleaned.split(".").size == 2 ->
                    it.replace(".", ".-.")
                cleaned.contains(".,") -> {
                    val parts = cleaned.split(".,")
                    if (parts.size == 2 &&
                            !parts[0].contains('.') && !parts[0].contains(',') &&
                            !parts[1].contains('.') && !parts[1].contains(','))
                        it.replace(".,", ".-.")
                    else
                        it
                }
                else -> it
            }
            else -> it
        }
    }


    private fun noteToPitch(stringNote: String, voice: String): Int {
        var pitch = -1
        val voiceOffset = when (voice) {
            "T" -> -12
            "B" -> -12
            else -> 0
        }

        if (stringNote == "") {
            return -1
        }
        if (stringNote == "-") {
            return 0
        }

        val noteMatch = noteP.matcher(stringNote.trim())
        if (noteMatch.find()) {
            pitch = searchNote(noteMatch.group(0)) + key + voiceOffset

            val octaveMatch = octaveP.matcher(stringNote)
            if (octaveMatch.find()) {
                pitch += 12 * Integer.valueOf(octaveMatch.group(0))
            }
        }

        return pitch
    }


    private fun searchNote(stringNote: String): Int {
        var pitch = -1
        scale.forEachIndexed { i, n ->
            if (n == stringNote) {
                pitch = 60 + i
            }
        }
        return pitch
    }


    private fun getCorrectRelease(pitch: Int): Int {
        return if (pitch == 0) {
            0
        } else {
            release
        }
    }
}