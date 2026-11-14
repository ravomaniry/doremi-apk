package mg.maniry.doremii.editor.managers

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import mg.maniry.doremii.R
import mg.maniry.doremii.R.id.*
import mg.maniry.doremii.commonUtils.PlaybackSettings
import mg.maniry.doremii.editor.EditorActivity
import mg.maniry.doremii.editor.partition.Labels
import mg.maniry.doremii.editor.partition.PartitionData
import mg.maniry.doremii.editor.viewModels.EditorViewModel
import mg.maniry.doremii.editor.partition.Player


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


    init {
        initSpinners()
        initTempoEditText()
        initSwingCheck()
        initLoopSpinner()
        observeMutedVoices()
        observeReRender()
        initSongInfoUpdater()
        initSongTitleTextView()
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
        val playedVoices = player.playedVoices.value

        while (instrConfigs.size <= index) {
            voiceIdsSPinInit.add(false)

            InstrConfig(mainContext).apply {
                instrConfigCont.addView(cont)
                instrConfigs.add(this)

                voiceIdSpinner.apply {
                    adapter = ArrayAdapter(
                        mainContext, simpleListItem, PartitionData.voiceIds
                    ).apply { setDropDownViewResource(dropDownItem) }
                    onChange {
                        if (voiceIdsSPinInit[index]) {
                            partitionData.updateVoiceId(index, it)
                        } else {
                            voiceIdsSPinInit[index] = true
                        }
                    }
                    setSelection(PartitionData.voiceIds.indexOf(voices[index]))
                }

                muteCheckbox.apply {
                    isChecked =
                        playedVoices != null && playedVoices.size > index && playedVoices[index]
                    setOnClickListener { player.toggleVoice(index) }
                }
            }
        }
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


    private fun observeMutedVoices() {
        player.playedVoices.observe(mainContext as EditorActivity) { voices ->
            voices?.forEachIndexed { i, value ->
                completeInstrConfig(i)
                instrConfigs[i].muteCheckbox.isChecked = value
            }
        }
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
