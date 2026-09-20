import { Ionicons } from '@expo/vector-icons';
import { useCallback, useMemo, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import { Button, Card, EmptyState, styles as ui } from '../src/components/ui';
import { useProtection } from '../src/state/protection';
import { useSecurity } from '../src/state/security';
import { colors, radius, space } from '../src/theme';

export default function AllowlistScreen() {
  const { config, saveConfig } = useProtection();
  const { acquirePin, hasPin } = useSecurity();
  const [entry, setEntry] = useState('');

  const allowlist = useMemo(() => config?.allowlist ?? [], [config?.allowlist]);

  const save = useCallback(
    (next: string[]) => {
      void (async () => {
        const pin = await acquirePin('The allowlist is part of your protection.');
        if (pin === null && hasPin) return;
        await saveConfig({ allowlist: next }, pin);
      })();
    },
    [acquirePin, hasPin, saveConfig]
  );

  const add = useCallback(() => {
    const domain = entry.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    if (!domain.includes('.')) {
      Alert.alert('That does not look like a domain', 'Use something like example.com');
      return;
    }
    if (allowlist.includes(domain)) {
      setEntry('');
      return;
    }
    save([...allowlist, domain]);
    setEntry('');
  }, [allowlist, entry, save]);

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Card>
        <Text style={styles.explainer}>
          Domains listed here are never blocked, and neither are their sub-domains. Use it when a
          list is too broad and breaks something you need.
        </Text>
        <TextInput
          style={styles.input}
          placeholder="example.com"
          placeholderTextColor={colors.faint}
          autoCapitalize="none"
          autoCorrect={false}
          value={entry}
          onChangeText={setEntry}
          onSubmitEditing={add}
        />
        <Button title="Add domain" onPress={add} icon="add" disabled={entry.trim().length === 0} />
      </Card>

      <Text style={ui.sectionTitle}>Exempt domains</Text>
      <Card>
        {allowlist.length === 0 ? (
          <EmptyState text="Nothing is exempt yet." />
        ) : (
          allowlist.map((domain, index) => (
            <View key={domain}>
              {index > 0 ? <View style={styles.divider} /> : null}
              <View style={styles.row}>
                <Text style={styles.domain}>{domain}</Text>
                <Pressable
                  hitSlop={10}
                  onPress={() => save(allowlist.filter((value) => value !== domain))}
                >
                  <Ionicons name="trash-outline" size={18} color={colors.danger} />
                </Pressable>
              </View>
            </View>
          ))
        )}
      </Card>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { padding: space(4), paddingBottom: space(10) },
  explainer: {
    color: colors.muted,
    fontSize: 13,
    lineHeight: 19,
    marginBottom: space(3),
  },
  input: {
    backgroundColor: colors.cardAlt,
    borderColor: colors.border,
    borderRadius: radius.md,
    borderWidth: 1,
    color: colors.text,
    marginBottom: space(3),
    paddingHorizontal: space(3),
    paddingVertical: space(3),
  },
  row: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  domain: { color: colors.text, fontSize: 14 },
  divider: {
    backgroundColor: colors.border,
    height: StyleSheet.hairlineWidth,
    marginVertical: space(2.5),
  },
});
