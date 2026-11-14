package mg.maniry.doremii.editor.partition


import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.TR
import kotlinx.html.b
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.sub
import kotlinx.html.sup
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.title
import kotlinx.html.tr
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.editor.views.NotesToSpan


class HtmlExport(
    private var partitionData: PartitionData
) {

    companion object {
        val notes = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    }

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


    fun export(): String {
        return FileManager.writeHtml(partitionData.songInfo.filename, render())
    }


    fun render(): String = createHTML(prettyPrint = false).html {
        head {
            title { +(partitionData.songInfo.title.value ?: "") }
        }
        body {
            style = fontFamily
            header()
            solfa()
            lyrics()
            footer()
        }
    }


    private fun FlowContent.header() {
        div {
            style = "$center$big$bold"
            +(partitionData.songInfo.title.value ?: "")
        }
        div {
            style = "$spaceBetween$flex$headerMargin"
            structure()
            singer()
        }
    }


    private fun FlowContent.structure() {
        div {
            div {
                style = bold
                +"${partitionData.signature.value}/4"
            }
            div {
                style = bold
                +"Do dia ${notes[partitionData.key.value!!]}"
            }
            div {
                style = bold
                +"Tempo: ${partitionData.tempo} bpm"
            }
            if (partitionData.swing.value == true) {
                div {
                    style = italic
                    span {
                        style = bold
                        +"Swing"
                    }
                    span {
                        style = small
                        +"(vakiana hoe \"d.-.d\" ny \"d.d\" sy ny \"d.,d\")"
                    }
                }
            }
        }
    }


    private fun FlowContent.singer() {
        div {
            style = right
            +(partitionData.songInfo.singer.value ?: "")
        }
    }


    private fun FlowContent.footer() {
        with(partitionData.songInfo) {
            div {
                style = "$right$italic"
                div {
                    style = "$inlineBlock$left"
                    if (author.value != "") {
                        div { +"Tonony: ${author.value ?: ""}" }
                    }
                    if (compositor.value != "") {
                        div { +"Feony: ${compositor.value ?: ""}" }
                    }
                    if (releaseDate.value != "") {
                        div { +(releaseDate.value ?: "") }
                    }
                }
            }
        }
    }


    private fun FlowContent.solfa() {
        div {
            style = flex
            var measureIndex = 0
            while (measureHasNotes(measureIndex)) {
                measureTable(measureIndex)
                measureIndex++
            }
        }
    }


    private fun measureHasNotes(measureIndex: Int): Boolean {
        val signature = partitionData.signature.value ?: 4
        for (voice in 0 until 4) {
            for (beat in 0 until signature) {
                val index = measureIndex * signature + beat
                if (partitionData.notes[voice].size > index) {
                    return true
                }
            }
        }
        return false
    }


    private fun DIV.measureTable(measureIndex: Int) {
        val signature = partitionData.signature.value ?: 4
        table {
            style = "$collapse$measureMargins"
            tbody {
                tr {
                    for (beat in 0 until signature) {
                        headerCell(measureIndex * signature + beat)
                        if (beat < signature - 1) {
                            td()
                        }
                    }
                }
                for (voice in 0 until 4) {
                    tr {
                        style = borders
                        for (beat in 0 until signature) {
                            val index = measureIndex * signature + beat
                            td {
                                style = "$tdMinWidth$center"
                                if (partitionData.notes[voice].size > index) {
                                    solfaNotes(partitionData.notes[voice][index])
                                }
                            }
                            if (beat != signature - 1) {
                                td {
                                    style = "$center$tdMinWidth"
                                    +if (signature % 2 == 0 && beat % signature == 1) "|" else ":"
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun kotlinx.html.TR.headerCell(position: Int) {
        val groups = with(ChangeEvent) {
            listOf(
                listOf(VELOCITY, SIGN, DAL),
                listOf(MOD),
                listOf(TEMPO)
            )
        }.map { group ->
            group.mapNotNull { type ->
                partitionData.changeEvents.find { it.type == type && it.position == position }
            }
        }

        td {
            style = "$italic$small"
            groups.forEach { group ->
                if (group.isNotEmpty()) {
                    div {
                        style = center
                        group.forEach { event ->
                            event.value.let {
                                +" "
                                when (event.type) {
                                    ChangeEvent.MOD -> b { +"Do=${event.value}" }
                                    ChangeEvent.TEMPO -> +"T=${event.value}"
                                    else -> +event.value
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun FlowContent.solfaNotes(strNotes: String) {
        if (strNotes.length <= 1) {
            +strNotes
            return
        }

        var skipNext = false
        for (i in strNotes.indices) {
            if (skipNext) {
                skipNext = false
                continue
            }
            val symbol = strNotes[i]
            if (symbol in NotesToSpan.symbols) {
                if (symbol == '-' && i < strNotes.lastIndex && strNotes[i + 1] in NotesToSpan.symbols) {
                    sub {
                        style = octaveStyles
                        +strNotes[i + 1].toString()
                    }
                    skipNext = true
                } else if (symbol == '-') {
                    +symbol.toString()
                } else {
                    sup {
                        style = octaveStyles
                        +symbol.toString()
                    }
                }
            } else {
                +symbol.toString()
            }
        }
    }


    private fun FlowContent.lyrics() {
        div {
            style = "$lyricsMargins$flex$spaceAround"
            partitionData.lyrics.value
                ?.replace(linebreakRegex, "\n\n")
                ?.split("\n\n")
                ?.forEach { verse ->
                    div {
                        pre {
                            style = "$fontFamily$verseMargin"
                            +verse.replace(">", "").replace("<", "")
                        }
                    }
                }
        }
    }
}
