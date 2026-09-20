import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, View } from 'react-native';

import { DailyChart } from '../../src/components/daily-chart';
import { Button, Card, EmptyState, StatTile, styles as ui } from '../../src/components/ui';
import { formatNumber } from '../../src/lib/format';
import { blockedSince, clearHistory, topBlocked } from '../../src/lib/history';
import Native from '../../modules/quiet-vpn';
import type { DailyStats } from '../../modules/quiet-vpn';
import { useProtection } from '../../src/state/protection';
import { useSecurity } from '../../src/state/security';
import { colors, space } from '../../src/theme';

type Top = { domain: string; hits: number };

export default function StatsScreen() {
  const { status } = useProtection();
  const { acquirePin } = useSecurity();
  const [daily, setDaily] = useState<DailyStats[]>([]);
  const [top, setTop] = useState<Top[]>([]);
  const [week, setWeek] = useState(0);
  const [today, setToday] = useState(0);

  const load = useCallback(async () => {
    const now = Date.now();
    const weekAgo = now - 7 * 86_400_000;
    try {
      const [stats, leaders, weekCount, todayCount] = await Promise.all([
        Native.getDailyStatsAsync(7),
        topBlocked(weekAgo, 10),
        blockedSince(weekAgo),
        blockedSince(now - 86_400_000),
      ]);
      setDaily(stats);
      setTop(leaders);
      setWeek(weekCount);
      setToday(todayCount);
    } catch {
      setDaily([]);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load])
  );

  const reset = useCallback(() => {
    Alert.alert('Clear history?', 'Daily totals and the list of blocked domains are erased.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Clear',
        style: 'destructive',
        onPress: () => {
          void (async () => {
            const pin = await acquirePin('Clearing your progress needs the PIN.');
            if (pin === null && (status?.hasPin ?? false)) return;
            await clearHistory();
            await Native.clearStatsAsync();
            await load();
          })();
        },
      },
    ]);
  }, [acquirePin, load, status?.hasPin]);

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <View style={styles.statRow}>
        <StatTile label="last 7 days" value={formatNumber(week)} hint="recorded lookups" />
        <StatTile label="last 24 hours" value={formatNumber(today)} />
      </View>
      <View style={styles.statRow}>
        <StatTile label="today" value={formatNumber(status?.blockedToday ?? 0)} />
        <StatTile label="all time" value={formatNumber(status?.totalBlocked ?? 0)} />
      </View>

      <Text style={ui.sectionTitle}>Blocked per day</Text>
      <Card>
        {daily.length === 0 ? <EmptyState text="No data yet." /> : <DailyChart data={daily} />}
      </Card>

      <Text style={ui.sectionTitle}>Most blocked this week</Text>
      <Card>
        {top.length === 0 ? (
          <EmptyState text="Nothing recorded yet. That is a good thing." />
        ) : (
          top.map((item, index) => (
            <View key={item.domain}>
              {index > 0 ? <View style={styles.divider} /> : null}
              <View style={styles.topRow}>
                <Text style={styles.rank}>{index + 1}</Text>
                <Text style={styles.domain} numberOfLines={1}>
                  {item.domain}
                </Text>
                <Text style={styles.hits}>{formatNumber(item.hits)}</Text>
              </View>
            </View>
          ))
        )}
      </Card>

      <Text style={styles.note}>
        Only domains are stored, never which app asked for them. Detail is kept for seven days;
        the daily totals stay until you clear them.
      </Text>
      <Button title="Clear statistics" variant="danger" icon="trash-outline" onPress={reset} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { padding: space(4), paddingBottom: space(10) },
  statRow: { flexDirection: 'row', gap: space(3), marginBottom: space(3) },
  topRow: { alignItems: 'center', flexDirection: 'row', gap: space(3) },
  rank: { color: colors.faint, fontSize: 12, width: 18 },
  domain: { color: colors.text, flex: 1, fontSize: 13.5 },
  hits: { color: colors.accent, fontSize: 13, fontWeight: '700' },
  divider: {
    backgroundColor: colors.border,
    height: StyleSheet.hairlineWidth,
    marginVertical: space(2.5),
  },
  note: {
    color: colors.faint,
    fontSize: 12,
    lineHeight: 18,
    marginBottom: space(4),
    marginTop: space(4),
  },
});
