import { Ionicons } from '@expo/vector-icons';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Defs, LinearGradient, Stop } from 'react-native-svg';

import { colors, radius, space } from '../theme';

const SIZE = 216;
const STROKE = 12;

export function ShieldDial({
  active,
  blockedToday,
  onPress,
  disabled,
  caption,
}: {
  active: boolean;
  blockedToday: number;
  onPress: () => void;
  disabled?: boolean;
  caption: string;
}) {
  const radiusValue = (SIZE - STROKE) / 2;
  const circumference = 2 * Math.PI * radiusValue;
  // The ring fills as the day's blocking grows, capping out so it is never full.
  const progress = active ? Math.min(0.86, 0.18 + blockedToday / 900) : 0;

  return (
    <Pressable onPress={onPress} disabled={disabled} style={({ pressed }) => [styles.wrapper, pressed && styles.pressed]}>
      <Svg width={SIZE} height={SIZE}>
        <Defs>
          <LinearGradient id="ring" x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor={active ? colors.success : colors.faint} />
            <Stop offset="1" stopColor={active ? colors.accent : colors.border} />
          </LinearGradient>
        </Defs>
        <Circle
          cx={SIZE / 2}
          cy={SIZE / 2}
          r={radiusValue}
          stroke={colors.border}
          strokeWidth={STROKE}
          fill={colors.card}
        />
        <Circle
          cx={SIZE / 2}
          cy={SIZE / 2}
          r={radiusValue}
          stroke="url(#ring)"
          strokeWidth={STROKE}
          strokeLinecap="round"
          strokeDasharray={`${circumference * progress} ${circumference}`}
          fill="none"
          transform={`rotate(-90 ${SIZE / 2} ${SIZE / 2})`}
        />
      </Svg>
      <View style={styles.overlay}>
        <Ionicons
          name={active ? 'shield-checkmark' : 'shield-outline'}
          size={44}
          color={active ? colors.success : colors.muted}
        />
        <Text style={styles.count}>{blockedToday.toLocaleString('en-US')}</Text>
        <Text style={styles.countLabel}>blocked today</Text>
        <Text style={[styles.caption, active ? styles.captionOn : null]}>{caption}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrapper: { alignItems: 'center', justifyContent: 'center' },
  pressed: { opacity: 0.85 },
  overlay: {
    alignItems: 'center',
    bottom: 0,
    justifyContent: 'center',
    left: 0,
    position: 'absolute',
    right: 0,
    top: 0,
  },
  count: { color: colors.text, fontSize: 34, fontWeight: '800', marginTop: space(2) },
  countLabel: { color: colors.muted, fontSize: 12, letterSpacing: 0.5, marginTop: space(0.5) },
  caption: {
    backgroundColor: colors.cardAlt,
    borderRadius: radius.pill,
    color: colors.muted,
    fontSize: 12,
    fontWeight: '600',
    marginTop: space(2),
    overflow: 'hidden',
    paddingHorizontal: space(3),
    paddingVertical: space(1),
  },
  captionOn: { backgroundColor: colors.successSoft, color: colors.success },
});
