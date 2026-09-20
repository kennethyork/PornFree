import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = 'pornfree.onboarded';

/**
 * Whether the first-run setup was completed.
 *
 * The PIN itself decides whether the app is usable at all; this flag only remembers that the user
 * has been through the wizard, so that a later PIN change does not walk them through it again.
 */
export async function isOnboarded(): Promise<boolean> {
  try {
    return (await AsyncStorage.getItem(KEY)) === 'true';
  } catch {
    return false;
  }
}

export async function setOnboarded(value: boolean): Promise<void> {
  try {
    if (value) {
      await AsyncStorage.setItem(KEY, 'true');
    } else {
      await AsyncStorage.removeItem(KEY);
    }
  } catch {
    // Losing this flag only means the wizard shows once more.
  }
}
