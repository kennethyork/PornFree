import Native from '../../modules/blockporna-vpn';
import { dropSalt } from './pin';

/** Removes the PIN. The verified hash of the current PIN must be supplied. */
export async function removePin(currentHash: string): Promise<void> {
  await Native.setPinAsync({ hash: null, currentPinHash: currentHash });
  await dropSalt();
}
