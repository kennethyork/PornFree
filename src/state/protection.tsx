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
import { PermissionsAndroid, Platform } from 'react-native';

import Native from '../../modules/blockporna-vpn';
import type {
  BlocklistInfo,
  BlockedDomainEvent,
  ProtectionConfig,
  ProtectionStatus,
} from '../../modules/blockporna-vpn';
import { pruneHistory, recordBlocked } from '../lib/history';

type ProtectionValue = {
  ready: boolean;
  status: ProtectionStatus | null;
  config: ProtectionConfig | null;
  lists: BlocklistInfo[];
  error: string | null;
  busy: boolean;
  refresh: () => Promise<void>;
  refreshLists: () => Promise<void>;
  start: () => Promise<void>;
  stop: (pinHash: string | null) => Promise<void>;
  saveConfig: (patch: Partial<ProtectionConfig>, pinHash: string | null) => Promise<void>;
  installList: (id: string) => Promise<void>;
  updateList: (id: string, url: string, title: string) => Promise<void>;
  removeList: (id: string) => Promise<void>;
  requestPermission: () => Promise<boolean>;
  dismissError: () => void;
};

const ProtectionContext = createContext<ProtectionValue | null>(null);

/**
 * Android 13+ hides the ongoing notification (and with it the reminder that protection is on)
 * unless this permission is granted. Refusing it does not stop filtering.
 */
async function ensureNotificationPermission(): Promise<void> {
  if (Platform.OS !== 'android' || Number(Platform.Version) < 33) return;
  try {
    const already = await PermissionsAndroid.check(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
    if (!already) {
      await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    }
  } catch {
    // Never block starting protection on a permission prompt.
  }
}

function messageFor(failure: unknown): string {
  if (failure && typeof failure === 'object' && 'message' in failure) {
    return String((failure as { message?: unknown }).message ?? 'Something went wrong.');
  }
  return 'Something went wrong.';
}

export function ProtectionProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [status, setStatus] = useState<ProtectionStatus | null>(null);
  const [config, setConfig] = useState<ProtectionConfig | null>(null);
  const [lists, setLists] = useState<BlocklistInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const autostartTried = useRef(false);
  const configRef = useRef<ProtectionConfig | null>(null);
  const lastPrune = useRef(0);

  configRef.current = config;

  const refreshLists = useCallback(async () => {
    const loaded = await Native.getListsAsync();
    setLists(loaded);
  }, []);

  const refresh = useCallback(async () => {
    const [nextStatus, nextConfig] = await Promise.all([
      Native.getStatusAsync(),
      Native.getConfigAsync(),
    ]);
    setStatus(nextStatus);
    setConfig(nextConfig);
    return;
  }, []);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        // Seeds the lists that ship with the app on first launch.
        await Native.ensureListsAsync();
        await refreshLists();
        await refresh();
        if (!cancelled) setReady(true);

        const current = await Native.getStatusAsync();
        if (
          !cancelled &&
          !autostartTried.current &&
          current.shouldRun &&
          !current.running &&
          current.permissionGranted
        ) {
          autostartTried.current = true;
          const stored = await Native.getConfigAsync();
          await Native.startAsync(stored);
          await refresh();
        }
      } catch (failure) {
        if (!cancelled) {
          setError(messageFor(failure));
          setReady(true);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [refresh, refreshLists]);

  useEffect(() => {
    const statsSubscription = Native.addListener('onStats', (next: ProtectionStatus) => {
      setStatus(next);
    });
    const stateSubscription = Native.addListener('onStateChange', () => {
      void refresh();
    });
    const blockedSubscription = Native.addListener('onBlocked', (event: BlockedDomainEvent) => {
      void recordBlocked(event.domains).catch(() => undefined);
      if (Date.now() - lastPrune.current > 3_600_000) {
        lastPrune.current = Date.now();
        void pruneHistory().catch(() => undefined);
      }
    });

    return () => {
      statsSubscription.remove();
      stateSubscription.remove();
      blockedSubscription.remove();
    };
  }, [refresh]);

  const run = useCallback(async <T,>(action: () => Promise<T>): Promise<T | null> => {
    setBusy(true);
    setError(null);
    try {
      return await action();
    } catch (failure) {
      setError(messageFor(failure));
      return null;
    } finally {
      setBusy(false);
    }
  }, []);

  const requestPermission = useCallback(async () => {
    const granted = await run(() => Native.requestPermissionAsync());
    await refresh();
    return granted === true;
  }, [refresh, run]);

  const start = useCallback(async () => {
    await ensureNotificationPermission();
    const current = configRef.current ?? (await Native.getConfigAsync());
    const granted = (await Native.getStatusAsync()).permissionGranted
      ? true
      : await requestPermission();
    if (!granted) return;
    await run(() => Native.startAsync(current));
    await refresh();
  }, [refresh, requestPermission, run]);

  const stop = useCallback(
    async (pinHash: string | null) => {
      await run(() => Native.stopAsync({ pinHash }));
      await refresh();
    },
    [refresh, run]
  );

  const saveConfig = useCallback(
    async (patch: Partial<ProtectionConfig>, pinHash: string | null) => {
      const base = configRef.current ?? (await Native.getConfigAsync());
      const next: ProtectionConfig = { ...base, ...patch, pinHash };
      const nextStatus = await run(() => Native.configureAsync(next));
      if (nextStatus) {
        setConfig({ ...next, pinHash: undefined });
        await refreshLists();
        await refresh();
      }
    },
    [refresh, refreshLists, run]
  );

  const installList = useCallback(
    async (id: string) => {
      await run(() => Native.installBundledListAsync(id));
      await refreshLists();
    },
    [refreshLists, run]
  );

  const updateList = useCallback(
    async (id: string, url: string, title: string) => {
      await run(() => Native.downloadListAsync(id, url, title));
      await refreshLists();
    },
    [refreshLists, run]
  );

  const removeList = useCallback(
    async (id: string) => {
      await run(() => Native.deleteListAsync(id));
      await refreshLists();
      await refresh();
    },
    [refresh, refreshLists, run]
  );

  const value = useMemo<ProtectionValue>(
    () => ({
      ready,
      status,
      config,
      lists,
      error,
      busy,
      refresh,
      refreshLists,
      start,
      stop,
      saveConfig,
      installList,
      updateList,
      removeList,
      requestPermission,
      dismissError: () => setError(null),
    }),
    [
      busy,
      config,
      error,
      installList,
      lists,
      ready,
      refresh,
      refreshLists,
      removeList,
      requestPermission,
      saveConfig,
      start,
      status,
      stop,
      updateList,
    ]
  );

  return <ProtectionContext.Provider value={value}>{children}</ProtectionContext.Provider>;
}

export function useProtection(): ProtectionValue {
  const value = useContext(ProtectionContext);
  if (!value) throw new Error('useProtection must be used inside ProtectionProvider');
  return value;
}
