package mg.maniry.doremii.editor.managers

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import mg.maniry.doremii.DoremiApp
import mg.maniry.doremii.R
import mg.maniry.doremii.R.id.*
import mg.maniry.doremii.commonUtils.PlaybackSettings
import mg.maniry.doremii.editor.EditorActivity
import mg.maniry.doremii.editor.partition.InstrumentsList
import mg.maniry.doremii.editor.partition.Labels
import mg.maniry.doremii.editor.partition.PartitionData
import mg.maniry.doremii.editor.partition.VoiceTimbre
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.partition.Player
import mg.maniry.doremii.soundfont.InstalledSoundFont
import mg.maniry.doremii.soundfont.SoundFontsActivity
import mg.maniry.doremii.soundfont.showTimbrePicker


class EditorTabManager(
    private val mainContext: Context,
    private val editorTab: View,
    private val editorViewModel: EditorViewModel,
    private val player: Player,
    private val mainView: View
) {
    private var signSpinInit = false
    private var keySpinInit = false
    private var voicesNumSpinInit = false
    private val voiceIdsSPinInit = mutableListOf<Boolean>()
    private val simpleListItem = android.R.layout.simple_list_item_1
    private val dropDownItem = android.R.layout.simple_spinner_dropdown_item
    private val tempoEditText = editorTab.findViewById<EditText>(tempo_edit_text)
    private val signatureSpinner = editorTab.findViewById<Spinner>(signature_spinner)
    private val keySpinner = editorTab.findViewById<Spinner>(key_spinner)
    private val instrConfigs = mutableListOf<InstrConfig>()
    private val instrConfigCont = editorTab.findViewById<LinearLayout>(instr_config_cont)
    private val voicesNumSpinner = editorTab.findViewById<Spinner>(voices_num_spin)
    private val soundFontInput = editorTab.findViewById<MaterialAutoCompleteTextView>(song_soundfont_input)
    private var installedFonts = emptyList<InstalledSoundFont>()
    private var updatingSoundFont = false
    private var soundFontKey = ""


    init {
        initSpinners()
        initTempoEditText()
        initSwingCheck()
        initLoopSpinner()
        observeReRender()
        observeVoiceToggles()
        initSongInfoUpdater()
        initSongTitleTextView()
        editorTab.findViewById<View>(manage_soundfonts).setOnClickListener {
            mainContext.startActivity(Intent(mainContext, SoundFontsActivity::class.java))
        }
        soundFontInput.setOnItemClickListener { _, _, position, _ ->
            if (updatingSoundFont) return@setOnItemClickListener
            val font = installedFonts.getOrNull(position) ?: return@setOnItemClickListener
            val currentId = editorViewModel.partitionData.timbres.value?.firstOrNull()?.soundFontId
            if (font.id == currentId) return@setOnItemClickListener
            applySongSoundFont(font)
        }
        val activity = mainContext as EditorActivity
        DoremiApp.from(mainContext).soundFonts.catalog.observe(activity) { fonts ->
            installedFonts = fonts.orEmpty()
            bindSoundFont()
        }
        editorViewModel.partitionData.timbres.observe(activity) {
            bindSoundFont()
        }
    }


    private fun observeReRender() {
        with(editorViewModel.partitionData) {
            signature.observe(mainContext as EditorActivity) {
                tempoEditText.setText(tempo.toString())

                for (i in 0 until voicesNum) {
                    completeInstrConfig(i)
                }
                trimInstrConfig(voicesNum)
                voicesNumSpinner.setSelection(voicesNum - 1)
            }
        }
    }


    private fun initTempoEditText() {
        tempoEditText.onChange {
            if (it == "") {
                tempoEditText.setBackgroundColor(Color.rgb(255, 120, 100))
            } else {
                val t = it.toInt()
                if (t in 31..399) {
                    editorViewModel.partitionData.tempo = t
                    tempoEditText.setBackgroundColor(Color.WHITE)
                } else {
                    tempoEditText.setBackgroundColor(Color.rgb(255, 120, 100))
                }
            }
        }
    }


    private fun initSwingCheck() {
        with(editorTab.findViewById<CheckBox>(swing_checkbx)) {
            setOnClickListener { editorViewModel.partitionData.toggleSwing() }
            editorViewModel.partitionData.swing.observe(mainContext as EditorActivity) {
                isChecked = editorViewModel.partitionData.swing.value ?: false
            }
        }
    }


    private fun initSpinners() {
        prepareSpinner(keySpinner, R.array.keys_list) {
            if (keySpinInit) {
                editorViewModel.partitionData.updateKey(it)
            } else {
                keySpinInit = true
            }
        }
        prepareSpinner(signatureSpinner, R.array.signature_list) {
            if (signSpinInit) {
                editorViewModel.partitionData.updateSignature(it + 2)
            } else {
                signSpinInit = true
            }
        }
        voicesNumSpinner.apply {
            adapter =
                ArrayAdapter(mainContext, simpleListItem, (1..12).map { it.toString() }).apply {
                    setDropDownViewResource(dropDownItem)
                }
            onChange {
                if (voicesNumSpinInit) {
                    editorViewModel.updateVoicesNum(it + 1)
                } else {
                    voicesNumSpinInit = true
                }
            }
        }
        with(editorViewModel.partitionData) {
            signature.observe(mainContext as EditorActivity) {
                it?.run { signatureSpinner.setSelection(it - 2) }
            }
            key.observe(mainContext) {
                it?.run { keySpinner.setSelection(it) }
            }
        }
    }


    private fun completeInstrConfig(index: Int) {
        val partitionData = editorViewModel.partitionData
        val voices = partitionData.voices

        while (instrConfigs.size <= index) {
            val rowIndex = instrConfigs.size
            voiceIdsSPinInit.add(false)

            InstrConfig(mainContext).apply {
                instrConfigCont.addView(cont)
                instrConfigs.add(this)

                voiceIdSpinner.apply {
                    adapter = ArrayAdapter(
                        mainContext, simpleListItem, PartitionData.voiceIds
                    ).apply { setDropDownViewResource(dropDownItem) }
                    onChange {
                        if (voiceIdsSPinInit[rowIndex]) {
                            partitionData.updateVoiceId(rowIndex, it)
                        } else {
                            voiceIdsSPinInit[rowIndex] = true
                        }
                    }
                    val voiceName = voices.getOrElse(rowIndex) { PartitionData.voiceIds[rowIndex % PartitionData.voiceIds.size] }
                    setSelection(PartitionData.voiceIds.indexOf(voiceName).coerceAtLeast(0))
                }

                muteButton.setOnClickListener { player.toggleMute(rowIndex) }
                soloButton.setOnClickListener { player.toggleSolo(rowIndex) }
                timbreButton.setOnClickListener { openTimbrePicker(rowIndex) }
                applyVoiceToggle(rowIndex, this)
                timbreButton.text = timbreLabel(
                    partitionData.timbres.value?.getOrNull(rowIndex) ?: VoiceTimbre.piano(),
                    sharedFont(),
                )
            }
        }
    }

    private fun bindSoundFont() {
        val key = installedFonts.joinToString("|") { it.id + it.displayName }
        if (soundFontKey != key) {
            soundFontKey = key
            soundFontInput.setAdapter(
                ArrayAdapter(
                    mainContext,
                    android.R.layout.simple_list_item_1,
                    installedFonts.map { it.displayName },
                )
            )
        }
        val font = sharedFont()
        updatingSoundFont = true
        soundFontInput.setText(font?.displayName.orEmpty(), false)
        updatingSoundFont = false
        instrConfigs.forEachIndexed { index, row ->
            val timbre = editorViewModel.partitionData.timbres.value?.getOrNull(index) ?: VoiceTimbre.piano()
            row.timbreButton.text = timbreLabel(timbre, font)
        }
    }

    private fun sharedFont(): InstalledSoundFont? {
        val preferred = editorViewModel.partitionData.timbres.value?.firstOrNull()?.soundFontId
        return installedFonts.firstOrNull { it.id == preferred }
            ?: installedFonts.firstOrNull { it.active }
            ?: installedFonts.firstOrNull { it.id == VoiceTimbre.PIANO_ID }
            ?: installedFonts.firstOrNull()
    }

    private fun applySongSoundFont(font: InstalledSoundFont) {
        val partitionData = editorViewModel.partitionData
        val current = partitionData.timbres.value.orEmpty()
        val first = font.presets.firstOrNull()
        val next = List(partitionData.voicesNum) { index ->
            val fallback = VoiceTimbre(font.id, first?.bank ?: 0, first?.program ?: 0)
            if (index == 0 || first == null) {
                fallback
            } else {
                val timbre = current.getOrNull(index) ?: VoiceTimbre.piano()
                val (bank, program) = timbre.midiBankAndProgram()
                val match = font.presets.firstOrNull { it.bank == bank && it.program == program }
                if (match != null) VoiceTimbre(font.id, match.bank, match.program) else fallback
            }
        }
        partitionData.replaceTimbres(next)
    }

    private fun openTimbrePicker(index: Int) {
        val font = sharedFont() ?: return
        showTimbrePicker(mainContext, font.presets) { preset ->
            editorViewModel.partitionData.setVoiceTimbre(
                index,
                VoiceTimbre(font.id, preset.bank, preset.program),
            )
        }
    }

    private fun applyVoiceToggle(index: Int, row: InstrConfig) {
        val muted = player.mutedVoices.value?.getOrNull(index) == true
        row.muteButton.setIconResource(if (muted) R.drawable.ic_speaker_off else R.drawable.ic_speaker)
        row.soloButton.showSolo(player.soloedVoices.value?.getOrNull(index) == true)
    }

    private fun refreshVoiceToggles() {
        instrConfigs.forEachIndexed { index, row -> applyVoiceToggle(index, row) }
    }

    private fun MaterialButton.showSolo(active: Boolean) {
        val primary = context.getColor(R.color.colorPrimary)
        val white = context.getColor(R.color.white)
        if (active) {
            backgroundTintList = ColorStateList.valueOf(primary)
            setTextColor(white)
            strokeWidth = 0
        } else {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(primary)
            strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            strokeColor = ColorStateList.valueOf(primary)
        }
    }

    private fun timbreLabel(timbre: VoiceTimbre, font: InstalledSoundFont?): String {
        val (bank, program) = timbre.midiBankAndProgram()
        val preset = font?.presets?.firstOrNull { it.bank == bank && it.program == program }?.name
        if (preset != null) return preset
        if (timbre.legacyIndex != null) {
            return InstrumentsList.list.getOrNull(timbre.legacyIndex)?.name ?: "Piano"
        }
        return "Program $program"
    }


    private fun trimInstrConfig(voicesNum: Int) {
        while (instrConfigs.size > voicesNum) {
            val last = instrConfigs[instrConfigs.size - 1]
            instrConfigCont.removeView(last.cont)
            instrConfigs.removeAt(instrConfigs.size - 1)
            voiceIdsSPinInit.removeAt(voiceIdsSPinInit.size - 1)
        }
    }


    private fun prepareSpinner(spinner: Spinner, listId: Int, callback: (i: Int) -> Unit) {
        spinner.apply {
            adapter = ArrayAdapter.createFromResource(
                mainContext, listId, android.R.layout.simple_spinner_item
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onChange(callback)
        }
    }


    private fun initLoopSpinner() {
        editorTab.findViewById<Spinner>(loop_spinner).apply {
            adapter = ArrayAdapter(
                mainContext,
                simpleListItem,
                (1..10).map { it.toString() }
            ).apply { setDropDownViewResource(dropDownItem) }
            setSelection(editorViewModel.playerLoops)
            onChange {
                editorViewModel.playerLoops = it
                PlaybackSettings.setLoops(mainContext, it)
            }
        }
    }


    private fun observeVoiceToggles() {
        val activity = mainContext as EditorActivity
        player.mutedVoices.observe(activity) { refreshVoiceToggles() }
        player.soloedVoices.observe(activity) { refreshVoiceToggles() }
    }


    private fun initSongInfoUpdater() {
        val targetValues = with(editorViewModel.partitionData.songInfo) {
            listOf(title, author, compositor, singer)
        }
        val fieldIds = with(Labels) { listOf(TITLE, AUTHOR, COMP, SINGER) }
        listOf(
            song_infos_titile, song_infos_aut, song_infos_comp, song_infos_singer
        ).forEachIndexed { i, id ->
            editorTab.findViewById<EditText>(id).apply {
                targetValues[i].observe(mainContext as EditorActivity) {
                    if (it != text.toString()) setText(it ?: "")
                }
                setOnFocusChangeListener { view, focused ->
                    if (!focused) editorViewModel.updateSongInfo(
                        fieldIds[i], (view as EditText).text.toString()
                    )
                }
            }
        }
    }

    private fun initSongTitleTextView() {
        editorViewModel.partitionData.songInfo.title.observe(
            mainContext as EditorActivity
        ) {
            it?.run { mainView.findViewById<TextView>(song_title_text_view).text = it }
        }
    }
}
