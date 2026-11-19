package mg.maniry.doremii.soundfont

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import mg.maniry.doremii.R

fun showTimbrePicker(
    context: Context,
    presets: List<DiscoveredPreset>,
    onPick: (DiscoveredPreset) -> Unit,
) {
    val view = View.inflate(context, R.layout.timbre_picker, null)
    val list = view.findViewById<RecyclerView>(R.id.timbre_list)
    val empty = view.findViewById<TextView>(R.id.timbre_empty)
    val search = view.findViewById<EditText>(R.id.timbre_search)
    val grouped = TimbreGrouping.rows(presets)
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.timbre_label)
        .setView(view)
        .setNegativeButton(R.string.text_cancel, null)
        .create()
    val adapter = TimbreAdapter(grouped) { preset ->
        onPick(preset)
        dialog.dismiss()
    }
    list.layoutManager = LinearLayoutManager(context)
    list.adapter = adapter
    fun apply(query: String) {
        val rows = TimbreGrouping.filter(grouped, query)
        adapter.submit(rows)
        val showEmpty = rows.isEmpty()
        empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        list.visibility = if (showEmpty) View.GONE else View.VISIBLE
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            apply(s?.toString().orEmpty())
        }
    })
    apply("")
    dialog.show()
}

private class TimbreAdapter(
    private var rows: List<TimbreRow>,
    private val onPick: (DiscoveredPreset) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    fun submit(next: List<TimbreRow>) {
        rows = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = if (rows[position] is TimbreRow.Header) HEADER else PRESET

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val label = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        return if (viewType == HEADER) HeaderHolder(label) else PresetHolder(label, onPick)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is TimbreRow.Header -> (holder as HeaderHolder).bind(row.title)
            is TimbreRow.Preset -> (holder as PresetHolder).bind(row.preset)
        }
    }

    private class HeaderHolder(private val label: TextView) : RecyclerView.ViewHolder(label) {
        fun bind(title: String) {
            label.text = title
            label.setTextColor(label.context.getColor(R.color.colorPrimary))
        }
    }

    private class PresetHolder(
        private val label: TextView,
        private val onPick: (DiscoveredPreset) -> Unit,
    ) : RecyclerView.ViewHolder(label) {
        fun bind(preset: DiscoveredPreset) {
            label.text = preset.name
            label.setTextColor(label.context.getColor(R.color.dark))
            label.setOnClickListener { onPick(preset) }
        }
    }

    companion object {
        private const val HEADER = 0
        private const val PRESET = 1
    }
}
