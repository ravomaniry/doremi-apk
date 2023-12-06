package mg.maniry.doremi.editor.managers


import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.views.ChangesDialog
import mg.maniry.doremi.R
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.editor.partition.ChangeEvent
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import java.lang.Exception


class ChangesDialogManager(
    private val mainContext: Context,
    private val mainView: View,
    private val editorViewModel: EditorViewModel
) {

    val dialogView: View = View.inflate(mainContext, R.layout.changes_dialog, null)

    private var position = 0
    private var changesInScope = mutableListOf<ChangeEvent>()

    private lateinit var signSpinner: Spinner
    private lateinit var dalSpinner: Spinner
    private lateinit var keysSpinner: Spinner
    private lateinit var veloSpinner: Spinner
    private lateinit var tempoEt: EditText
    private lateinit var signAdapter: ArrayAdapter<String>
    private lateinit var dalAdapter: ArrayAdapter<String>
    private lateinit var keysAdapter: ArrayAdapter<String>
    private lateinit var veloAdapter: ArrayAdapter<String>
    private val keys =
        mutableListOf("", "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private var signs = mutableListOf("", "$")
    private var dals = mutableListOf("")
    private val velocities = mutableListOf("", "pp", "p", "mp", "mf", "f", "ff")

    private val coordTv = dialogView.findViewById<TextView>(cd_time_coord)
    private val submitBtn = dialogView.findViewById<Button>(cd_submit_btn)

    private val dialog = ChangesDialog()


    val onDismiss = {
        editorViewModel.dialogOpen.value = false
        mainView.hideKeyboard()
    }


    init {
        observe()
        initAdapters()
        handleSubmit()
    }


    private fun observe() {
        editorViewModel.dialogOpen.observe(mainContext as EditorActivity) {
            try {
                if (it == true) {
                    dialog.show(mainContext.supportFragmentManager, "changes_dialog")
                    popupActions()
                } else if (it == false && !dialog.isHidden) {
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                println("Failed to Show changes dialog $e")
            }
        }
    }


    private fun initAdapters() {
        val simpleListItem = android.R.layout.simple_list_item_1
        val dropDownItem = android.R.layout.simple_spinner_dropdown_item

        with(dialogView) {
            veloSpinner = findViewById<Spinner>(cd_velocity_spinner).apply {
                adapter = ArrayAdapter(mainContext, simpleListItem, velocities)
                    .apply { setDropDownViewResource(dropDownItem) }
                    .also { veloAdapter = it }

                onChange { handleChange(ChangeEvent.VELOCITY, velocities[it]) }
            }

            signSpinner = findViewById<Spinner>(cd_sign_spinner).apply {
                adapter = ArrayAdapter(mainContext, simpleListItem, signs)
                    .apply { setDropDownViewResource(dropDownItem) }
                    .also { signAdapter = it }

                onChange { handleChange(ChangeEvent.SIGN, signs[it]) }
            }

            dalSpinner = findViewById<Spinner>(cd_dal_spinner).apply {
                adapter = ArrayAdapter(mainContext, simpleListItem, dals)
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    .also { dalAdapter = it }

                onChange { handleChange(ChangeEvent.DAL, dals[it]) }
            }

            keysSpinner = findViewById<Spinner>(cd_key_spinner).apply {
                adapter = ArrayAdapter(mainContext, simpleListItem, keys)
                    .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    .also { keysAdapter = it }

                onChange { handleChange(ChangeEvent.MOD, keys[it]) }
            }

            tempoEt = findViewById<EditText>(cd_tempo_et).apply {
                onChange {
                    if (it == "") {
                        setBackgroundColor(Color.rgb(255, 0, 0))
                        handleChange(ChangeEvent.TEMPO, "")
                    } else {
                        val t = it.toInt()

                        if (t in 31..399) {
                            handleChange(ChangeEvent.TEMPO, it)
                            setBackgroundColor(Color.WHITE)
                        } else {
                            handleChange(ChangeEvent.TEMPO, "")
                            setBackgroundColor(Color.rgb(255, 120, 100))
                        }
                    }
                }
            }
        }
    }


    @SuppressLint("SetTextI18n")
    private fun popupActions() {
        val partitionData = editorViewModel.partitionData
        val signature = partitionData.signature.value ?: 4
        position = editorViewModel.dialogPosition
        changesInScope = partitionData.changeEvents.asSequence()
            .filter { it.position == position }
            .toMutableList()

        coordTv.text = "${
            1 + Math.floor(position.toDouble() / signature).toInt()
        } : ${1 + position % signature}"

        // SUGGEST ALL POSSIBLE VALUES FOR DC&& D$
        dals = mutableListOf("")

        if (position % signature == signature - 1) {
            dals.add("DC")
        }

        val similarSigns = partitionData.changeEvents
            .asSequence()
            .filter { it.type == ChangeEvent.SIGN && it.value.startsWith("$") }
            .filter { it.position % signature == (position + 1) % signature }
            .toList()

        if (!similarSigns.isEmpty())
            dals.addAll(similarSigns.map { "D" + it.value })

        dalAdapter.apply {
            clear()
            addAll(dals)
            notifyDataSetChanged()
        }

        // SIGNS
        signs = mutableListOf("", getDollarSign())
        with(partitionData.changeEvents.find { it.type == ChangeEvent.SIGN && it.value == "Fin" }) {
            if (this == null || this.position == this@ChangesDialogManager.position)
                signs.add("Fin")
        }

        signAdapter.apply {
            clear()
            addAll(signs)
            notifyDataSetChanged()
        }

        // CURSOR THE CORRECT VALUE IN SPINNERS
        val values = listOf(velocities, signs, dals, keys)
        val spinners = listOf(veloSpinner, signSpinner, dalSpinner, keysSpinner)
        listOf(
            ChangeEvent.VELOCITY,
            ChangeEvent.SIGN,
            ChangeEvent.DAL,
            ChangeEvent.MOD
        ).forEachIndexed { i, type ->
            val relatedChange = changesInScope.filter { it.type == type }

            if (relatedChange.isNotEmpty()) {
                spinners[i].setSelection(values[i].indexOf(relatedChange[0].value))
            } else {
                spinners[i].setSelection(0)
            }
        }

        // TEMPO ET
        changesInScope.find { it.type == ChangeEvent.TEMPO }.also {
            tempoEt.setText(it?.value ?: "")
        }
    }


    private fun handleChange(updatedType: String, value: String) {
        changesInScope =
            changesInScope.asSequence().filter { it.type != updatedType }.toMutableList()

        if (value != "")
            changesInScope.add(ChangeEvent(position, updatedType, value))
    }


    private fun handleSubmit() {
        submitBtn.setOnClickListener {
            editorViewModel.partitionData.updateChangeEvents(position, changesInScope)
            editorViewModel.headerTvTrigger.value = true
            onDismiss()
        }
    }


    private fun getDollarSign(): String {
        val insertedSigns = editorViewModel.partitionData.changeEvents
            .filter { it.type == ChangeEvent.SIGN && it.value.startsWith('$') }

        if (insertedSigns.isEmpty())
            return "$"

        with(insertedSigns.find { it.position == position }) {
            if (this != null)
                return value
        }

        var i = 1
        with(insertedSigns.map { it.value }) {
            while (contains("$$i"))
                i++
        }

        return "$$i"
    }
}