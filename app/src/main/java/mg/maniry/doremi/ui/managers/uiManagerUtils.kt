package mg.maniry.doremi.ui.managers

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*


data class EditorViews(
        val mainCont: LinearLayout,
        val keyboardCont: LinearLayout,
        val viewerCont: LinearLayout,
        val viewer: ScrollView,
        val btn: ImageView
)


class ActionTypes {
    companion object {
        const val OPEN = 0
        const val SHARE = 1
        const val DELETE = 2
    }
}


fun EditText.onChange(callback: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(p0: Editable?) {
            callback(p0.toString())
        }

        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
    })
}


fun View.hideKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
}


fun Spinner.onChange(callback: (Int) -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(p0: AdapterView<*>?) {}

        override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
            callback(p2)
        }
    }
}