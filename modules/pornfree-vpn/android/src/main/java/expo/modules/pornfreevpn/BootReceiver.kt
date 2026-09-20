package expo.modules.pornfreevpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/** Brings protection back after a reboot when the user asked for it to stay on. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    if (!Store.shouldRun(context)) return
    if (!Store.loadConfig(context).autoRestart) return
    if (VpnService.prepare(context) != null) {
      // The user revoked the VPN permission; nothing we are allowed to do here.
      Store.setShouldRun(context, false)
      return
    }
    try {
      PornFreeVpnService.start(context)
    } catch (error: Exception) {
      Log.w(PornFreeVpnService.TAG, "Could not restart protection: ${error.message}")
    }
  }
}
