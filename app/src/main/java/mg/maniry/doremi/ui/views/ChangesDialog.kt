package mg.maniry.doremi.ui.views

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.View
import mg.maniry.doremi.ui.MainActivity


class ChangesDialog : DialogFragment() {
    private var onDismiss: (() -> Unit) = {}
    private var inflatedView: View? = null
    private var createdDialog: Dialog? = null

    override fun onAttach(context: Context?) {
        super.onAttach(context)

        (context as MainActivity).uiManager?.changesDialogManager?.run {
            this@ChangesDialog.onDismiss = this.onDismiss
            inflatedView = dialogView
        }
    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreateDialog(savedInstanceState)

        return createdDialog ?: AlertDialog.Builder(activity)
                .apply {
                    setCancelable(true)
                    setView(inflatedView)
                }
                .create()
                .also { createdDialog = it }
    }


    override fun onCancel(dialog: DialogInterface?) {
        super.onCancel(dialog)
        onDismiss()
    }
}