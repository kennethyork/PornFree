import { PermissionsAndroid, Platform } from 'react-native';

/**
 * Asks for the notification permission Android 13+ needs before an app may show one.
 *
 * This matters more here than in most apps: the ongoing Protection notification is the documented
 * way back into the app once its launcher icon is hidden, so hiding is only allowed when it can
 * actually be shown. Native code re-checks with NotificationManager and refuses otherwise.
 */
export async function ensureNotificationPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  if (Number(Platform.Version) < 33) return true;
  try {
    const granted = await PermissionsAndroid.check(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
    if (granted) return true;
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

/** True when the app can still reach the user through a notification. */
export async function notificationsAllowed(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  if (Number(Platform.Version) < 33) return true;
  try {
    return await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  } catch {
    return false;
  }
}
