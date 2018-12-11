package mg.maniry.doremi.partition

import java.util.regex.Pattern
import kotlin.math.max


class NotesParser {
    private val scale = arrayOf("d", "di", "r", "ri", "m", "f", "fi", "s", "si", "l", "ta", "t")
    private val modulationKeys = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val velLetters = listOf("pp", "p", "mp", "mf", "f", "ff")
    private val velNumbers = listOf(78, 84, 90, 100, 110, 120)
    private val noteP = Pattern.compile("^[a-z]+")
    private val octaveP = Pattern.compile("[0-9-]+$")
    private val release = 5
    private var baseVelocity = 110
    private val timeUnit = 480L
    private val commonDelay = 120L
    var playedVoices = MutableList(4) { true }
    var changeEvents = mutableListOf<ChangeEvent>()
    private var tmpChangeEvents = mutableListOf<ParserStructureEvent>()
    private lateinit var notesToParse: Array<MutableList<String>>

    var start = 0
    var key = 0
    var tempo = 120
    var swing = false
    var enableVelocity = true
    private val ticks = MutableList(4) { commonDelay }
    private var currentTempo = 0


    fun parse(notes: Array<MutableList<String>>): MutableList<Note> {
        handleRepetitions(notes)

        val parsedNotes = mutableListOf<Note>()

        notesToParse
                .mapIndexed { voice, voiceNotes ->
                    ticks[voice] = commonDelay
                    val parsed = mutableListOf<Note>()

                    if (playedVoices[voice]) {
                        cleanNotes(voiceNotes).forEachIndexed { index, timeNotes ->
                            parsed.addAll(parseSingleTimeNotes(timeNotes, voice, index))
                        }

                        var lastNoteIndex = -1
                        parsed.forEachIndexed { index, note ->
                            if (note.pitch == 0 && lastNoteIndex >= 0)
                                parsed[lastNoteIndex].addDuration(note.duration)
                            else
                                lastNoteIndex = index
                        }
                    }

                    return@mapIndexed parsed
                }
                .forEach {
                    parsedNotes.addAll(it)
                }

        return parsedNotes
    }


    private fun handleRepetitions(notes: Array<MutableList<String>>) {
        notesToParse = Array(4) { mutableListOf<String>() }
        tmpChangeEvents = mutableListOf()
        changeEvents = changeEvents.asSequence()
                .filter { it.isValid() }
                .toMutableList()
                .also { changes -> changes.forEach { it.treated = false } }

        var nextIndex = start
        var realIndex = 0
        var initTempoKey = true
        var reachedEnd = false
        var maxIndex = notes.map { it.size }.reduce(::max)

        if (!changeEvents.isEmpty())
            maxIndex = max(maxIndex, changeEvents.asSequence().map { it.position }.reduce(::max) + 1)


        while (nextIndex < maxIndex) {
            // TEMPO && KEY: INIT + CHANGES
            if (nextIndex == 0) {
                val tmpEvent = ParserStructureEvent(realIndex, tempo, key, 110)
                tmpChangeEvents.add(tmpEvent)

                changeEvents
                        .find { it.position == 0 && it.type == ChangeEvent.VELOCITY }
                        ?.run { tmpEvent.velocity = velNumbers[velLetters.indexOf(value)] }

                initTempoKey = false

            } else {
                if (initTempoKey) {
                    initTempoKey = false
                    var i = nextIndex
                    var tmpTempo = -1
                    var tmpKey = -1

                    while (i > 0 && (tmpTempo == -1 || tmpKey == -1)) {
                        if (tmpTempo == -1)
                            changeEvents
                                    .find { it.position == i && it.type == ChangeEvent.MVMT }
                                    ?.run { tmpTempo = value.toInt() }

                        if (tmpKey == -1)
                            changeEvents
                                    .find { it.position == i && it.type == ChangeEvent.MOD }
                                    ?.run { tmpKey = modulationKeys.indexOf(value) }

                        i -= 1
                    }

                    if (tmpTempo == -1) tmpTempo = tempo
                    if (tmpKey == -1) tmpKey = key

                    tmpChangeEvents.add(ParserStructureEvent(0, tmpTempo, tmpKey, 110))

                } else {
                    val changes = changeEvents.filter {
                        it.position == nextIndex &&
                                arrayOf(ChangeEvent.MOD, ChangeEvent.MVMT, ChangeEvent.VELOCITY).contains(it.type)
                    }

                    if (!changes.isEmpty()) {
                        var tmpCh = tmpChangeEvents.find { it.position == realIndex }
                        if (tmpCh == null) {
                            tmpCh = ParserStructureEvent(position = realIndex)
                                    .also { tmpChangeEvents.add(it) }
                        }

                        changes.forEach { ch ->
                            when (ch.type) {
                                ChangeEvent.MOD -> tmpCh.key = modulationKeys.indexOf(ch.value)
                                ChangeEvent.MVMT -> tmpCh.tempo = ch.value.toInt()
                                ChangeEvent.VELOCITY -> tmpCh.velocity = velNumbers[velLetters.indexOf(ch.value)]
                            }
                        }
                    }
                }
            }

            // NOTES
            notesToParse.forEachIndexed { voice, voiceNotes ->
                if (notes[voice].size > nextIndex)
                    voiceNotes.add(notes[voice][nextIndex])
                else
                    voiceNotes.add("")
            }

            realIndex++

            // Fine
            val fine = changeEvents.find {
                it.type == ChangeEvent.SIGN && it.position == nextIndex && it.value == "Fin"
            }

            if (reachedEnd && fine != null) {
                return

            } else if (nextIndex == maxIndex - 1) {
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


    private fun parseSingleTimeNotes(note: String, voice: Int, index: Int): MutableList<Note> {
        val notes = mutableListOf<Note>()

        tmpChangeEvents
                .find { it.position == index }
                ?.also {
                    if (it.key >= 0)
                        key = it.key
                    if (it.tempo >= 0)
                        currentTempo = it.tempo
                    if (enableVelocity && it.velocity >= 0)
                        baseVelocity = it.velocity
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
            return mutableListOf(Note(voice, noteToPitch(note, voice), velocity, ticks[voice], currentTU - release))
                    .also { ticks[voice] += currentTU }
        } else {
            val subNotes = note.split(".")
            val noteCoeff = subNotes.size

            subNotes.forEach { subNote ->
                if (!subNote.contains(',')) {
                    if (subNote != "")
                        notes.add(Note(
                                voice,
                                noteToPitch(subNote, voice),
                                velocity,
                                ticks[voice],
                                currentTU / noteCoeff - release))

                    ticks[voice] += currentTU / noteCoeff
                } else {
                    val finalNotes = subNote.split(",")
                    val finalNoteCoeff = finalNotes.size

                    finalNotes.forEach { finalNote ->
                        if (finalNote != "")
                            notes.add(Note(
                                    voice,
                                    noteToPitch(finalNote, voice),
                                    velocity,
                                    ticks[voice],
                                    currentTU / (noteCoeff * finalNoteCoeff) - release
                            ))

                        ticks[voice] += currentTU / (noteCoeff * finalNoteCoeff)
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


    private fun noteToPitch(stringNote: String, voice: Int): Int {
        var pitch = -1
        val voiceOffset = when (voice) {
            2 -> -12
            3 -> -12
            else -> 0
        }

        if (stringNote == "")
            return -1
        if (stringNote == "-")
            return 0

        val noteMatch = noteP.matcher(stringNote)
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
        scale.forEachIndexed { i, n -> if (n == stringNote) pitch = 60 + i }
        return pitch
    }
}