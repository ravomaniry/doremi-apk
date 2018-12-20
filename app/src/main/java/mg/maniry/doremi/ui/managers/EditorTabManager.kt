package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.partition.InstrumentsList
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.partition.Player


class EditorTabManager(
        private val mainContext: Context,
        private val editorTab: View,
        private val editorViewModel: EditorViewModel,
        private val player: Player) {

    private val simpleListItem = android.R.layout.simple_list_item_1
    private val dropDownItem = android.R.layout.simple_spinner_dropdown_item

    private val tempoEditText = editorTab.findViewById<EditText>(tempo_edit_text)
    private val signatureSpinner = editorTab.findViewById<Spinner>(signature_spinner)
    private var signSpinInit = false
    private val keySpinner = editorTab.findViewById<Spinner>(key_spinner)
    private var keySpinInit = false
    private val instrSpinners = arrayOf(instru_spin_s, instru_spin_a, instru_spin_t, instru_spin_b)
            .map { editorTab.findViewById<Spinner>(it) }


    init {
        initSpinners()
        initTempoEditText()
        initSwingCheck()
        initVelocityChb()
        initVoicesMuter()
        initInstrSpinners()
        initLoopSpinner()
    }


    private fun initTempoEditText() {
        with(editorViewModel.partitionData) {
            signature.observe(mainContext as MainActivity, Observer {
                tempoEditText.setText(tempo.toString())
            })
        }

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
            editorViewModel.partitionData.swing.observe(mainContext as MainActivity, Observer {
                isChecked = editorViewModel.partitionData.swing.value ?: false
            })
        }
    }


    private fun initSpinners() {
        prepareSpinner(keySpinner, R.array.keys_list) {
            if (keySpinInit)
                editorViewModel.partitionData.updateKey(it)
            else
                keySpinInit = true
        }

        prepareSpinner(signatureSpinner, R.array.signature_list) {
            if (signSpinInit)
                editorViewModel.partitionData.updateSignature(it + 2)
            else
                signSpinInit = true
        }

        with(editorViewModel.partitionData) {
            signature.observe(mainContext as MainActivity, Observer {
                if (it != null)
                    signatureSpinner.setSelection(it - 2)
            })

            key.observe(mainContext, Observer {
                if (it != null)
                    keySpinner.setSelection(it)
            })
        }
    }


    private fun prepareSpinner(spinner: Spinner, listId: Int, callback: (i: Int) -> Unit) {
        spinner.apply {
            adapter = ArrayAdapter.createFromResource(mainContext, listId, android.R.layout.simple_spinner_item).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            onChange(callback)
        }
    }


    private fun initVoicesMuter() {
        val boxes = listOf(soprano_cbx, alto_cbx, tenor_cbx, baritone_cbx).map {
            editorTab.findViewById<CheckBox>(it)
        }.also { boxes ->
            boxes.forEachIndexed { index, box ->
                box.setOnClickListener { player.toggleVoice(index) }
            }
        }

        player.playedVoices.observe(mainContext as MainActivity, Observer { voices ->
            voices?.forEachIndexed { i, value -> boxes[i].isChecked = value }
        })
    }


    private fun initVelocityChb() {
        val chb = editorTab.findViewById<CheckBox>(velocity_chbx).apply {
            setOnClickListener {
                with(editorViewModel.enablePlayerVelocity) {
                    value = !(value ?: false)
                }
            }
        }

        editorViewModel.enablePlayerVelocity.observe(mainContext as MainActivity, Observer {
            chb.isChecked = it ?: false
        })
    }


    private fun initInstrSpinners() {
        val instrList = InstrumentsList.list.map { it.name }

        instrSpinners.forEachIndexed { v, spinner ->
            spinner.apply {
                adapter = ArrayAdapter(mainContext, simpleListItem, instrList)
                        .apply { setDropDownViewResource(dropDownItem) }
                onChange { editorViewModel.partitionData.instruments[v].value = it }
            }
        }

        editorViewModel.partitionData.instruments.forEachIndexed { v, instr ->
            instr.observe(mainContext as MainActivity, Observer {
                instrSpinners[v].setSelection(instr.value ?: 0)
            })
        }
    }


    private fun initLoopSpinner() {
        editorTab.findViewById<Spinner>(loop_spinner).apply {
            adapter = ArrayAdapter(mainContext, simpleListItem, (1..10).map { it.toString() })
                    .apply { setDropDownViewResource(dropDownItem) }

            onChange { editorViewModel.playerLoops = it }
        }
    }
}