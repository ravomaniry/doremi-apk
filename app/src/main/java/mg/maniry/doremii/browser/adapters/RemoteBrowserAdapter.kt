package mg.maniry.doremii.browser.adapters

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.android.synthetic.main.remote_files_list_item.view.*
import mg.maniry.doremii.R
import mg.maniry.doremii.browser.RemoteSolfa


class RemoteBrowserAdapter(
        private val onDownload: (Int) -> Unit) : RecyclerView.Adapter<RemoteBrowserAdapter.ViewHolder>() {


    private var list = mutableListOf<RemoteSolfa>()


    fun update(newList: MutableList<RemoteSolfa>) {
        list = newList
        notifyDataSetChanged()
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.remote_files_list_item, parent, false))


    override fun getItemCount() = list.size


    override fun onBindViewHolder(holder: ViewHolder, p: Int) {
        holder.apply {
            list[p].also { item ->
                name.apply {
                    text = item.name
                    setOnClickListener { onDownload(list[p].id) }
                }

                dld.apply {
                    text = item.dld.toString()
                    setOnClickListener { onDownload(list[p].id) }
                }
            }
        }
    }


    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.file_name_tv
        val dld: TextView = itemView.download_num
    }
}