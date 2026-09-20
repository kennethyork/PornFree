package expo.modules.pornfreevpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The entry point used while the launcher icon is hidden: the ongoing notification and
 * `pornfree://open` both land here.
 *
 * It opens the app's UI directly rather than through the launcher entry, because that entry is the
 * thing that is switched off. If the hand-over fails it stays on screen with a retry button instead
 * of disappearing silently, since a silent failure here means staring at a phone with no way into
 * the app.
 */
class VaultActivity : Activity() {
  companion object {
    private const val TAG = "PornFreeVault"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    openOrExplain()
  }

  /** The name shown on the device, so a discreet label needs no code change here. */
  private fun appName(): String = try {
    applicationInfo.loadLabel(packageManager).toString()
  } catch (_: Exception) {
    "The app"
  }

  private fun openOrExplain() {
    val launch = LauncherVisibility.launchIntent(this)
    if (launch != null) {
      // Keep any deep link the user followed.
      intent?.data?.let { launch.data = it }
      try {
        startActivity(launch)
        finish()
        return
      } catch (error: Exception) {
        Log.w(TAG, "Could not open the app: ${error.message}")
      }
    }
    showFallback()
  }

  /** Shown only when opening the app failed. Deliberately plain, and built in code. */
  private fun showFallback() {
    val padding = (resources.displayMetrics.density * 24).toInt()

    val title = TextView(this).apply {
      text = "${appName()} is hidden"
      setTextColor(Color.parseColor("#F8FAFC"))
      textSize = 20f
      setTypeface(typeface, Typeface.BOLD)
    }

    val body = TextView(this).apply {
      text = "Its icon is hidden and the app could not be opened automatically just now. " +
        "Tap below to try again. Settings → Visibility inside the app turns hiding off, and " +
        "uninstalling from Settings → Apps always works."
      setTextColor(Color.parseColor("#94A3B8"))
      textSize = 14f
      setPadding(0, padding / 2, 0, padding)
    }

    val button = Button(this).apply {
      text = "Open ${appName()}"
      setOnClickListener { openOrExplain() }
    }

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setBackgroundColor(Color.parseColor("#070B14"))
      setPadding(padding, padding, padding, padding)
      addView(title)
      addView(body)
      addView(button)
    }
    setContentView(layout)
  }
}
