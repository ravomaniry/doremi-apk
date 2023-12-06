package mg.maniry.doremi.editor.managers

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.partition.PartitionData
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import mg.maniry.doremi.editor.partition.Player


class EditorTabManager(
    private val mainContext: Context,
    private val editorTab: View,
    private val editorViewModel: EditorViewModel,
    private val player: Player
) {
    private var signSpinInit = false
    private var keySpinInit = false
    private var voicesNumSpinInit = false
    private val voiceIdsSPinInit = mutableListOf<Boolean>()
    private var instrumentsSpinInit = false
    private val simpleListItem = android.R.layout.simple_list_item_1
    private val dropDownItem = android.R.layout.simple_spinner_dropdown_item
    private val tempoEditText = editorTab.findViewById<EditText>(tempo_edit_text)
    private val signatureSpinner = editorTab.findViewById<Spinner>(signature_spinner)
    private val keySpinner = editorTab.findViewById<Spinner>(key_spinner)
    private val instrConfigs = mutableListOf<InstrConfig>()
    private val instrConfigCont = editorTab.findViewById<LinearLayout>(instr_config_cont)
    private val voicesNumSpinner = editorTab.findViewById<Spinner>(voices_num_spin)
    private val instrumentsSpinner = editorTab.findViewById<Spinner>(instrument_spin)


    init {
        initSpinners()
        initTempoEditText()
        initSwingCheck()
        initVelocityChb()
        observeMutedVoices()
        initLoopSpinner()
        observeReRender()
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
        val instrumentsList = mainContext.resources.getStringArray(R.array.instruments_list)
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
        prepareSpinner(instrumentsSpinner, R.array.instruments_list) {
            if (instrumentsSpinInit) {
                editorViewModel.onInstrumentChanged(instrumentsList[it])
            } else {
                instrumentsSpinInit = true
            }
        }
        editorViewModel.instrument.observe(mainContext as EditorActivity) {
            it?.run { instrumentsSpinner.setSelection(instrumentsList.indexOf(it)) }
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


    private fun observeMutedVoices() {
        player.playedVoices.observe(mainContext as EditorActivity) { voices ->
            voices?.forEachIndexed { i, value ->
                completeInstrConfig(i)
                instrConfigs[i].muteCheckbox.isChecked = value
            }
        }
    }


    private fun initVelocityChb() {
        val chb = editorTab.findViewById<CheckBox>(velocity_chbx).apply {
            setOnClickListener {
                with(editorViewModel.enablePlayerVelocity) {
                    value = !(value ?: false)
                }
            }
        }

        editorViewModel.enablePlayerVelocity.observe(mainContext as EditorActivity) {
            chb.isChecked = it ?: false
        }
    }

    private fun initLoopSpinner() {
        editorTab.findViewById<Spinner>(loop_spinner).apply {
            adapter = ArrayAdapter(
                mainContext,
                simpleListItem,
                (1..10).map { it.toString() }).apply { setDropDownViewResource(dropDownItem) }
            onChange { editorViewModel.playerLoops = it }
        }
    }
}
