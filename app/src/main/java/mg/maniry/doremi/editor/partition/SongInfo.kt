package mg.maniry.doremi.editor.partition

import android.arch.lifecycle.MutableLiveData


data class SongInfo(val title: MutableLiveData<String> = MutableLiveData(),
                    val author: MutableLiveData<String> = MutableLiveData(),
                    val compositor: MutableLiveData<String> = MutableLiveData(),
                    val releaseDate: MutableLiveData<String> = MutableLiveData(),
                    val singer: MutableLiveData<String> = MutableLiveData()) {

    var filename: String = ""
        get() = "${title.value}${if (singer.value != null && singer.value != "") " - ${singer.value}" else ""}"
                .replace("/", "_")


    override fun toString() = "title=${title.value}, aut=${author.value} comp=${compositor.value}," +
            "date=${releaseDate.value}, singer=${singer.value} "


    fun parseFile(keyValues: List<KeyValue>) {
        reset()

        keyValues.forEach {
            with(it) {
                when (mKey) {
                    Labels.TITLE -> title.value = mValue
                    Labels.AUTHOR -> author.value = mValue
                    Labels.COMP -> compositor.value = mValue
                    Labels.DATE -> releaseDate.value = mValue
                    Labels.SINGER -> singer.value = mValue
                }
            }
        }
    }


    private fun reset() {
        title.value = ""
        author.value = ""
        compositor.value = ""
        releaseDate.value = ""
        singer.value = ""
    }


    fun update(index: String, value: String) {
        if (value != "") {
            with(Labels) {
                when (index) {
                    AUTHOR -> author.value = value
                    COMP -> compositor.value = value
                    DATE -> releaseDate.value = value
                    SINGER -> singer.value = value
                    TITLE -> title.value = value
                }
            }
        }
    }
}