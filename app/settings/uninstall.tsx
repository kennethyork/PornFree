import { Ionicons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, Linking, ScrollView, StyleSheet, Text, View } from 'react-native';

import Native from '../../modules/quiet-vpn';
import type { UninstallState } from '../../modules/quiet-vpn';
import { Button, Card, Pill, SettingRow, ToggleRow, styles as ui } from '../../src/components/ui';
import { APP_NAME } from '../../src/lib/app-name';
import { Links } from '../../src/lib/links';
import { useProtection } from '../../src/state/protection';
import { useSecurity } from '../../src/state/security';
import { colors, radius, space } from '../../src/theme';

const FALLBACK_COMPONENT =
  'dev.quiet.app/expo.modules.quietvpn.QuietDeviceAdminReceiver';

function errorMessage(failure: unknown): string {
  if (failure && typeof failure === 'object' && 'message' in failure) {
    return String((failure as { message?: unknown }).message);
  }
  return 'Unknown error';
}

export default function UninstallProtectionScreen() {
  const { refresh } = useProtection();
  const { acquirePin, hasPin, refresh: refreshSecurity } = useSecurity();
  const router = useRouter();
  const [state, setState] = useState<UninstallState | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const load = useCallback(async () => {
    try {
      setState(await Native.getUninstallStateAsync());
    } catch {
      setState(null);
    }
    await refreshSecurity();
  }, [refreshSecurity]);

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load])
  );

  const component = state?.adminComponent ?? FALLBACK_COMPONENT;
  const addCommand = `adb shell dpm set-device-owner ${component}`;
  const removeCommand = `adb shell dpm remove-active-admin ${component}`;

  const copy = useCallback(
    async (value: string) => {
      await Clipboard.setStringAsync(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    },
    []
  );

  const setLock = useCallback(
    (blocked: boolean) => {
      void (async () => {
        if (blocked && !hasPin) {
          Alert.alert(
            'Set a PIN first',
            'A lock without a password is pointless: the PIN is what stops the lock from being switched off again.'
          );
          return;
        }
        const pin = await acquirePin(
          blocked
            ? 'Turning on uninstall protection needs your PIN.'
            : 'Turning uninstall protection off needs your PIN.'
        );
        if (pin === null && hasPin) return;
        setBusy(true);
        try {
          setState(await Native.setUninstallBlockedAsync({ blocked, pinHash: pin }));
          await refresh();
        } catch (failure) {
          Alert.alert('Could not change the uninstall lock', errorMessage(failure));
        } finally {
          setBusy(false);
        }
      })();
    },
    [acquirePin, hasPin, refresh]
  );

  const setAlwaysOn = useCallback(
    (enabled: boolean) => {
      void (async () => {
        const pin = await acquirePin('Changing always-on VPN needs your PIN.');
        if (pin === null && hasPin) return;
        setBusy(true);
        try {
          setState(await Native.setAlwaysOnVpnAsync({ enabled, pinHash: pin }));
        } catch (failure) {
          Alert.alert('Could not change always-on VPN', errorMessage(failure));
        } finally {
          setBusy(false);
        }
      })();
    },
    [acquirePin, hasPin]
  );

  const releaseDeviceOwner = useCallback(() => {
    Alert.alert(
      'Turn off device owner mode?',
      `This releases the uninstall lock and the always-on VPN setting. ${APP_NAME} keeps filtering, but the app can then be removed like any other.`,
      [
        { text: 'Keep it', style: 'cancel' },
        {
          text: 'Turn off',
          style: 'destructive',
          onPress: () => {
            void (async () => {
              const pin = await acquirePin('Turning off device owner mode needs your PIN.');
              if (pin === null && hasPin) return;
              setBusy(true);
              try {
                setState(await Native.removeDeviceOwnerAsync({ pinHash: pin }));
                await refresh();
              } catch (failure) {
                Alert.alert('Could not release device owner mode', errorMessage(failure));
              } finally {
                setBusy(false);
              }
            })();
          },
        },
      ]
    );
  }, [acquirePin, hasPin, refresh]);

  const locked = state?.uninstallBlocked ?? false;

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Card style={[styles.status, locked ? styles.statusOn : null]}>
        <Ionicons
          name={locked ? 'lock-closed' : 'lock-open-outline'}
          size={26}
          color={locked ? colors.success : colors.muted}
        />
        <View style={styles.statusText}>
          <Text style={styles.statusTitle}>
            {locked ? 'Uninstall is locked' : 'Uninstall is not locked'}
          </Text>
          <Text style={styles.statusBody}>
            {locked
              ? `Android will refuse to remove ${APP_NAME}. Turning this off, or releasing device owner mode, asks for your PIN first.`
              : `${APP_NAME} can currently be removed like any other app.`}
          </Text>
        </View>
      </Card>

      <Text style={ui.sectionTitle}>The honest version</Text>
      <Card>
        <Text style={styles.body}>
          Android gives no app the right to put a password on the uninstall button: the confirmation
          you see belongs to the system, not to us, and no app can intercept it. The one mechanism
          that genuinely blocks an uninstall is device owner mode, which Android only grants through
          a one-time setup over USB.
        </Text>
        <View style={styles.bullets}>
          <Bullet text="Normal uninstall from the home screen or Settings: blocked, once the lock is on." />
          <Bullet text="Turning the lock off or releasing device owner mode: needs your PIN." />
          <Bullet text="Factory reset, recovery mode, or adb with developer options: always possible. No app can prevent that." />
        </View>
      </Card>

      <Text style={ui.sectionTitle}>Step 1 · A PIN</Text>
      <Card>
        <SettingRow
          title={hasPin ? 'PIN is set' : 'No PIN yet'}
          subtitle={
            hasPin
              ? 'This is the password that guards the lock.'
              : 'The lock refuses to switch on without a PIN, so set one first.'
          }
          right={
            <Pill label={hasPin ? 'Ready' : 'Required'} tone={hasPin ? 'success' : 'warn'} />
          }
        />
        {!hasPin ? (
          <View style={styles.spaced}>
            <Button
              title="Set a PIN"
              icon="key-outline"
              onPress={() => router.push('/settings/security')}
            />
          </View>
        ) : null}
      </Card>

      <Text style={ui.sectionTitle}>Step 2 · Device owner mode</Text>
      {state?.deviceOwner ? (
        <Card>
          <SettingRow
            title="Device owner"
            subtitle={`${APP_NAME} owns this device's policy, so it can lock its own removal.`}
            right={<Pill label="Active" tone="success" />}
          />
          <View style={styles.divider} />
          <ToggleRow
            title="Block uninstall"
            subtitle="Android will refuse to remove the app until this is switched off with your PIN."
            value={locked}
            disabled={busy}
            onValueChange={setLock}
          />
          <View style={styles.divider} />
          <ToggleRow
            title="Always-on VPN"
            subtitle="Android starts filtering by itself after a reboot or when the app is closed. Lockdown mode is never enabled, so normal traffic keeps working."
            value={state?.alwaysOnVpn ?? false}
            disabled={busy}
            onValueChange={setAlwaysOn}
          />
          <View style={styles.spaced}>
            <Button
              title="Release device owner mode"
              variant="danger"
              icon="lock-open-outline"
              onPress={releaseDeviceOwner}
            />
          </View>
        </Card>
      ) : (
        <Card>
          <Text style={styles.body}>
            Device owner mode is granted once, from a computer, over USB. Android will not let an app
            take it any other way, and it refuses if the phone still has accounts on it.
          </Text>
          <NumberedStep
            index={1}
            text="On the phone: Settings → About phone → tap Build number seven times, then enable USB debugging in Developer options."
          />
          <NumberedStep
            index={2}
            text="Remove every account from the phone (Settings → Passwords & accounts). Android blocks device owner setup while accounts exist; you can add them back afterwards."
          />
          <NumberedStep index={3} text="Connect the phone to a computer with adb installed, then run:" />
          <View style={styles.codeBlock}>
            <Text style={styles.code} selectable>
              {addCommand}
            </Text>
          </View>
          <Button
            title={copied ? 'Copied' : 'Copy command'}
            variant="ghost"
            icon={copied ? 'checkmark' : 'copy-outline'}
            onPress={() => void copy(addCommand)}
          />
          <Text style={styles.hint}>
            If adb answers with an account error, remove the remaining accounts and try again. On
            some devices you also need to append --user 0 to the command.
          </Text>
          <View style={styles.spaced}>
            <Button
              title="Check again"
              icon="refresh"
              loading={busy}
              onPress={() => {
                setBusy(true);
                void load().finally(() => setBusy(false));
              }}
            />
          </View>
        </Card>
      )}

      <Text style={ui.sectionTitle}>How it can be removed anyway</Text>
      <Card>
        <Text style={styles.body}>
          Nothing here is meant to trap you: it is a speed bump for the version of you that wants out
          at 1 a.m. If you really want this app gone, you can always:
        </Text>
        <View style={styles.bullets}>
          <Bullet text="Turn the lock off in this screen, or release device owner mode, with your PIN." />
          <Bullet text={`From a computer: ${removeCommand}`} mono />
          <Bullet text="Factory reset the phone, which also erases everything else." />
        </View>
      </Card>

      <Button
        title="Read the full guide"
        variant="ghost"
        icon="book-outline"
        onPress={() => void Linking.openURL(Links.uninstallProtection)}
      />

      <Text style={styles.footnote}>
        Removing the app removes the VPN and the lists with it. Nothing is stored in the cloud, so
        there is nothing to restore except by installing again.
      </Text>
    </ScrollView>
  );
}

function Bullet({ text, mono }: { text: string; mono?: boolean }) {
  return (
    <View style={styles.bulletRow}>
      <Text style={styles.bulletDot}>•</Text>
      <Text style={[styles.bulletText, mono ? styles.code : null]}>{text}</Text>
    </View>
  );
}

function NumberedStep({ index, text }: { index: number; text: string }) {
  return (
    <View style={styles.stepRow}>
      <View style={styles.stepIndex}>
        <Text style={styles.stepIndexText}>{index}</Text>
      </View>
      <Text style={styles.stepText}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { padding: space(4), paddingBottom: space(12) },
  status: { alignItems: 'center', borderColor: colors.border, flexDirection: 'row', gap: space(4) },
  statusOn: { borderColor: colors.success },
  statusText: { flex: 1 },
  statusTitle: { color: colors.text, fontSize: 16, fontWeight: '700' },
  statusBody: { color: colors.muted, fontSize: 12.5, lineHeight: 18, marginTop: space(1.5) },
  body: { color: colors.muted, fontSize: 13, lineHeight: 20 },
  bullets: { gap: space(2), marginTop: space(3) },
  bulletRow: { flexDirection: 'row', gap: space(2) },
  bulletDot: { color: colors.faint, fontSize: 13, lineHeight: 20 },
  bulletText: { color: colors.muted, flex: 1, fontSize: 12.5, lineHeight: 20 },
  stepRow: { flexDirection: 'row', gap: space(3), marginTop: space(4) },
  stepIndex: {
    alignItems: 'center',
    backgroundColor: colors.accentSoft,
    borderRadius: radius.pill,
    height: 22,
    justifyContent: 'center',
    width: 22,
  },
  stepIndexText: { color: colors.accent, fontSize: 12, fontWeight: '700' },
  stepText: { color: colors.muted, flex: 1, fontSize: 12.5, lineHeight: 19 },
  codeBlock: {
    backgroundColor: colors.cardAlt,
    borderColor: colors.border,
    borderRadius: radius.md,
    borderWidth: 1,
    marginBottom: space(3),
    marginTop: space(3),
    padding: space(3),
  },
  code: { color: colors.text, fontFamily: 'monospace', fontSize: 11.5, lineHeight: 17 },
  hint: { color: colors.faint, fontSize: 11.5, lineHeight: 17, marginTop: space(3) },
  divider: {
    backgroundColor: colors.border,
    height: StyleSheet.hairlineWidth,
    marginVertical: space(2),
  },
  spaced: { marginTop: space(3) },
  footnote: { color: colors.faint, fontSize: 12, lineHeight: 18, marginTop: space(6) },
});
