package expo.modules.pornfreevpn

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Hiding the app's launcher entry.
 *
 * The launcher filter lives on an `<activity-alias>` (see `plugins/withLauncherAlias.js`), and this
 * toggles that alias. MainActivity itself is never disabled - which is the entire point. An app
 * whose launcher activity is disabled cannot be started by anything: not its own notification, not
 * a deep link, not adb. The first version of this feature did exactly that and locked people out of
 * their own app. Here the icon disappears and stays gone, while every way back in keeps working.
 *
 * This is not uninstall protection. Android still lists the app under Settings > Apps, where it can
 * be removed like anything else.
 */
object LauncherVisibility {
  private const val TAG = "PornFreeLauncher"
  private const val KEY_HIDE = "hide_launcher"
  private const val ALIAS_SUFFIX = ".LauncherAlias"
  private const val MAIN_ACTIVITY_SUFFIX = ".MainActivity"

  /** Dial `*#*#7676#*#*` to bring the icon back when there is no notification to tap. */
  const val SECRET_CODE = "7676"

  fun hideLauncher(context: Context): Boolean = Store.prefs(context).getBoolean(KEY_HIDE, false)

  fun setHideLauncher(context: Context, hide: Boolean) {
    Store.prefs(context).edit().putBoolean(KEY_HIDE, hide).apply()
    if (hide) hide(context) else show(context)
  }

  fun hide(context: Context) = applyState(context, enabled = false)

  fun show(context: Context) = applyState(context, enabled = true)

  fun isHidden(context: Context): Boolean = try {
    context.packageManager.getComponentEnabledSetting(alias(context)) ==
      PackageManager.COMPONENT_ENABLED_STATE_DISABLED
  } catch (_: Exception) {
    false
  }

  fun notificationsEnabled(context: Context): Boolean = try {
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false
  } catch (_: Exception) {
    false
  }

  /**
   * An explicit intent to the app's UI.
   *
   * It deliberately does not go through the launcher entry: while the icon is hidden that entry is
   * disabled, and `getLaunchIntentForPackage` would return nothing.
   */
  fun launchIntent(context: Context): Intent? = try {
    val component = ComponentName(context.packageName, mainActivityName(context))
    // Throws when the component is not usable, which is what we want to know.
    context.packageManager.getActivityInfo(component, 0)
    Intent(Intent.ACTION_MAIN)
      .setComponent(component)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  } catch (error: Exception) {
    Log.w(TAG, "No launch intent: ${error.message}")
    null
  }

  /**
   * Whether hiding is safe: the app must still be reachable without its icon. The ongoing
   * notification is how most people get back, so notifications have to be on.
   */
  fun canHide(context: Context): Boolean =
    notificationsEnabled(context) && launchIntent(context) != null

  fun readiness(context: Context): Map<String, Any> = mapOf(
    "hidden" to isHidden(context),
    "hideAfterUse" to hideLauncher(context),
    "secretCode" to SECRET_CODE,
    "canHide" to canHide(context),
    "notificationsEnabled" to notificationsEnabled(context),
    "launchResolvable" to (launchIntent(context) != null)
  )

  private fun alias(context: Context): ComponentName =
    ComponentName(context.packageName, "${context.packageName}$ALIAS_SUFFIX")

  /** The alias itself knows which activity it presents, so nothing has to be hard coded. */
  private fun mainActivityName(context: Context): String = try {
    context.packageManager
      .getActivityInfo(alias(context), PackageManager.MATCH_DISABLED_COMPONENTS)
      .targetActivity
      ?.takeIf { it.isNotEmpty() }
  } catch (_: Exception) {
    null
  } ?: "${context.packageName}$MAIN_ACTIVITY_SUFFIX"

  private fun applyState(context: Context, enabled: Boolean) {
    try {
      context.packageManager.setComponentEnabledSetting(
        alias(context),
        if (enabled) {
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        },
        PackageManager.DONT_KILL_APP
      )
    } catch (error: Exception) {
      Log.w(TAG, "Could not change launcher visibility: ${error.message}")
    }
  }
}
