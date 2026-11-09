package eu.woshicado.circletoshare

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Hand a web page to whatever browser the user has. The app itself has no
 * internet permission and loads nothing: the link leaves as an intent.
 */
internal fun Activity.openUrl(@StringRes urlRes: Int) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlRes))))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.qr_open_failed, Toast.LENGTH_SHORT).show()
    }
}

/** This app's version, as its users know it. */
internal fun Context.appVersionName(): String? =
    packageManager.getPackageInfo(packageName, 0).versionName

/**
 * Start an email to the developer in the user's mail app, with the subject
 * filled in (app name and version, so a report says what it is about).
 * Nothing is sent from here: the draft is the mail app's, to send or drop.
 * [body], if any, is what the draft starts out saying.
 */
internal fun Activity.composeFeedback(body: String? = null) {
    val subject = getString(R.string.feedback_subject, appVersionName())
    // ACTION_SENDTO with a mailto: URI reaches mail apps only; ACTION_SEND
    // would offer every app that takes text.
    val uri = Uri.parse(
        "mailto:" + getString(R.string.feedback_email) + "?subject=" + Uri.encode(subject) +
            // (Line breaks in a mailto: body are CR LF, by RFC 6068.)
            (body?.let { "&body=" + Uri.encode(it.replace("\n", "\r\n")) } ?: "")
    )
    try {
        startActivity(Intent(Intent.ACTION_SENDTO, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.qr_open_failed, Toast.LENGTH_SHORT).show()
    }
}
