package mg.maniry.doremi.ui.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import mg.maniry.doremi.R
import mg.maniry.doremi.ui.MainActivity
import mg.maniry.doremi.viewModels.EditorViewModel
import mg.maniry.doremi.partition.Player


class EditorTabManager(
        private val mainContext: Context,
        private val editorTab: View,
        private val editorViewModel: EditorViewModel,
        private val player: Player) {


    private val tempoEditText = editorTab.findViewById<EditText>(R.id.tempo_edit_text)

    private val signatureSpinner = editorTab.findViewById<Spinner>(R.id.signature_spinner)
    private var signSpinInit = false
    private val keySpinner = editorTab.findViewById<Spinner>(R.id.key_spinner)
    private var keySpinInit = false


    init {
        initSpinners()
        initTempoEditText()
        initSwingCheck()
        initVelocityChb()
        initVoicesMuter()

        with(editorViewModel.partitionData) {
            signature.observe(mainContext as MainActivity, Observer {
                tempoEditText.setText(tempo.toString())
            })
        }
    }


    private fun initTempoEditText() {
//        tempoEditText.setText(editorViewModel.partitionData.tempo.toString())
        setTempoListener {
            editorViewModel.partitionData.tempo = it
        }
    }


    private fun initSwingCheck() {
        with(editorTab.findViewById<CheckBox>(R.id.swing_checkbx)) {
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


    private fun setTempoListener(callback: (i: Int) -> Unit) {
        tempoEditText.onChange {
            if (it == "") {
                tempoEditText.setBackgroundColor(Color.rgb(255, 120, 100))

            } else {
                val t = it.toInt()

                if (t in 31..399) {
                    callback(t)
                    tempoEditText.setBackgroundColor(Color.WHITE)
                } else {
                    tempoEditText.setBackgroundColor(Color.rgb(255, 120, 100))
                }
            }
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
        val boxes = listOf(R.id.soprano_cbx, R.id.alto_cbx, R.id.tenor_cbx, R.id.baritone_cbx).map {
            editorTab.findViewById<CheckBox>(it)
        }.also {
            it.forEachIndexed { index, box ->
                box.setOnClickListener { player.toggleVoice(index) }
            }
        }

        player.playedVoices.observe(mainContext as MainActivity, Observer { voices ->
            voices?.forEachIndexed { i, value -> boxes[i].isChecked = value }
        })
    }


    private fun initVelocityChb() {
        val chb = editorTab.findViewById<CheckBox>(R.id.velocity_chbx).apply {
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
}