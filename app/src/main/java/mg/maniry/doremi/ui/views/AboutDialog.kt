package mg.maniry.doremi.ui.views


import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.View
import android.widget.ImageView
import mg.maniry.doremi.R


class AboutDialog : DialogFragment() {
    private var inflatedView: View? = null
    private var createdDialog: Dialog? = null


    override fun onAttach(context: Context?) {
        super.onAttach(context)
        if (inflatedView == null)
            inflatedView = View.inflate(context, R.layout.about_dialog, null).also { v ->
                v.findViewById<ImageView>(R.id.about_close_btn)
                        .setOnClickListener { dismiss() }
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
}
