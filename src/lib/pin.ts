import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

const SALT_KEY = 'pornfree.pin.salt';
const PIN_PATTERN = /^\d{4,8}$/;

export function isValidPin(pin: string): boolean {
  return PIN_PATTERN.test(pin);
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

async function salt(): Promise<string> {
  const existing = await SecureStore.getItemAsync(SALT_KEY);
  if (existing) return existing;
  const created = toHex(await Crypto.getRandomBytesAsync(16));
  await SecureStore.setItemAsync(SALT_KEY, created);
  return created;
}

/**
 * The PIN itself never leaves the device and is never stored: only a salted SHA-256 of it is kept,
 * and native code compares the hash before it will do anything that weakens protection.
 */
export async function hashPin(pin: string): Promise<string> {
  const value = await salt();
  return Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, `${value}:${pin}`);
}

export async function dropSalt(): Promise<void> {
  await SecureStore.deleteItemAsync(SALT_KEY);
}
