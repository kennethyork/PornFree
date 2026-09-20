package expo.modules.pornfreevpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The way back in when the app is hidden from the launcher.
 *
 * The ongoing notification points here rather than straight at the UI, because a disabled launcher
 * activity cannot be started by anyone - not even by us. Restoring the icon first also means the app
 * stays reachable if the notification is dismissed.
 */
class VaultActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      LauncherVisibility.show(this)
      val launch = LauncherVisibility.launchIntent(this)
      if (launch == null) {
        Notices.post(
          this,
          "PornFree is visible again",
          "Open it from your app list. Its icon was hidden, so it could not be started automatically."
        )
      } else {
        // Keep any deep link the user followed.
        intent?.data?.let { launch.data = it }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
      }
    } catch (error: Exception) {
      Log.w("PornFreeVault", "Could not open the app: ${error.message}")
    }
    finish()
  }
}
