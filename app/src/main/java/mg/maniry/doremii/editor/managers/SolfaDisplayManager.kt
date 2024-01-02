package mg.maniry.doremii.editor.managers

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.google.android.flexbox.FlexboxLayout
import mg.maniry.doremii.R.id.add_measure_btn
import mg.maniry.doremii.R.id.partition_cont
import mg.maniry.doremii.editor.EditorActivity
import mg.maniry.doremii.editor.viewModels.EditorViewModel


class SolfaDisplayManager(
    mainContext: Context, mainView: View, private val editorVM: EditorViewModel
) {
    private val viewsCont = mainView.findViewById<FlexboxLayout>(partition_cont)
    private val react = SolfaDisplayManagerReactive(editorVM, viewsCont)

    init {
        editorVM.partitionData.signature.observe(mainContext as EditorActivity) { react.reRender() }
        editorVM.reRenderNotifier.observe(mainContext) { react.reRender() }
        viewsCont.findViewById<ImageView>(add_measure_btn).setOnClickListener {
            editorVM.addMeasure()
        }
    }
}
