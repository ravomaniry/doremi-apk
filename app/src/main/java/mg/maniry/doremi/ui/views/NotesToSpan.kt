package mg.maniry.doremi.ui.views

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan


class NotesToSpan {
    companion object {
        private const val TAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val symbols = listOf('-', '1', '2', '3')


        private data class Span(val isSup: Boolean, val position: Int) {
            fun assign(builder: SpannableStringBuilder) {
                if (isSup)
                    sup(builder, position)
                else
                    sub(builder, position)
            }
        }


        fun convert(notes: String): SpannableStringBuilder {
            return if (notes.length <= 1) {
                SpannableStringBuilder(notes)

            } else {
                var str = ""
                var index = 0
                var skipNext = false
                val spans = mutableListOf<Span>()

                for (i in 0..(notes.length - 1)) {
                    if (!skipNext) {
                        if (symbols.contains(notes[i])) {
                            if (notes[i] == '-') {
                                if (i < notes.length - 1 && symbols.contains(notes[i + 1])) {
                                    str += notes[i + 1]
                                    spans.add(Span(false, index))
                                    skipNext = true
                                    index++
                                } else {
                                    str += notes[i]
                                    index++
                                }

                            } else {
                                str += notes[i]
                                spans.add(Span(true, index))
                                index++
                            }

                        } else {
                            str += notes[i]
                            index++
                        }

                    } else {
                        skipNext = false
                    }
                }


                SpannableStringBuilder(str).apply {
                    if (spans.size > 0) {
                        spans.forEach {
                            it.assign(this)
                        }
                    }
                }
            }
        }


        private fun sub(builder: SpannableStringBuilder, position: Int) {
            builder.apply {
                setSpan(SubscriptSpan(), position, position + 1, TAG)
                setSpan(RelativeSizeSpan(0.7f), position, position + 1, TAG)
            }
        }


        private fun sup(builder: SpannableStringBuilder, position: Int) {
            builder.apply {
                setSpan(SuperscriptSpan(), position, position + 1, TAG)
                setSpan(RelativeSizeSpan(0.6f), position, position + 1, TAG)
            }
        }
    }
}