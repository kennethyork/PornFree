package expo.modules.pornfreevpn

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Hiding the app from the launcher.
 *
 * This is deliberately *not* uninstall protection and not a lock: Android still lists the app under
 * Settings > Apps, where it can be removed like anything else. What it takes away is the icon - the
 * thing that keeps reminding you the app is there and inviting you to poke at it.
 *
 * A disabled launcher activity cannot be started by anything, including our own notification, so
 * hiding is only ever applied on the way out. [VaultActivity] brings the icon back before it opens
 * the UI, and the app hides itself again when it is next left.
 */
object LauncherVisibility {
  private const val TAG = "PornFreeLauncher"
  private const val KEY_COMPONENT = "launcher_component"
  private const val KEY_HIDE = "hide_launcher"

  /** Dial `*#*#7676#*#*` to bring the icon back if there is no notification to tap. */
  const val SECRET_CODE = "7676"

  @Volatile private var registered = false

  fun hideLauncher(context: Context): Boolean =
    Store.prefs(context).getBoolean(KEY_HIDE, false)

  fun setHideLauncher(context: Context, hide: Boolean) {
    Store.prefs(context).edit().putBoolean(KEY_HIDE, hide).apply()
    rememberComponent(context)
    if (hide) hide(context) else show(context)
  }

  fun isHidden(context: Context): Boolean = try {
    val component = component(context)
    component != null &&
      context.packageManager.getComponentEnabledSetting(component) ==
      PackageManager.COMPONENT_ENABLED_STATE_DISABLED
  } catch (_: Exception) {
    false
  }

  fun hide(context: Context) = applyState(context, enabled = false)

  fun show(context: Context) = applyState(context, enabled = true)

  /** The intent that opens the app, resolved after the icon has been restored. */
  fun launchIntent(context: Context): Intent? {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { return it }
    // The package manager may still report the component as disabled; fall back to the name we
    // stored when the icon was first hidden.
    val stored = component(context) ?: return null
    return Intent.makeMainActivity(stored)
  }

  /**
   * Hides the icon again once no activity of ours is visible, but only when the user asked for
   * hiding. Registered by the module and by the tunnel service so it works whether or not the UI
   * has ever been opened in this process.
   */
  fun registerAutoHide(context: Context) {
    if (registered) return
    val application = context.applicationContext as? Application ?: return
    registered = true
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      private var started = 0

      override fun onActivityStarted(activity: Activity) {
        started++
      }

      override fun onActivityStopped(activity: Activity) {
        started--
        if (started > 0) return
        started = 0
        val appContext = activity.applicationContext
        if (hideLauncher(appContext)) hide(appContext)
      }

      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
  }

  private fun applyState(context: Context, enabled: Boolean) {
    try {
      val component = component(context) ?: return
      context.packageManager.setComponentEnabledSetting(
        component,
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

  private fun component(context: Context): ComponentName? {
    componentFromPrefs(context)?.let { return it }
    rememberComponent(context)
    return componentFromPrefs(context)
  }

  private fun rememberComponent(context: Context) {
    if (componentFromPrefs(context) != null) return
    val resolved = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
      ?: return
    Store.prefs(context).edit().putString(KEY_COMPONENT, resolved.flattenToString()).apply()
  }

  private fun componentFromPrefs(context: Context): ComponentName? =
    Store.prefs(context).getString(KEY_COMPONENT, null)?.let(ComponentName::unflattenFromString)
}
