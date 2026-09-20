export type BlockMode = 'nodata' | 'null' | 'nxdomain';

export type Screen = string; // reserved for future per-app rules

/** Everything the native tunnel needs in order to run. */
export type ProtectionConfig = {
  /** Verified PIN hash. Required by native code once a PIN exists. */
  pinHash?: string | null;
  /** Resolvers queries are relayed to. The first reachable one wins. */
  upstreams: string[];
  /** Ids of the blocklists that should be applied. */
  listIds: string[];
  /** Domains that are never blocked. */
  allowlist: string[];
  /** How a blocked name is answered. */
  blockMode: BlockMode;
  /** Also route (and therefore break) known DNS-over-HTTPS / DNS-over-TLS endpoints. */
  interceptEncryptedDns: boolean;
  /** Handle IPv6 DNS as well. Turning this off leaves IPv6 lookups unfiltered. */
  includeIpv6: boolean;
  /** Bring protection back automatically after a reboot. */
  autoRestart: boolean;
  /** Keep a rolling buffer of recently blocked domains to hand to JavaScript. */
  logDomains: boolean;
};

export type ProtectionStatus = {
  running: boolean;
  permissionGranted: boolean;
  /** Number of domains in the lists that are currently loaded. */
  listSize: number;
  startedAt: number;
  blockedSession: number;
  allowedSession: number;
  cachedSession: number;
  /** DNS packets dropped because they were not plain DNS. */
  droppedSession: number;
  blockedToday: number;
  allowedToday: number;
  totalBlocked: number;
  /** Epoch millis until which protection cannot be switched off (0 = not locked). */
  commitmentUntil: number;
  hasPin: boolean;
  deviceOwner: boolean;
  installedAt: number;
  /** True when the user wants protection on, even if the tunnel is momentarily down. */
  shouldRun: boolean;
  /** True while the app has no launcher icon. */
  launcherHidden: boolean;
};

export type BlocklistInfo = {
  id: string;
  title: string;
  description: string;
  sourceUrl: string;
  bundled: boolean;
  installed: boolean;
  enabled: boolean;
  domains: number;
  updatedAt: number;
};

export type DailyStats = {
  date: string;
  blocked: number;
  allowed: number;
};

/** Result of the device-owner queries that back uninstall protection. */
export type UninstallState = {
  deviceOwner: boolean;
  profileOwner: boolean;
  uninstallBlocked: boolean;
  alwaysOnVpn: boolean;
  hasPin: boolean;
  packageName: string;
  adminComponent: string;
};

/**
 * Whether the app keeps itself out of the launcher.
 *
 * This is not uninstall protection: Android still lists the app under Settings > Apps, where it can
 * be removed. It only takes away the icon and the reminders that come with it.
 */
export type LauncherState = {
  /** Whether the launcher entry is switched off right now. */
  hidden: boolean;
  /** Whether the user asked for the app to hide itself. */
  hideAfterUse: boolean;
  /** Dial `*#*#<secretCode>#*#*` to bring the icon back without a notification to tap. */
  secretCode: string;
  /** False when hiding would leave no reachable way back into the app. */
  canHide: boolean;
  /** Whether Android will actually show the ongoing notification. */
  notificationsEnabled: boolean;
  /** Whether the app's own UI can be started again from the vault. */
  launchResolvable: boolean;
};

export type BlockedDomainEvent = {
  domains: { domain: string; at: number }[];
};

export type PornFreeVpnModuleEvents = {
  onStats: (status: ProtectionStatus) => void;
  onBlocked: (event: BlockedDomainEvent) => void;
  onStateChange: (event: { running: boolean }) => void;
};
