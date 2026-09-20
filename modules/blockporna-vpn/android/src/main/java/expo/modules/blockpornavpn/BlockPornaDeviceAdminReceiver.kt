package expo.modules.blockpornavpn

import android.app.admin.DeviceAdminReceiver

/**
 * Present only so that the device owner / profile owner path is available.
 *
 * A plain "active device admin" can no longer stop the user from uninstalling an app - Android
 * removed that in Android 7 - so uninstall protection genuinely requires device owner privilege.
 */
class BlockPornaDeviceAdminReceiver : DeviceAdminReceiver()
