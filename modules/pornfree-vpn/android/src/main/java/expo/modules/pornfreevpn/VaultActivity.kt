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
 * The way back in when the app is hidden from the launcher.
 *
 * A disabled launcher activity cannot be started by anyone, not even by us, so this exists to
 * restore the icon and then hand over to the UI. It normally does that and disappears before you
 * see it; if the hand-over fails it stays put and offers a button, because a silent failure here
 * means being locked out of your own app.
 */
class VaultActivity : Activity() {
  companion object {
    private const val TAG = "PornFreeVault"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    LauncherVisibility.show(this)
    openOrExplain()
  }

  private fun openOrExplain() {
    val launch = LauncherVisibility.launchIntent(this)
    if (launch != null) {
      // Keep any deep link the user followed.
      intent?.data?.let { launch.data = it }
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        startActivity(launch)
        finish()
        return
      } catch (error: Exception) {
        Log.w(TAG, "Could not open the app: ${error.message}")
      }
    } else {
      Log.w(TAG, "No launch intent could be resolved")
    }
    showFallback()
  }

  /** Shown only when opening the app failed. Deliberately plain, and built in code. */
  private fun showFallback() {
    val padding = (resources.displayMetrics.density * 24).toInt()

    val title = TextView(this).apply {
      text = "PornFree is hidden"
      setTextColor(Color.parseColor("#F8FAFC"))
      textSize = 20f
      setTypeface(typeface, Typeface.BOLD)
    }

    val body = TextView(this).apply {
      text = "Its icon was hidden, and it could not be opened automatically just now. " +
        "Tap below to try again. If this keeps happening, uninstalling the app from " +
        "Settings → Apps restores everything."
      setTextColor(Color.parseColor("#94A3B8"))
      textSize = 14f
      setPadding(0, padding / 2, 0, padding)
    }

    val button = Button(this).apply {
      text = "Open PornFree"
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
