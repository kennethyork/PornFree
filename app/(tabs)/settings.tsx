import { Ionicons } from '@expo/vector-icons';
import * as Application from 'expo-application';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import Native from '../../modules/pornfree-vpn';
import type { BlockMode, LauncherState, UninstallState } from '../../modules/pornfree-vpn';
import { Card, Chevron, Pill, SettingRow, ToggleRow, styles as ui } from '../../src/components/ui';
import { APP_NAME } from '../../src/lib/app-name';
import { Links } from '../../src/lib/links';
import { ensureNotificationPermission } from '../../src/lib/notifications';
import { RESOLVER_PRESETS, resolverPresetFor } from '../../src/lib/lists';
import { useProtection } from '../../src/state/protection';
import { useSecurity } from '../../src/state/security';
import { colors, radius, space } from '../../src/theme';

const MODES: { id: BlockMode; title: string; description: string }[] = [
  {
    id: 'nodata',
    title: 'Empty answer',
    description: 'The name resolves to nothing. Best compatibility, fastest failure.',
  },
  {
    id: 'null',
    title: 'Point at 0.0.0.0',
    description: 'Answers with 0.0.0.0 and ::. Some apps show an error, none can connect.',
  },
  {
    id: 'nxdomain',
    title: 'Domain does not exist',
    description: 'Answers NXDOMAIN, as if the site had never existed.',
  },
];

function ChoiceRow({
  title,
  description,
  selected,
  onPress,
  disabled,
}: {
  title: string;
  description?: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <Pressable onPress={onPress} disabled={disabled} style={styles.choice}>
      <Ionicons
        name={selected ? 'radio-button-on' : 'radio-button-off'}
        size={18}
        color={selected ? colors.accent : colors.faint}
      />
      <View style={styles.choiceText}>
        <Text style={styles.choiceTitle}>{title}</Text>
        {description ? <Text style={styles.choiceDescription}>{description}</Text> : null}
      </View>
    </Pressable>
  );
}

export default function SettingsScreen() {
  const { config, status, saveConfig, busy } = useProtection();
  const { acquirePin, hasPin, refresh: refreshSecurity } = useSecurity();
  const router = useRouter();
  const [uninstall, setUninstall] = useState<UninstallState | null>(null);
  const [launcher, setLauncher] = useState<LauncherState | null>(null);

  const load = useCallback(async () => {
    try {
      setUninstall(await Native.getUninstallStateAsync());
    } catch {
      setUninstall(null);
    }
    try {
      setLauncher(await Native.getLauncherStateAsync());
    } catch {
      setLauncher(null);
    }
    await refreshSecurity();
  }, [refreshSecurity]);

  const setHidden = useCallback(
    (hidden: boolean) => {
      void (async () => {
        if (hidden) {
          const allowed = await ensureNotificationPermission();
          if (!allowed) {
            Alert.alert(
              'Notifications are off',
              'The ongoing notification is the only way back into a hidden app. Allow notifications ' +
                `for ${APP_NAME} first, or you would be locked out of your own app.`,
              [
                { text: 'Not now', style: 'cancel' },
                { text: 'Notification settings', onPress: () => void Linking.openSettings() },
              ]
            );
            return;
          }
        }
        const pin = await acquirePin(
          hidden
            ? 'Hiding the app needs your PIN.'
            : 'Bringing the icon back needs your PIN.'
        );
        if (pin === null && hasPin) return;
        try {
          setLauncher(await Native.setLauncherHiddenAsync({ hidden, pinHash: pin }));
        } catch (failure) {
          Alert.alert('Could not change launcher visibility', (failure as Error).message, [
            { text: 'OK' },
            { text: 'Notification settings', onPress: () => void Linking.openSettings() },
          ]);
        }
      })();
    },
    [acquirePin, hasPin]
  );

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load])
  );

  const change = useCallback(
    (reason: string, patch: Parameters<typeof saveConfig>[0]) => {
      void (async () => {
        const pin = await acquirePin(reason);
        if (pin === null && hasPin) return;
        await saveConfig(patch, pin);
      })();
    },
    [acquirePin, hasPin, saveConfig]
  );

  const preset = config ? resolverPresetFor(config.upstreams) : undefined;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Text style={ui.sectionTitle}>Resolver</Text>
      <Card>
        <Text style={styles.note}>
          Allowed lookups are relayed to a resolver you trust. The family resolvers below also filter
          adult content on their side, which catches sites that no list knows about yet.
        </Text>
        {RESOLVER_PRESETS.map((item, index) => (
          <View key={item.id}>
            {index > 0 ? <View style={styles.divider} /> : null}
            <ChoiceRow
              title={item.title}
              description={item.description}
              selected={preset?.id === item.id}
              disabled={busy || !config}
              onPress={() =>
                change('Changing the resolver changes how filtering works.', {
                  upstreams: item.servers,
                })
              }
            />
          </View>
        ))}
      </Card>

      <Text style={ui.sectionTitle}>Blocked answers</Text>
      <Card>
        {MODES.map((item, index) => (
          <View key={item.id}>
            {index > 0 ? <View style={styles.divider} /> : null}
            <ChoiceRow
              title={item.title}
              description={item.description}
              selected={(config?.blockMode ?? 'nodata') === item.id}
              disabled={busy || !config}
              onPress={() =>
                change('Changing how blocked sites answer needs your PIN.', { blockMode: item.id })
              }
            />
          </View>
        ))}
      </Card>

      <Text style={ui.sectionTitle}>Network</Text>
      <Card>
        <ToggleRow
          title="Block encrypted DNS bypass"
          subtitle="Stops apps from using their own DNS-over-HTTPS or DNS-over-TLS resolver, which would skip filtering entirely."
          value={config?.interceptEncryptedDns ?? true}
          disabled={busy || !config}
          onValueChange={(next) =>
            change('This decides whether encrypted DNS can bypass filtering.', {
              interceptEncryptedDns: next,
            })
          }
        />
        <View style={styles.divider} />
        <ToggleRow
          title="Filter IPv6 lookups"
          subtitle="Turn this off only if IPv6 is broken on your network. IPv6 lookups would then go unfiltered."
          value={config?.includeIpv6 ?? true}
          disabled={busy || !config}
          onValueChange={(next) =>
            change('This changes how much of your traffic is filtered.', { includeIpv6: next })
          }
        />
      </Card>

      <Text style={ui.sectionTitle}>Behaviour</Text>
      <Card>
        <ToggleRow
          title="Restart protection after a reboot"
          subtitle="Android only lets this happen if the VPN permission is still granted."
          value={config?.autoRestart ?? true}
          disabled={busy || !config}
          onValueChange={(next) =>
            change('Turning this off makes it easier to stay unprotected.', { autoRestart: next })
          }
        />
        <View style={styles.divider} />
        <ToggleRow
          title="Keep a log of blocked domains"
          subtitle="Domain names only, kept for seven days. Needed for the stats screen."
          value={config?.logDomains ?? true}
          disabled={busy || !config}
          onValueChange={(next) =>
            change('The log is what makes your progress visible.', { logDomains: next })
          }
        />
      </Card>

      <Text style={ui.sectionTitle}>Visibility</Text>
      <Card>
        <ToggleRow
          title="Hide from the launcher"
          subtitle={
            launcher?.hidden
              ? `${APP_NAME} has no launcher icon. Open it from the ongoing notification, or with ` +
                `pornfree://open. Dialling *#*#${launcher?.secretCode ?? '7676'}#*#* brings the icon back.`
              : launcher?.notificationsEnabled === false
                ? `Notifications are switched off for ${APP_NAME}. The ongoing notification is how a hidden app is opened, so allow them first or you would be locked out.`
                : `Removes ${APP_NAME} from your launcher. The app keeps working, and the ongoing notification opens it. Not uninstall protection: Settings → Apps still lists it.`
          }
          value={launcher?.hideAfterUse ?? false}
          disabled={busy || !launcher}
          onValueChange={setHidden}
        />
      </Card>

      <Text style={ui.sectionTitle}>Security</Text>
      <Card>
        <SettingRow
          title="PIN & commitment"
          subtitle="The PIN is required to switch off protection or change lists."
          onPress={() => router.push('/settings/security')}
          right={
            <View style={styles.rightGroup}>
              <Pill label={hasPin ? 'PIN set' : 'No PIN'} tone={hasPin ? 'success' : 'warn'} />
              <Chevron />
            </View>
          }
        />
        <View style={styles.divider} />
        <SettingRow
          title="Uninstall protection"
          subtitle="Requires device owner mode. Stops the app from being removed without your PIN."
          onPress={() => router.push('/settings/uninstall')}
          right={
            <View style={styles.rightGroup}>
              <Pill
                label={
                  uninstall?.uninstallBlocked
                    ? 'Locked'
                    : uninstall?.deviceOwner
                      ? 'Available'
                      : 'Not set up'
                }
                tone={uninstall?.uninstallBlocked ? 'success' : uninstall?.deviceOwner ? 'accent' : 'neutral'}
              />
              <Chevron />
            </View>
          }
        />
      </Card>

      <Text style={ui.sectionTitle}>About</Text>
      <Card>
        <SettingRow
          title={APP_NAME}
          subtitle={`Version ${Application.nativeApplicationVersion ?? '1.0.0'} · GPL-3.0 · Android only`}
        />
        <View style={styles.divider} />
        <SettingRow
          title="How it works"
          subtitle={
            'A local VPN service that only routes DNS servers. Lookups are matched against your ' +
            'lists, blocked ones are answered locally, and the rest are relayed to the resolver you ' +
            'picked. Nothing is inspected beyond the domain name, and no account or server is involved.'
          }
        />
        <View style={styles.divider} />
        <SettingRow
          title="Statistics"
          subtitle={status ? `${status.totalBlocked.toLocaleString('en-US')} lookups blocked on this device` : '—'}
        />
        <View style={styles.divider} />
        <SettingRow
          title="How filtering works"
          subtitle="The architecture notes in the repository explain the tunnel, the matching and the limits."
          onPress={() => void Linking.openURL(Links.architecture)}
          right={<Chevron />}
        />
        <View style={styles.divider} />
        <SettingRow
          title="Source code"
          subtitle="Source on GitHub · GPL-3.0"
          onPress={() => void Linking.openURL(Links.repository)}
          right={<Chevron />}
        />
        <View style={styles.divider} />
        <SettingRow
          title="Report a bug"
          subtitle="A missed site, a broken app, anything else."
          onPress={() => void Linking.openURL(Links.issues)}
          right={<Chevron />}
        />
      </Card>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { padding: space(4), paddingBottom: space(12) },
  note: { color: colors.muted, fontSize: 12.5, lineHeight: 19, marginBottom: space(2) },
  divider: {
    backgroundColor: colors.border,
    height: StyleSheet.hairlineWidth,
    marginVertical: space(2),
  },
  choice: { alignItems: 'flex-start', flexDirection: 'row', gap: space(3), paddingVertical: space(2.5) },
  choiceText: { flex: 1 },
  choiceTitle: { color: colors.text, fontSize: 14.5, fontWeight: '600' },
  choiceDescription: { color: colors.muted, fontSize: 12.5, lineHeight: 18, marginTop: space(1) },
  rightGroup: { alignItems: 'center', flexDirection: 'row', gap: space(2) },
  code: {
    backgroundColor: colors.cardAlt,
    borderRadius: radius.sm,
    color: colors.text,
    fontFamily: 'monospace',
    fontSize: 12,
    padding: space(3),
  },
});
