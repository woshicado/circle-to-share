package eu.woshicado.circletoshare

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * What the user is told on switching an experimental feature on: that it
 * is one, where to report what goes wrong with it (an e-mail to the
 * developer, or an issue on GitHub — both links), and what a report has to
 * say to be of use.
 *
 * It tells, and asks nothing: the feature is on already.
 */
class ExperimentalNoticeDialog : AppCompatDialogFragment() {

    /** The experimental features there are, each with its name in the
     *  settings. Kept by its own name here, not by that text: a notice
     *  that outlives a change of language says its feature in the new one. */
    enum class Feature(@StringRes val title: Int) {
        SCROLLSHOT(R.string.switch_scrollshot)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val feature = getString(Feature.valueOf(requireArguments().getString(ARG_FEATURE)!!).title)
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.experimental_notice_title)
            .setMessage(message(feature))
            .setPositiveButton(R.string.experimental_notice_ok, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // (Without it the links are colored, and dead.)
        dialog?.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    /** The text, with its two links: marked in the string resource as
     *  annotations, made to do something here. */
    private fun message(feature: String): CharSequence {
        val text = SpannableString(
            TextUtils.expandTemplate(
                getText(R.string.experimental_notice_message),
                feature,
                Build.VERSION.RELEASE,
                getString(R.string.feedback_email),
                getString(R.string.url_issues).removePrefix("https://")
            )
        )
        for (link in text.getSpans(0, text.length, Annotation::class.java)) {
            if (link.key != "link") continue
            val open: () -> Unit = when (link.value) {
                "email" -> { -> activity?.composeFeedback(reportDraft(feature)) }
                "issues" -> { -> activity?.openUrl(R.string.url_issues) }
                else -> continue
            }
            text.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = open()
                },
                text.getSpanStart(link), text.getSpanEnd(link), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return text
    }

    /** What a report by e-mail starts out saying: what this device can say
     *  for itself, and room for what only the user knows. */
    private fun reportDraft(feature: String): String = getString(
        R.string.experimental_report_body,
        // (As text: a number would be written in the locale's digits.)
        feature, requireContext().appVersionName(), Build.VERSION.RELEASE, Build.VERSION.SDK_INT.toString(),
        "${Build.MANUFACTURER} ${Build.MODEL}"
    )

    companion object {
        private const val ARG_FEATURE = "feature"

        /**
         * Show it for [feature], unless the one for that feature is up
         * already. Up at once, so that a
         * second call finds the first. False if it cannot be shown: the
         * screen is on its way out.
         */
        fun show(fragments: FragmentManager, feature: Feature): Boolean {
            if (fragments.isStateSaved) return false
            val tag = "experimental_notice:${feature.name}"
            if (fragments.findFragmentByTag(tag) != null) return true
            ExperimentalNoticeDialog().apply {
                arguments = Bundle().apply { putString(ARG_FEATURE, feature.name) }
            }.showNow(fragments, tag)
            return true
        }
    }
}
