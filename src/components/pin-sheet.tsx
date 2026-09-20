import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, radius, space } from '../theme';
import { Button } from './ui';

type Props = {
  visible: boolean;
  mode: 'verify' | 'create';
  reason?: string;
  busy?: boolean;
  error?: string | null;
  onSubmit: (pin: string) => void;
  onCancel: () => void;
};

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', 'del'];

export function PinSheet({ visible, mode, reason, busy, error, onSubmit, onCancel }: Props) {
  const [entry, setEntry] = useState('');
  const [firstEntry, setFirstEntry] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  useEffect(() => {
    if (!visible) {
      setEntry('');
      setFirstEntry(null);
      setLocalError(null);
    }
  }, [visible]);

  const creating = mode === 'create' && firstEntry === null;

  const press = (key: string) => {
    setLocalError(null);
    if (key === 'del') {
      setEntry((current) => current.slice(0, -1));
      return;
    }
    if (key === '' || entry.length >= 8) return;
    setEntry((current) => current + key);
  };

  const advance = () => {
    if (entry.length < 4) {
      setLocalError('Use at least 4 digits.');
      return;
    }
    if (mode === 'verify') {
      onSubmit(entry);
      setEntry('');
      return;
    }
    if (firstEntry === null) {
      setFirstEntry(entry);
      setEntry('');
      return;
    }
    if (firstEntry !== entry) {
      setLocalError('The two entries did not match.');
      setFirstEntry(null);
      setEntry('');
      return;
    }
    onSubmit(entry);
    setEntry('');
    setFirstEntry(null);
  };

  const title = mode === 'create'
    ? creating
      ? 'Choose a PIN'
      : 'Repeat the PIN'
    : 'Enter your PIN';

  const message = localError ?? error ?? reason
    ?? (mode === 'create'
      ? 'This PIN is what stands between anyone holding your phone and your protection settings.'
      : 'Your PIN is needed for this.');

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onCancel}>
      <View style={styles.backdrop}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <Ionicons name="lock-closed" size={20} color={colors.accent} />
            <Text style={styles.title}>{title}</Text>
            <Pressable onPress={onCancel} hitSlop={12} style={styles.close}>
              <Ionicons name="close" size={20} color={colors.muted} />
            </Pressable>
          </View>

          <Text style={[styles.message, localError || error ? styles.errorText : null]}>{message}</Text>

          <View style={styles.dots}>
            {Array.from({ length: Math.max(entry.length, 4) }).map((_, index) => (
              <View
                key={index}
                style={[styles.dot, index < entry.length ? styles.dotFilled : null]}
              />
            ))}
          </View>

          <View style={styles.keypad}>
            {KEYS.map((key, index) => (
              <Pressable
                key={`${key}-${index}`}
                onPress={() => press(key)}
                disabled={key === ''}
                style={({ pressed }) => [
                  styles.key,
                  key === '' ? styles.keyGhost : null,
                  pressed && key !== '' ? styles.keyPressed : null,
                ]}
              >
                {key === 'del' ? (
                  <Ionicons name="backspace-outline" size={22} color={colors.text} />
                ) : (
                  <Text style={styles.keyText}>{key}</Text>
                )}
              </Pressable>
            ))}
          </View>

          <Button
            title={mode === 'create' && firstEntry === null ? 'Continue' : 'Unlock'}
            onPress={advance}
            disabled={entry.length < 4}
            loading={busy}
            icon={mode === 'create' && firstEntry === null ? 'arrow-forward' : 'lock-open'}
          />
        </View>
      </View>
    </Modal>
  );
}

export function PinBadge({ locked }: { locked: boolean }) {
  return (
    <View style={styles.badge}>
      <Ionicons name={locked ? 'lock-closed' : 'lock-open'} size={13} color={colors.muted} />
      <Text style={styles.badgeText}>{locked ? 'PIN protected' : 'No PIN'}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    backgroundColor: 'rgba(2, 6, 23, 0.82)',
    flex: 1,
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: colors.card,
    borderColor: colors.border,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    borderWidth: StyleSheet.hairlineWidth,
    padding: space(5),
    paddingBottom: space(8),
  },
  header: { alignItems: 'center', flexDirection: 'row' },
  title: { color: colors.text, flex: 1, fontSize: 17, fontWeight: '700', marginLeft: space(2) },
  close: { padding: space(1) },
  message: { color: colors.muted, fontSize: 13, lineHeight: 19, marginTop: space(3) },
  errorText: { color: colors.danger },
  dots: {
    flexDirection: 'row',
    gap: space(2.5),
    justifyContent: 'center',
    marginVertical: space(5),
  },
  dot: {
    backgroundColor: colors.cardAlt,
    borderColor: colors.border,
    borderRadius: radius.pill,
    borderWidth: 1,
    height: 14,
    width: 14,
  },
  dotFilled: { backgroundColor: colors.accent, borderColor: colors.accent },
  keypad: { flexDirection: 'row', flexWrap: 'wrap', gap: space(3), marginBottom: space(4) },
  key: {
    alignItems: 'center',
    backgroundColor: colors.cardAlt,
    borderRadius: radius.md,
    height: 60,
    justifyContent: 'center',
    width: '30%',
  },
  keyGhost: { backgroundColor: 'transparent' },
  keyPressed: { backgroundColor: colors.accentSoft },
  keyText: { color: colors.text, fontSize: 22, fontWeight: '600' },
  badge: { alignItems: 'center', flexDirection: 'row', gap: space(1.5) },
  badgeText: { color: colors.muted, fontSize: 12 },
});
