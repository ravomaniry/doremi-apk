package mg.maniry.doremi.editor.partition


import mg.maniry.doremi.commonUtils.FileManager
import mg.maniry.doremi.editor.views.NotesToSpan
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class HtmlExport(
        private var partitionData: PartitionData) {

    data class MeasureResult(val table: String, val finished: Boolean)

    private val notes = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val suffix = "</body></html>"
    private val bold = "font-weight:bold;"
    private val center = "text-align:center;"
    private val right = "text-align:right;"
    private val left = "text-align:left;"
    private val big = "font-size:24px;"
    private val inlineBlock = "display:inline-block;"
    private val italic = "font-style:italic;"
    private val tdMinWidth = "min-width:10px;"
    private val borders = "border-left:1px solid #000;border-right:1px solid #000;"
    private val headerMargin = "margin-bottom:20px;"
    private val measureMargins = "margin: 10px 0 0 0;"
    private val flex = "display:flex;flex-wrap:wrap;align-items:baseline;"
    private val spaceBetween = "justify-content:space-between;"
    private val spaceAround = "justify-content:space-around;"
    private val fontFamily = "font-family:sans-serif;"
    private val lyricsMargins = "margin: 20px 0 0 0;"
    private val linebreakRegex = Regex("\n +\n([ \n])*")
    private val verseMargin = "margin: 0 20px 10px 0"
    private val octaveStyles = "line-height:0;font-size:8px;$italic"
    private val small = "font-size:0.7em;"
    private val collapse = "border-collapse:collapse;"


    fun print(callback: () -> Unit) {
        doAsync {
            val html = prefix() + header() + solfa() + lyrics() + footer() + suffix
            FileManager.writeHtml(partitionData.songInfo.filename, html)

            uiThread { callback() }
        }
    }


    private fun prefix() = with(partitionData.songInfo) {
        "<html><head><title>${title.value}</title></head><body style=\"$fontFamily\">"
    }


    private fun header(): String {
        return title() +
                "<div style=\"$spaceBetween$flex$headerMargin\">" +
                structure() +
                singer() +
                "</div>"
    }

    private fun title() = with(partitionData.songInfo.title) {
        "<div style=\"$center$big$bold\">${value ?: ""}</div>"
    }


    private fun structure(): String {
        with(partitionData) {
            var str = "<div>" +
                    "<div style=\"$bold\">${signature.value}/4</div>" +
                    "<div style=\"$bold\">Do dia ${this@HtmlExport.notes[key.value!!]}</div>" +
                    "<div style=\"$bold\">Tempo: $tempo bpm</div>"

            if (swing.value == true) {
                str += "<div style=\"$italic\"><span style=\"$bold\">Swing</span>" +
                        "<span style=\"$small\">" +
                        "(vakiana hoe \"d.-.d\" ny \"d.d\" sy ny \"d.,d\")" +
                        "</span></div>"
            }

            str += "</div>"

            return str
        }
    }


    private fun singer() = with(partitionData.songInfo.singer) {
        "<div style=\"$right\">${value ?: ""}</div>"
    }


    private fun footer() = with(partitionData.songInfo) {
        var str = "<div style=\"$right$italic\">" +
                "<div style=\"$inlineBlock$left\">"

        if (author.value != "")
            str += "<div>Tonony: ${author.value ?: ""}</div>"

        if (compositor.value != "")
            str += "<div>Feony: ${compositor.value ?: ""}</div>"

        if (releaseDate.value != "")
            str += "<div>${releaseDate.value ?: ""}</div>"

        return@with "$str</div></div>"
    }


    private fun solfa(): String {
        var html = "<div style=\"$flex\">"
        var finished = false
        var i = 0

        while (!finished) {
            with(measure(i)) {
                finished = this.finished
                html += table
            }
            i++
        }

        html += "</div>"

        return html
    }


    private fun measure(measureIndex: Int): MeasureResult {
        val signature = partitionData.signature.value ?: 4
        var finished = true
        var index: Int
        var table = "<table style=\"$collapse$measureMargins\"><tbody>"
        val rows = Array(4) { "" }


        // Changes + signs
        var headerIndex: Int
        val events = mutableListOf<List<List<ChangeEvent?>>>()
        for (h in 0 until (signature)) {
            headerIndex = measureIndex * signature + h
            events.add(with(ChangeEvent) {
                listOf(listOf(VELOCITY, SIGN, DAL), listOf(MOD), listOf(MVMT))
            }.asSequence().map { group ->
                group.asSequence()
                        .map { type ->
                            partitionData.changeEvents.find { it.type == type && it.position == headerIndex }
                        }
                        .filter { it != null }
                        .toList()
            }.toList())
        }

        var headerHtml = "<tr>"
        events.forEachIndexed { i, timeEvent ->
            if (timeEvent.isNotEmpty()) {
                headerHtml += "<td style=\"$italic$small\">"
                timeEvent.forEach { group ->
                    if (group.isNotEmpty()) {
                        headerHtml += "<div style=\"$center\">"
                        group.forEach { e ->
                            e?.value?.run {
                                headerHtml += " " + when (e.type) {
                                    ChangeEvent.MOD -> "<b>Do=${e.value}</b>"
                                    ChangeEvent.MVMT -> "T=${e.value}"
                                    else -> e.value
                                }
                            }
                        }

                        headerHtml += "</div>"
                    }
                }
                headerHtml += "</td>"
            } else {
                headerHtml += "<td/>"
            }
            if (i < signature - 1) {
                headerHtml += "<td/>"
            }
        }

        headerHtml += "</tr>"
        table += headerHtml

        for (voice in 0 until 4) {
            rows[voice] += "<tr style=\"$borders\">"

            for (i in 0 until (signature)) {
                index = measureIndex * signature + i
                rows[voice] += "<td style=\"$tdMinWidth$center\">"

                if (partitionData.notes[voice].size > index) {
                    finished = false
                    rows[voice] += supSubOctave(partitionData.notes[voice][index])
                }

                rows[voice] += "</td>"

                if (i != signature - 1) {
                    rows[voice] += "<td style=\"$center$tdMinWidth\">"
                    rows[voice] += if (signature % 2 == 0 && i % signature == 1) "|" else ":"
                    rows[voice] += "</td>"
                }
            }

            rows[voice] += "</tr>"
        }

        table += rows.joinToString("") + "</tbody></table>"

        if (finished)
            table = ""

        return MeasureResult(table, finished)
    }


    private fun supSubOctave(strNotes: String): String {
        return if (strNotes.length <= 1) {
            strNotes

        } else {
            var htmlNotes = ""
            var index = 0
            var skipNext = false

            for (i in 0..(strNotes.length - 1)) {
                if (!skipNext) {
                    if (NotesToSpan.symbols.contains(strNotes[i])) {
                        if (strNotes[i] == '-') {
                            if (i < strNotes.length - 1 && NotesToSpan.symbols.contains(strNotes[i + 1])) {
                                htmlNotes += "<sub style=\"$octaveStyles\">${strNotes[i + 1]}</sub>"
                                skipNext = true
                                index++
                            } else {
                                htmlNotes += strNotes[i]
                                index++
                            }

                        } else {
                            htmlNotes += "<sup style=\"$octaveStyles\">${strNotes[i]}</sup>"
                            index++
                        }

                    } else {
                        htmlNotes += strNotes[i]
                        index++
                    }

                } else {
                    skipNext = false
                }
            }

            return htmlNotes
        }
    }


    private fun lyrics(): String {
        var html = "<div style=\"$lyricsMargins$flex$spaceAround\">"

        html += partitionData.lyrics.value
                ?.replace(linebreakRegex, "\n\n")
                ?.split("\n\n")
                ?.asSequence()
                ?.map {
                    "<div><pre style=\"$fontFamily$verseMargin\">${it.replace(">", "").replace("<", "")}</pre></div>"
                }?.joinToString("")

        html += "</div>"

        return html
    }
}