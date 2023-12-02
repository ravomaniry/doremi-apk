package mg.maniry.doremi.browser.adapters

import android.annotation.SuppressLint
import android.support.v7.view.menu.MenuBuilder
import android.support.v7.view.menu.MenuPopupHelper
import android.support.v7.widget.RecyclerView
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import kotlinx.android.synthetic.main.files_list_item.view.*
import mg.maniry.doremi.R
import mg.maniry.doremi.browser.ActionTypes
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class FileBrowserAdapter(
    private var fileNames: List<String>, private val onClick: (type: Int, value: String) -> Unit
) : RecyclerView.Adapter<FileBrowserAdapter.ViewHolder>() {

    var filterValue = ""
    private var tmpValues = fileNames.toList()
    private var activeFile = ""


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
        val name: TextView = itemView.list_item_name
        val actions: ImageView = itemView.list_item_share_btn
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
            doAsync {
                val tmpFilterValue = filterValue
                tmpValues = fileNames.filter(this@FileBrowserAdapter::searchCallback)
                uiThread {
                    if (tmpFilterValue == filterValue) notifyDataSetChanged()
                }
            }
        }
    }


    private fun searchCallback(value: String): Boolean {
        val parts = filterValue.toLowerCase().split(" ")
        val filename = value.trim().toLowerCase()

        parts.forEach {
            if (it != "" && !filename.contains(it)) return false
        }

        return true
    }


    @SuppressLint("RestrictedApi")
    private fun showMenu(v: View) {
        val builder = MenuBuilder(v.context).apply {
            MenuInflater(v.context).inflate(R.menu.actions_menu, this)
            onSelect {
                when (it.itemId) {
                    R.id.delete_action -> onClick(ActionTypes.DELETE, activeFile)
                }
            }
        }

        MenuPopupHelper(v.context, builder, v).run {
            setForceShowIcon(true)
            show()
        }
    }
}
