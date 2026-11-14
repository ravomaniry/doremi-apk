package mg.maniry.doremii.editor.views

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mg.maniry.doremii.editor.EditorActivity


class ChangesDialog : DialogFragment() {
    private var onDismiss: (() -> Unit) = {}
    private var inflatedView: View? = null
    private var createdDialog: Dialog? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        (context as EditorActivity).uiManager?.changesDialogManager?.run {
            this@ChangesDialog.onDismiss = this.onDismiss
            inflatedView = dialogView
        }
    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreateDialog(savedInstanceState)

        return createdDialog ?: MaterialAlertDialogBuilder(requireActivity())
            .setCancelable(true)
            .setView(inflatedView)
            .create()
            .also { createdDialog = it }
    }


    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onDismiss()
    }
}