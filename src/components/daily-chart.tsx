import { StyleSheet, Text, View } from 'react-native';

import type { DailyStats } from '../../modules/blockporna-vpn';
import { formatCompact, weekdayLabel } from '../lib/format';
import { colors, radius, space } from '../theme';

export function DailyChart({ data }: { data: DailyStats[] }) {
  const peak = Math.max(1, ...data.map((item) => item.blocked));

  return (
    <View style={styles.wrapper}>
      <View style={styles.bars}>
        {data.map((item) => {
          const ratio = item.blocked / peak;
          return (
            <View key={item.date} style={styles.column}>
              <Text style={styles.value}>{item.blocked > 0 ? formatCompact(item.blocked) : ''}</Text>
              <View style={styles.track}>
                <View
                  style={[
                    styles.bar,
                    {
                      height: `${Math.max(2, ratio * 100)}%`,
                      backgroundColor: ratio > 0 ? colors.accent : colors.border,
                    },
                  ]}
                />
              </View>
              <Text style={styles.label}>{weekdayLabel(item.date)}</Text>
            </View>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: { marginTop: space(2) },
  bars: { alignItems: 'flex-end', flexDirection: 'row', gap: space(2), height: 148 },
  column: { alignItems: 'center', flex: 1, height: '100%', justifyContent: 'flex-end' },
  value: { color: colors.muted, fontSize: 11, marginBottom: space(1) },
  track: {
    backgroundColor: colors.cardAlt,
    borderRadius: radius.sm,
    flex: 1,
    justifyContent: 'flex-end',
    overflow: 'hidden',
    width: '70%',
  },
  bar: { borderRadius: radius.sm, width: '100%' },
  label: { color: colors.faint, fontSize: 11, marginTop: space(1.5) },
});
