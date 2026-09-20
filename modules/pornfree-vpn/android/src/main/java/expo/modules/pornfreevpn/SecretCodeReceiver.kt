package expo.modules.pornfreevpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * `*#*#7676#*#*` from the dialer.
 *
 * The fallback for a hidden app when notifications are switched off for it: the icon comes back and
 * a notification says so, rather than the app trying to launch itself from the background (which
 * Android has blocked since version 10).
 */
class SecretCodeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    LauncherVisibility.setHideLauncher(context, false)
    // No app name: this notification can appear on a locked screen.
    Notices.post(
      context,
      "App icon restored",
      "Open it from your app list. Turn hiding back on in Settings when you are done."
    )
  }
}
