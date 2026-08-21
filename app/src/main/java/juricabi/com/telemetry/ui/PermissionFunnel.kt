package juricabi.com.telemetry.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat

/**
 * One permission dialog at a time. Android cancels a request fired while
 * another request's dialog is standing — the cancelled ask returns empty
 * and unseen, which is how a fresh install lost its location dialog under
 * the start-of-app storage one, and a first Connect under a pending
 * dialog lost its tap. Every ask funnels through here: one in flight,
 * the next waits for its result, and a repeat of one already waiting is
 * dropped rather than shown twice.
 *
 * The system call is injected so the funnel's queueing — the part that
 * broke — is plain enough to test without a screen.
 */
class PermissionFunnel(
    private val activity: Activity,
    private val showDialog: (AlertDialog) -> Unit,
    private val request: (permission: String, code: Int) -> Unit = { permission, code ->
        ActivityCompat.requestPermissions(activity, arrayOf(permission), code)
    }
) {

    private var askInFlight = -1
    private val asksWaiting = ArrayDeque<Pair<String, Int>>()

    fun ask(permission: String, code: Int) {
        if (askInFlight == code || asksWaiting.any { it.second == code }) return
        if (askInFlight != -1) {
            asksWaiting.addLast(permission to code)
            return
        }
        askInFlight = code
        request(permission, code)
    }

    /** Any result — granted, denied or cancelled — frees the next ask. */
    fun resolved() {
        askInFlight = -1
        val next = asksWaiting.removeFirstOrNull() ?: return
        ask(next.first, next.second)
    }

    /**
     * A refusal, explained — and when the system will no longer even show
     * the dialog, the road to the one place it can still be granted.
     */
    fun explainDenied(message: String, permission: String?) {
        val builder = AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("OK", null)
        if (permission != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            builder.setNeutralButton("Open app settings") { _, _ ->
                activity.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null)
                    )
                )
            }
        }
        showDialog(builder.create())
    }
}
