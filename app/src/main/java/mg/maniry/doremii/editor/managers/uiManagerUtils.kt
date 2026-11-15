package mg.maniry.doremii.editor.managers


import android.content.Context
import android.view.View
import android.widget.*
import com.google.android.material.button.MaterialButton
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
    val muteButton: MaterialButton = cont.findViewById(R.id.voice_mute)
    val soloButton: MaterialButton = cont.findViewById(R.id.voice_solo)
    val timbreButton: MaterialButton = cont.findViewById(R.id.timbre_button)
}
