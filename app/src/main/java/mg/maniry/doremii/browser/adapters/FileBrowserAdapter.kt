package mg.maniry.doremii.browser.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.ActionTypes
import java.util.Locale


class FileBrowserAdapter(
    private var fileNames: List<String>, private val onClick: (type: Int, value: String) -> Unit
) : RecyclerView.Adapter<FileBrowserAdapter.ViewHolder>() {

    var filterValue = ""
    private var tmpValues = fileNames.toList()
    private var activeFile = ""
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.files_list_item, parent, false)
    )


    override fun getItemCount() = tmpValues.size


    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.apply {
            tmpValues[position].also { filename ->
                name.apply {
                    text = filename
                    setOnClickListener { onClick(ActionTypes.OPEN, filename) }
                }

                actions.setOnClickListener {
                    activeFile = filename
                    showMenu(it)
                }
            }
        }
    }


    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.list_item_name)
        val actions: ImageView = itemView.findViewById(R.id.list_item_share_btn)
    }


    fun update(newList: List<String>) {
        fileNames = newList
        search()
    }


    fun deleteFile(name: String) {
        update(fileNames.filter { it != name })
    }


    fun search(str: String = filterValue) {
        filterValue = str

        if (str == "") {
            tmpValues = fileNames
            notifyDataSetChanged()
        } else {
            searchScope.launch {
                val tmpFilterValue = filterValue
                val filtered = withContext(Dispatchers.Default) {
                    fileNames.filter(this@FileBrowserAdapter::searchCallback)
                }
                if (tmpFilterValue == filterValue) {
                    tmpValues = filtered
                    notifyDataSetChanged()
                }
            }
        }
    }


    private fun searchCallback(value: String): Boolean {
        val parts = filterValue.lowercase(Locale.getDefault()).split(" ")
        val filename = value.trim().lowercase(Locale.getDefault())

        parts.forEach {
            if (it != "" && !filename.contains(it)) return false
        }

        return true
    }


    private fun showMenu(v: View) {
        PopupMenu(v.context, v).apply {
            menuInflater.inflate(R.menu.actions_menu, menu)
            setForceShowIcon(true)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.delete_action -> {
                        onClick(ActionTypes.DELETE, activeFile)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }
}
