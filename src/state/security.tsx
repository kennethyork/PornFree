import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

import Native from '../../modules/blockporna-vpn';
import { PinSheet } from '../components/pin-sheet';
import { hashPin, isValidPin } from '../lib/pin';

type Mode = 'verify' | 'create';

type PendingRequest = {
  mode: Mode;
  reason?: string;
  resolve: (hash: string | null) => void;
};

type SecurityValue = {
  ready: boolean;
  hasPin: boolean;
  /** Prompts for the PIN when one is set and returns its verified hash, otherwise null. */
  acquirePin: (reason?: string, mode?: Mode) => Promise<string | null>;
  refresh: () => Promise<void>;
  forget: () => void;
  invalidate: () => void;
};

const SecurityContext = createContext<SecurityValue | null>(null);

/** How long a successful unlock is trusted while the app stays open. */
const UNLOCK_TTL_MS = 5 * 60 * 1000;

export function SecurityProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [hasPin, setHasPin] = useState(false);
  const [pending, setPending] = useState<PendingRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cache = useRef<{ hash: string; until: number } | null>(null);
  const hasPinRef = useRef(false);

  const remember = useCallback((hash: string) => {
    cache.current = { hash, until: Date.now() + UNLOCK_TTL_MS };
  }, []);

  const refresh = useCallback(async () => {
    try {
      const status = await Native.getStatusAsync();
      hasPinRef.current = status.hasPin;
      setHasPin(status.hasPin);
    } catch {
      // Leave the last known value in place.
    }
  }, []);

  useEffect(() => {
    void refresh().finally(() => setReady(true));
  }, [refresh]);

  const forget = useCallback(() => {
    cache.current = null;
  }, []);

  const invalidate = useCallback(() => {
    hasPinRef.current = true;
    setHasPin(true);
  }, []);

  const acquirePin = useCallback(
    async (reason?: string, mode: Mode = 'verify'): Promise<string | null> => {
      if (mode === 'verify') {
        const fresh = cache.current;
        if (fresh && fresh.until > Date.now()) return fresh.hash;
        // Nothing to ask for when no PIN has ever been set.
        if (!hasPinRef.current) return null;
      }
      return new Promise<string | null>((resolve) => {
        setError(null);
        setPending({ mode, reason, resolve });
      });
    },
    []
  );

  const close = useCallback(
    (result: string | null) => {
      pending?.resolve(result);
      setPending(null);
      setBusy(false);
      setError(null);
    },
    [pending]
  );

  const submit = useCallback(
    async (pin: string) => {
      if (!pending) return;
      setBusy(true);
      setError(null);
      try {
        if (pending.mode === 'create') {
          if (!isValidPin(pin)) {
            setError('Use 4 to 8 digits.');
            setBusy(false);
            return;
          }
          const hash = await hashPin(pin);
          // Replacing an existing PIN is only allowed with the old one, which the caller
          // verified a moment ago and which is still cached.
          await Native.setPinAsync({ hash, currentPinHash: cache.current?.hash ?? null });
          remember(hash);
          hasPinRef.current = true;
          setHasPin(true);
          close(hash);
          return;
        }

        const hash = await hashPin(pin);
        const ok = await Native.verifyPinAsync(hash);
        if (!ok) {
          setError('Wrong PIN.');
          setBusy(false);
          return;
        }
        remember(hash);
        close(hash);
      } catch (failure) {
        setError(failure instanceof Error ? failure.message : 'Something went wrong.');
        setBusy(false);
      }
    },
    [close, pending, remember]
  );

  const value = useMemo<SecurityValue>(
    () => ({ ready, hasPin, acquirePin, refresh, forget, invalidate }),
    [acquirePin, forget, hasPin, invalidate, ready, refresh]
  );

  return (
    <SecurityContext.Provider value={value}>
      {children}
      <PinSheet
        visible={pending !== null}
        mode={pending?.mode ?? 'verify'}
        reason={pending?.reason}
        busy={busy}
        error={error}
        onSubmit={submit}
        onCancel={() => close(null)}
      />
    </SecurityContext.Provider>
  );
}

export function useSecurity(): SecurityValue {
  const value = useContext(SecurityContext);
  if (!value) throw new Error('useSecurity must be used inside SecurityProvider');
  return value;
}
