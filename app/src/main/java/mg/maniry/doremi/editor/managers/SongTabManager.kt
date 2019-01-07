package mg.maniry.doremi.editor.managers

import android.arch.lifecycle.Observer
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import mg.maniry.doremi.R.id.*
import mg.maniry.doremi.editor.EditorActivity
import mg.maniry.doremi.editor.viewModels.EditorViewModel
import mg.maniry.doremi.editor.partition.Labels


class SongTabManager(
        private val mainContext: Context,
        private val mainView: View,
        private val songTab: View,
        private val editorViewModel: EditorViewModel) {


    init {
        initSongInfoUpdater()
        initSongTitleTextView()
    }


    private fun initSongInfoUpdater() {
        val targetValues = with(editorViewModel.partitionData.songInfo) { listOf(title, author, compositor, releaseDate, singer) }
        val fieldIds = with(Labels) { listOf(TITLE, AUTHOR, COMP, DATE, SINGER) }

        listOf(song_infos_titile, song_infos_aut, song_infos_comp, song_infos_release_date, song_infos_singer).forEachIndexed { i, id ->
            songTab.findViewById<EditText>(id).apply {
                targetValues[i].observe(mainContext as EditorActivity, Observer {
                    if (it != text.toString())
                        setText(it ?: "")
                })

                setOnFocusChangeListener { view, focused ->
                    if (!focused)
                        editorViewModel.updateSongInfo(fieldIds[i], (view as EditText).text.toString())
                }
            }
        }
    }


    private fun initSongTitleTextView() {
        editorViewModel.partitionData.songInfo.title.observe(mainContext as EditorActivity, Observer {
            it?.run { mainView.findViewById<TextView>(song_title_text_view).text = it }
        })
    }
}