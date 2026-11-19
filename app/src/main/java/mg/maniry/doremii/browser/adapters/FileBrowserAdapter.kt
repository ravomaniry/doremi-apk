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
import mg.maniry.doremii.commonUtils.SongFile
import java.text.DateFormat
import java.util.Date
import java.util.Locale


class FileBrowserAdapter(
    private var songs: List<SongFile>,
    private val untitledLabel: String,
    private val songsLabel: String,
    private val onClick: (type: Int, value: String) -> Unit,
    private val onVisibleCount: (Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var filterValue = ""
    private var rows: List<LibraryRow> = emptyList()
    private var activeFile = ""
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dateFormat =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())


    override fun getItemViewType(position: Int) = when (rows[position]) {
        is LibraryRow.Header -> HEADER
        is LibraryRow.Song -> SONG
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HEADER) {
            HeaderHolder(inflater.inflate(R.layout.library_section_header, parent, false))
        } else {
            SongHolder(inflater.inflate(R.layout.files_list_item, parent, false))
        }
    }


    override fun getItemCount() = rows.size


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LibraryRow.Header -> (holder as HeaderHolder).title.text = row.title
            is LibraryRow.Song -> bindSong(holder as SongHolder, row.file)
        }
    }


    private fun bindSong(holder: SongHolder, song: SongFile) {
        holder.name.text = if (song.untitled) untitledLabel else song.title
        holder.subtitle.text = song.singer.ifEmpty { dateFormat.format(Date(song.modifiedAt)) }
        holder.itemView.setOnClickListener { onClick(ActionTypes.OPEN, song.name) }
        holder.actions.setOnClickListener {
            activeFile = song.name
            showMenu(it)
        }
    }


    fun update(newList: List<SongFile>) {
        songs = newList
        search()
    }


    fun deleteFile(name: String) {
        update(songs.filter { it.name != name })
    }


    fun search(str: String = filterValue) {
        filterValue = str

        if (str == "") {
            publish(songs)
        } else {
            searchScope.launch {
                val tmpFilterValue = filterValue
                val filtered = withContext(Dispatchers.Default) {
                    songs.filter(::searchCallback)
                }
                if (tmpFilterValue == filterValue) publish(filtered)
            }
        }
    }


    private fun publish(files: List<SongFile>) {
        val untitled = files.filter { it.untitled }.sortedByDescending { it.modifiedAt }
        val named = files.filter { !it.untitled }.sortedByDescending { it.modifiedAt }
        val next = mutableListOf<LibraryRow>()
        if (untitled.isNotEmpty()) {
            next += LibraryRow.Header(untitledLabel)
            next += untitled.map { LibraryRow.Song(it) }
        }
        if (named.isNotEmpty()) {
            if (untitled.isNotEmpty()) next += LibraryRow.Header(songsLabel)
            next += named.map { LibraryRow.Song(it) }
        }
        rows = next
        notifyDataSetChanged()
        onVisibleCount(untitled.size + named.size)
    }


    private fun searchCallback(song: SongFile): Boolean {
        val parts = filterValue.lowercase(Locale.getDefault()).split(" ")
        val filename = song.name.trim().lowercase(Locale.getDefault())
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


    private class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.library_section_title)
    }


    private class SongHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.list_item_name)
        val subtitle: TextView = itemView.findViewById(R.id.list_item_subtitle)
        val actions: ImageView = itemView.findViewById(R.id.list_item_share_btn)
    }


    private sealed class LibraryRow {
        data class Header(val title: String) : LibraryRow()
        data class Song(val file: SongFile) : LibraryRow()
    }


    companion object {
        private const val HEADER = 0
        private const val SONG = 1
    }
}
