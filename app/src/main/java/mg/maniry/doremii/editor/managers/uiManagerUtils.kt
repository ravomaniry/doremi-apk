package mg.maniry.doremii.editor.managers


import android.content.Context
import android.view.View
import android.widget.*
import mg.maniry.doremii.R


data class EditorViews(
    val mainCont: LinearLayout,
    val keyboardCont: LinearLayout,
    val viewerCont: LinearLayout,
    val viewer: ScrollView,
    val btn: ImageView
)


data class InstrConfig(val context: Context) {
    val cont: LinearLayout = View.inflate(context, R.layout.instru_line, null) as LinearLayout
    val voiceIdSpinner: Spinner = cont.findViewById(R.id.voice_id)
    val muteCheckbox: CheckBox = cont.findViewById(R.id.instru_cbx)
}
