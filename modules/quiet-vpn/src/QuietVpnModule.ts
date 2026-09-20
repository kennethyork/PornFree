import { NativeModule, requireNativeModule } from 'expo';

import type {
  BlocklistInfo,
  QuietVpnModuleEvents,
  DailyStats,
  LauncherState,
  ProtectionConfig,
  ProtectionStatus,
  UninstallState,
} from './QuietVpn.types';

declare class QuietVpnModule extends NativeModule<QuietVpnModuleEvents> {
  isPermissionGrantedAsync(): Promise<boolean>;
  requestPermissionAsync(): Promise<boolean>;
  startAsync(config: ProtectionConfig): Promise<ProtectionStatus>;
  configureAsync(config: ProtectionConfig): Promise<ProtectionStatus>;
  stopAsync(options: { pinHash?: string | null }): Promise<ProtectionStatus>;
  getStatusAsync(): Promise<ProtectionStatus>;
  getConfigAsync(): Promise<ProtectionConfig>;
  getListsAsync(): Promise<BlocklistInfo[]>;
  ensureListsAsync(): Promise<BlocklistInfo[]>;
  installBundledListAsync(id: string): Promise<BlocklistInfo>;
  downloadListAsync(id: string, url: string, title: string): Promise<BlocklistInfo>;
  deleteListAsync(id: string): Promise<boolean>;
  getDailyStatsAsync(days: number): Promise<DailyStats[]>;
  clearStatsAsync(): Promise<boolean>;
  setPinAsync(options: {
    hash: string | null;
    currentPinHash?: string | null;
  }): Promise<ProtectionStatus>;
  verifyPinAsync(hash: string): Promise<boolean>;
  setCommitmentAsync(options: { hours: number; pinHash?: string | null }): Promise<ProtectionStatus>;
  isDeviceOwnerAsync(): Promise<boolean>;
  getLauncherStateAsync(): Promise<LauncherState>;
  setLauncherHiddenAsync(options: {
    hidden: boolean;
    pinHash?: string | null;
  }): Promise<LauncherState>;
  getUninstallStateAsync(): Promise<UninstallState>;
  setUninstallBlockedAsync(options: {
    blocked: boolean;
    pinHash?: string | null;
  }): Promise<UninstallState>;
  setAlwaysOnVpnAsync(options: {
    enabled: boolean;
    pinHash?: string | null;
  }): Promise<UninstallState>;
  removeDeviceOwnerAsync(options: { pinHash?: string | null }): Promise<UninstallState>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<QuietVpnModule>('QuietVpn');
