package expo.modules.blockpornavpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle

/** In-process hand-off of the result of the system VPN consent dialog. */
object VpnConsent {
  private val waiters = mutableListOf<(Boolean) -> Unit>()

  fun await(callback: (Boolean) -> Unit) {
    synchronized(waiters) { waiters.add(callback) }
  }

  fun deliver(granted: Boolean) {
    val pending: List<(Boolean) -> Unit>
    synchronized(waiters) {
      pending = waiters.toList()
      waiters.clear()
    }
    for (callback in pending) callback(granted)
  }
}

/**
 * A headless activity that shows the system "allow VPN connection" dialog and reports the answer.
 *
 * Android only delivers the answer of `VpnService.prepare()` to an activity, so this exists purely
 * to carry it back to the JavaScript promise.
 */
class VpnConsentActivity : Activity() {
  companion object {
    private const val REQUEST_CODE = 7331
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val request = try {
      VpnService.prepare(this)
    } catch (_: Exception) {
      null
    }
    if (request == null) {
      VpnConsent.deliver(true)
      finish()
      return
    }
    try {
      @Suppress("DEPRECATION")
      startActivityForResult(request, REQUEST_CODE)
    } catch (_: Exception) {
      VpnConsent.deliver(false)
      finish()
    }
  }

  @Suppress("OVERRIDE_DEPRECATION")
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE) {
      VpnConsent.deliver(resultCode == RESULT_OK)
      finish()
    }
  }
}
