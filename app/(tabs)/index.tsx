import { Ionicons } from '@expo/vector-icons';
import { Link } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ShieldDial } from '../../src/components/shield';
import { Button, Card, EmptyState, Pill, StatTile, styles as ui } from '../../src/components/ui';
import { APP_NAME } from '../../src/lib/app-name';
import { formatDuration, formatNumber, formatRelative } from '../../src/lib/format';
import { recentBlocked } from '../../src/lib/history';
import { useProtection } from '../../src/state/protection';
import { useSecurity } from '../../src/state/security';
import { colors, space } from '../../src/theme';

type Recent = { id: number; domain: string; at: number };

export default function ShieldScreen() {
  const insets = useSafeAreaInsets();
  const { ready, status, error, busy, dismissError, start, stop, requestPermission } = useProtection();
  const { acquirePin } = useSecurity();
  const [recent, setRecent] = useState<Recent[]>([]);
  const [working, setWorking] = useState(false);

  const loadRecent = useCallback(async () => {
    try {
      setRecent(await recentBlocked(6));
    } catch {
      setRecent([]);
    }
  }, []);

  useEffect(() => {
    void loadRecent();
  }, [loadRecent, status?.blockedToday]);

  const toggle = useCallback(async () => {
    if (!status) return;
    setWorking(true);
    try {
      if (status.running) {
        const pin = await acquirePin('Turning protection off requires your PIN.');
        if (pin === null && status.hasPin) return;
        await stop(pin);
      } else {
        await start();
      }
    } finally {
      setWorking(false);
      void loadRecent();
    }
  }, [acquirePin, loadRecent, start, status, stop]);

  if (!ready || !status) {
    return (
      <View style={styles.loading}>
        <Text style={styles.splashTitle}>{APP_NAME}</Text>
        <Text style={styles.splashText}>Starting protection…</Text>
      </View>
    );
  }

  const active = status.running;
  const permissionMissing = !status.permissionGranted;

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + space(4) }]}
    >
      <View style={styles.brandRow}>
        <Text style={styles.brand}>{APP_NAME}</Text>
        <Pill
          label={active ? 'Filtering' : 'Off'}
          tone={active ? 'success' : 'neutral'}
        />
      </View>

      {error ? (
        <Card style={styles.errorCard}>
          <Text style={styles.errorText}>{error}</Text>
          <Button title="Dismiss" variant="ghost" onPress={dismissError} />
        </Card>
      ) : null}

      {permissionMissing ? (
        <Card style={styles.warnCard}>
          <Text style={styles.warnTitle}>Android needs your permission first</Text>
          <Text style={styles.warnText}>
            {APP_NAME} filters by running a local VPN that only handles DNS. Android will show a
            one-time consent dialog.
          </Text>
          <Button title="Grant permission" onPress={() => void requestPermission()} icon="key" />
        </Card>
      ) : null}

      <ShieldDial
        active={active}
        blockedToday={status.blockedToday}
        onPress={() => void toggle()}
        disabled={working || busy}
        caption={active ? 'Active — tap to stop' : 'Off — tap to start'}
      />

      {status.commitmentUntil > 0 ? (
        <Card style={styles.commitment}>
          <Ionicons name="hourglass-outline" size={18} color={colors.warn} />
          <Text style={styles.commitmentText}>
            Commitment lock is on. Protection cannot be switched off until{' '}
            {new Date(status.commitmentUntil).toLocaleString()}.
          </Text>
        </Card>
      ) : null}

      <View style={styles.statRow}>
        <StatTile label="blocked today" value={formatNumber(status.blockedToday)} />
        <StatTile label="allowed today" value={formatNumber(status.allowedToday)} />
      </View>
      <View style={styles.statRow}>
        <StatTile
          label="rules loaded"
          value={formatNumber(status.listSize)}
          hint="domains"
        />
        <StatTile
          label="uptime"
          value={active ? formatDuration(Date.now() - status.startedAt) : '—'}
          hint={active ? 'this session' : 'not running'}
        />
      </View>

      <View style={styles.sectionHeader}>
        <Text style={ui.sectionTitle}>Just blocked</Text>
        <Link href="/logs" style={styles.link}>
          See all
        </Link>
      </View>
      <Card>
        {recent.length === 0 ? (
          <EmptyState
            text={active ? 'Nothing blocked yet. Keep it that way.' : 'Protection is off.'}
          />
        ) : (
          recent.map((item, index) => (
            <View key={item.id}>
              {index > 0 ? <View style={styles.rowDivider} /> : null}
              <View style={styles.recentRow}>
                <Ionicons name="close-circle" size={16} color={colors.danger} />
                <Text style={styles.recentDomain} numberOfLines={1}>
                  {item.domain}
                </Text>
                <Text style={styles.recentTime}>{formatRelative(item.at)}</Text>
              </View>
            </View>
          ))
        )}
      </Card>

      <Text style={styles.footnote}>
        {APP_NAME} filters DNS lookups for adult domains. It never sees the contents of your
        traffic, and nothing leaves the device except the DNS queries themselves.
      </Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { paddingBottom: space(10), paddingHorizontal: space(4) },
  loading: {
    alignItems: 'center',
    backgroundColor: colors.bg,
    flex: 1,
    justifyContent: 'center',
  },
  splashTitle: { color: colors.text, fontSize: 26, fontWeight: '800' },
  splashText: { color: colors.muted, fontSize: 14, marginTop: space(2) },
  brandRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: space(4),
  },
  brand: { color: colors.text, fontSize: 22, fontWeight: '800' },
  errorCard: { borderColor: colors.danger, marginBottom: space(3) },
  errorText: { color: colors.danger, fontSize: 13, marginBottom: space(3) },
  warnCard: { borderColor: colors.warn, marginBottom: space(4) },
  warnTitle: { color: colors.text, fontSize: 15, fontWeight: '700' },
  warnText: {
    color: colors.muted,
    fontSize: 13,
    lineHeight: 19,
    marginBottom: space(3),
    marginTop: space(2),
  },
  commitment: {
    alignItems: 'center',
    borderColor: colors.warn,
    flexDirection: 'row',
    gap: space(3),
    marginTop: space(4),
  },
  commitmentText: { color: colors.muted, flex: 1, fontSize: 12.5, lineHeight: 18 },
  statRow: { flexDirection: 'row', gap: space(3), marginTop: space(3) },
  sectionHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: space(2),
  },
  link: { color: colors.accent, fontSize: 13, fontWeight: '600' },
  recentRow: { alignItems: 'center', flexDirection: 'row', gap: space(2.5) },
  recentDomain: { color: colors.text, flex: 1, fontSize: 13.5 },
  recentTime: { color: colors.faint, fontSize: 11.5 },
  rowDivider: {
    backgroundColor: colors.border,
    height: StyleSheet.hairlineWidth,
    marginVertical: space(2.5),
  },
  footnote: {
    color: colors.faint,
    fontSize: 12,
    lineHeight: 18,
    marginTop: space(6),
  },
});
