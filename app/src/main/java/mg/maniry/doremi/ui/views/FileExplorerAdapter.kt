package mg.maniry.doremi.ui.views

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.android.synthetic.main.files_list_item.view.*
import mg.maniry.doremi.R
import mg.maniry.doremi.ui.managers.ActionTypes
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class FileExplorerAdapter(
        private var fileNames: List<String>,
        private val onClick: (type: Int, value: String) -> Unit) : RecyclerView.Adapter<FileExplorerAdapter.ViewHolder>() {

    var filterValue = ""
    private var tmpValues = fileNames.toList()


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.files_list_item, parent, false))


    override fun getItemCount() = tmpValues.size


    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.apply {
            tmpValues[position].also { filename ->
                name.text = filename
                with(itemView) {
                    list_item_name.setOnClickListener { onClick(ActionTypes.OPEN, filename) }
                    list_item_share_btn.setOnClickListener { onClick(ActionTypes.SHARE, filename) }
                    list_item_delete_btn.setOnClickListener { onClick(ActionTypes.DELETE, filename) }
                }
            }
        }
    }


    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.list_item_name
    }


    fun update(newList: List<String>) {
        fileNames = newList
        search()
    }


    fun search(str: String = filterValue) {
        filterValue = str

        if (str == "") {
            tmpValues = fileNames
            notifyDataSetChanged()

        } else {
            doAsync {
                val tmpFilterValue = filterValue
                tmpValues = fileNames.filter(this@FileExplorerAdapter::searchCallback)
                uiThread {
                    if (tmpFilterValue == filterValue)
                        notifyDataSetChanged()
                }
            }
        }
    }


    private fun searchCallback(value: String): Boolean {
        val parts = filterValue.toLowerCase().split(" ")
        val filename = value.trim().toLowerCase()

        parts.forEach {
            if (it != "" && !filename.contains(it))
                return false
        }

        return true
    }
}
