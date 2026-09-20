import * as Application from 'expo-application';

/**
 * The name this app shows on the device: the launcher label, the notification header, and its entry
 * under Settings → Apps.
 *
 * It is read from the app config rather than written into strings, so making the app discreet is a
 * one-line change in `app.json` (expo.name) instead of a hunt through the UI. Nothing user-visible
 * should hardcode the project's name.
 */
export const APP_NAME = Application.applicationName ?? 'PornFree';
