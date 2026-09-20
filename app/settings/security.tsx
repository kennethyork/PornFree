import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, View } from 'react-native';

import Native from '../../modules/pornfree-vpn';
import { Button, Card, Divider, Pill, SettingRow, styles as ui } from '../../src/components/ui';
import { describeCountdown } from '../../src/lib/format';
import { useProtection } from '../../src/state/protection';
import { useSecurity } from '../../src/state/security';
import { colors, space } from '../../src/theme';

const COMMITMENT_OPTIONS = [
  { hours: 24, label: '1 day' },
  { hours: 72, label: '3 days' },
  { hours: 168, label: '1 week' },
  { hours: 720, label: '30 days' },
];

export default function SecurityScreen() {
  const { status, refresh } = useProtection();
  const { acquirePin, hasPin, refresh: refreshSecurity, forget } = useSecurity();
  const [busy, setBusy] = useState(false);

  useFocusEffect(
    useCallback(() => {
      void refresh();
      void refreshSecurity();
    }, [refresh, refreshSecurity])
  );

  const setPin = useCallback(async () => {
    setBusy(true);
    try {
      if (hasPin) {
        const current = await acquirePin('Confirm the PIN you are replacing.');
        if (current === null) return;
        const next = await acquirePin('Choose the new PIN.', 'create');
        if (next === null) return;
      } else {
        const created = await acquirePin('Choose the PIN that will protect your settings.', 'create');
        if (created === null) return;
      }
      await refreshSecurity();
    } finally {
      setBusy(false);
    }
  }, [acquirePin, hasPin, refreshSecurity]);

  const removePin = useCallback(() => {
    Alert.alert(
      'Remove the PIN?',
      'Anyone who picks up the phone will then be able to switch protection off and change your lists.',
      [
        { text: 'Keep it', style: 'cancel' },
        {
          text: 'Remove',
          style: 'destructive',
          onPress: () => {
            void (async () => {
              const current = await acquirePin('Confirm the PIN you are removing.');
              if (current === null) return;
              const { removePin: remove } = await import('../../src/lib/pin-actions');
              await remove(current);
              forget();
              await refreshSecurity();
              await refresh();
            })();
          },
        },
      ]
    );
  }, [acquirePin, forget, refresh, refreshSecurity]);

  const setCommitment = useCallback(
    (hours: number) => {
      void (async () => {
        const pin = await acquirePin(
          hours > 0
            ? `Locking protection for ${hours / 24 >= 1 ? `${Math.round(hours / 24)} day(s)` : `${hours} hours`} needs your PIN.`
            : 'Changing the commitment needs your PIN.'
        );
        if (pin === null && hasPin) return;
        try {
          await Native.setCommitmentAsync({ hours, pinHash: pin });
          await refresh();
        } catch (failure) {
          Alert.alert('Could not change the commitment', errorMessage(failure));
        }
      })();
    },
    [acquirePin, hasPin, refresh]
  );

  const commitment = status?.commitmentUntil ?? 0;
  const locked = commitment > Date.now();

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Text style={ui.sectionTitle}>PIN</Text>
      <Card>
        <SettingRow
          title={hasPin ? 'PIN is set' : 'No PIN yet'}
          subtitle={
            hasPin
              ? 'Turning protection off, changing lists or clearing stats all ask for it. A correct PIN unlocks the app for five minutes.'
              : 'Without a PIN nothing stops a moment of weakness. It takes ten seconds to set one.'
          }
          right={<Pill label={hasPin ? 'Enabled' : 'Off'} tone={hasPin ? 'success' : 'warn'} />}
        />
        <Divider />
        <Button
          title={hasPin ? 'Change PIN' : 'Set a PIN'}
          onPress={() => void setPin()}
          loading={busy}
          icon="key-outline"
        />
        {hasPin ? (
          <View style={styles.spaced}>
            <Button title="Remove PIN" variant="danger" onPress={removePin} icon="trash-outline" />
          </View>
        ) : null}
      </Card>

      <Text style={ui.sectionTitle}>Commitment lock</Text>
      <Card>
        <Text style={styles.note}>
          A commitment lock refuses to switch protection off until the timer runs out, whatever you
          tap. It is meant for the moments you would otherwise negotiate with yourself. It cannot
          survive removing the app, and the system VPN permission can still be revoked in Android
          settings.
        </Text>
        <SettingRow
          title={locked ? 'Locked' : 'Not locked'}
          subtitle={locked ? describeCountdown(commitment) : 'Protection can be switched off freely.'}
          right={locked ? <Pill label="Locked" tone="warn" /> : undefined}
        />
        <View style={styles.optionRow}>
          {COMMITMENT_OPTIONS.map((option) => (
            <Button
              key={option.hours}
              title={option.label}
              variant="ghost"
              onPress={() => setCommitment(option.hours)}
              style={styles.option}
            />
          ))}
        </View>
        {locked ? (
          <Button title="Release the lock" variant="ghost" onPress={() => setCommitment(0)} />
        ) : null}
      </Card>

      <Text style={styles.footnote}>
        Losing the PIN means losing access to these settings. In that case clearing the app data
        resets everything, including your lists and statistics.
      </Text>
    </ScrollView>
  );
}

function errorMessage(failure: unknown): string {
  if (failure && typeof failure === 'object' && 'message' in failure) {
    return String((failure as { message?: unknown }).message);
  }
  return 'Unknown error';
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { padding: space(4), paddingBottom: space(12) },
  note: { color: colors.muted, fontSize: 12.5, lineHeight: 19, marginBottom: space(2) },
  spaced: { marginTop: space(3) },
  optionRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space(2), marginTop: space(3) },
  option: { flexGrow: 1, paddingHorizontal: space(2), paddingVertical: space(2) },
  footnote: { color: colors.faint, fontSize: 12, lineHeight: 18, marginTop: space(6) },
});
