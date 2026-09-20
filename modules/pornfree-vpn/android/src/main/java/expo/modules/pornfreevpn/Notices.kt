package expo.modules.pornfreevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Notification plumbing shared by the tunnel service and the rescue paths. */
object Notices {
  const val CHANNEL_ID = "pornfree.protection"
  const val ONGOING_ID = 4711
  private const val NOTICE_ID = 4712

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Protection",
      NotificationManager.IMPORTANCE_LOW
    )
    // Deliberately does not name the app: the channel is visible in Android's settings.
    channel.description = "Shows whether filtering is running"
    channel.setShowBadge(false)
    manager.createNotificationChannel(channel)
  }

  /** A one-off notification, used to tell the user the app is visible again. */
  fun post(context: Context, title: String, text: String) {
    try {
      ensureChannel(context)
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, CHANNEL_ID)
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
      }
      builder
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setSmallIcon(
          if (context.applicationInfo.icon != 0) {
            context.applicationInfo.icon
          } else {
            android.R.drawable.stat_sys_warning
          }
        )
      manager.notify(NOTICE_ID, builder.build())
    } catch (_: Exception) {
      // A missing notification must never break the rescue path itself.
    }
  }
}
