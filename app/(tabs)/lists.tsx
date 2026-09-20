import { useRouter } from 'expo-router';
import { useCallback, useMemo, useState } from 'react';
import { Alert, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import { Button, Card, Chevron, EmptyState, Pill, SettingRow, ToggleRow, styles as ui } from '../../src/components/ui';
import { formatDateTime, formatNumber } from '../../src/lib/format';
import { SUGGESTED_LISTS } from '../../src/lib/lists';
import { useProtection } from '../../src/state/protection';
import { useSecurity } from '../../src/state/security';
import { colors, radius, space } from '../../src/theme';

export default function ListsScreen() {
  const { lists, config, saveConfig, installList, updateList, removeList, busy } = useProtection();
  const { acquirePin, hasPin } = useSecurity();
  const [customUrl, setCustomUrl] = useState('');
  const [customTitle, setCustomTitle] = useState('');
  const [working, setWorking] = useState<string | null>(null);
  const router = useRouter();

  const enabledIds = useMemo(() => config?.listIds ?? [], [config?.listIds]);
  const installedIds = new Set(lists.filter((item) => item.installed).map((item) => item.id));

  const withPin = useCallback(
    async (reason: string, action: (pin: string | null) => Promise<void>) => {
      const pin = await acquirePin(reason);
      // Cancelling the prompt must not fall through to the action.
      if (pin === null && hasPin) return;
      await action(pin);
    },
    [acquirePin, hasPin]
  );

  const toggleList = useCallback(
    (id: string, enabled: boolean) => {
      const next = enabled ? [...enabledIds, id] : enabledIds.filter((value) => value !== id);
      void withPin(
        enabled ? 'Adding a list changes what gets blocked.' : 'Turning a list off needs your PIN.',
        (pin) => saveConfig({ listIds: next }, pin)
      );
    },
    [enabledIds, saveConfig, withPin]
  );

  const addCustom = useCallback(() => {
    const url = customUrl.trim();
    if (!url.startsWith('http')) {
      Alert.alert('Enter a full URL', 'The list has to be reachable over http or https.');
      return;
    }
    const id = `custom-${Date.now().toString(36)}`;
    const title = customTitle.trim() || url.replace(/^https?:\/\//, '').slice(0, 32);
    setWorking(id);
    void withPin('Adding a blocklist is a protection change.', async (pin) => {
      await updateList(id, url, title);
      const current = config?.listIds ?? [];
      await saveConfig({ listIds: [...current, id] }, pin);
      setCustomUrl('');
      setCustomTitle('');
    }).finally(() => setWorking(null));
  }, [config?.listIds, customTitle, customUrl, saveConfig, updateList, withPin]);

  const refreshList = useCallback(
    (id: string, url: string, title: string) => {
      if (!url) {
        Alert.alert('No source', 'This list ships with the app and has no update source.');
        return;
      }
      setWorking(id);
      void updateList(id, url, title).finally(() => setWorking(null));
    },
    [updateList]
  );

  const deleteList = useCallback(
    (id: string, title: string) => {
      Alert.alert(`Delete "${title}"?`, 'The downloaded copy is removed from this device.', [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            void withPin('Removing a blocklist needs your PIN.', async (pin) => {
              const current = config?.listIds ?? [];
              if (current.includes(id)) {
                await saveConfig({ listIds: current.filter((value) => value !== id) }, pin);
              }
              await removeList(id);
            });
          },
        },
      ]);
    },
    [config?.listIds, removeList, saveConfig, withPin]
  );

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Text style={ui.sectionTitle}>Active lists</Text>
      <Card>
        {lists.length === 0 ? <EmptyState text="No lists yet." /> : null}
        {lists.map((item, index) => (
          <View key={item.id}>
            {index > 0 ? <View style={styles.divider} /> : null}
            <ToggleRow
              title={item.title}
              subtitle={
                item.installed
                  ? `${formatNumber(item.domains)} domains · updated ${formatDateTime(item.updatedAt)}`
                  : 'Not installed yet'
              }
              value={enabledIds.includes(item.id)}
              onValueChange={(next) => toggleList(item.id, next)}
              disabled={!item.installed || busy}
            />
            <View style={styles.actions}>
              <Button
                title={item.installed ? 'Refresh' : 'Install'}
                variant="ghost"
                icon={item.installed ? 'refresh' : 'download-outline'}
                loading={working === item.id}
                onPress={() =>
                  item.installed
                    ? refreshList(item.id, item.sourceUrl, item.title)
                    : void installList(item.id)
                }
                style={styles.actionButton}
              />
              {item.bundled ? (
                <Pill label="built in" tone="accent" />
              ) : (
                <Button
                  title="Delete"
                  variant="danger"
                  icon="trash-outline"
                  onPress={() => deleteList(item.id, item.title)}
                  style={styles.actionButton}
                />
              )}
            </View>
          </View>
        ))}
      </Card>

      <Text style={ui.sectionTitle}>More lists</Text>
      <Card>
        {SUGGESTED_LISTS.filter((item) => !installedIds.has(item.id)).length === 0 ? (
          <EmptyState text="Every suggested list is already installed." />
        ) : null}
        {SUGGESTED_LISTS.filter((item) => !installedIds.has(item.id)).map((item, index) => (
          <View key={item.id}>
            {index > 0 ? <View style={styles.divider} /> : null}
            <SettingRow
              title={item.title}
              subtitle={item.description}
              right={
                <Button
                  title="Add"
                  variant="primary"
                  icon="add"
                  loading={working === item.id}
                  onPress={() => {
                    setWorking(item.id);
                    void withPin('Adding a blocklist is a protection change.', async (pin) => {
                      await updateList(item.id, item.url, item.title);
                      const current = config?.listIds ?? [];
                      await saveConfig({ listIds: [...current, item.id] }, pin);
                    }).finally(() => setWorking(null));
                  }}
                />
              }
            />
            {item.heavy ? (
              <Text style={styles.note}>
                Large list: while it is loaded the app keeps roughly 50 MB of memory busy.
              </Text>
            ) : null}
          </View>
        ))}
      </Card>

      <Text style={ui.sectionTitle}>Add your own</Text>
      <Card>
        <Text style={styles.note}>
          Any hosts file or plain domain list works. The download is cleaned up into a plain list of
          domains on this device.
        </Text>
        <TextInput
          style={styles.input}
          placeholder="https://example.com/hosts.txt"
          placeholderTextColor={colors.faint}
          autoCapitalize="none"
          autoCorrect={false}
          value={customUrl}
          onChangeText={setCustomUrl}
        />
        <TextInput
          style={styles.input}
          placeholder="Name (optional)"
          placeholderTextColor={colors.faint}
          value={customTitle}
          onChangeText={setCustomTitle}
        />
        <Button title="Download and enable" onPress={addCustom} icon="cloud-download-outline" />
      </Card>

      <Text style={ui.sectionTitle}>Exceptions</Text>
      <Card>
        <SettingRow
          title="Allowlist"
          subtitle={
            config?.allowlist.length
              ? `${config.allowlist.length} domains are never blocked`
              : 'No domains are exempt yet'
          }
          onPress={() => router.push('/allowlist')}
          right={<Chevron />}
        />
      </Card>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { padding: space(4), paddingBottom: space(10) },
  divider: {
    backgroundColor: colors.border,
    height: StyleSheet.hairlineWidth,
    marginVertical: space(2),
  },
  actions: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: space(2),
    marginBottom: space(2),
  },
  actionButton: { paddingHorizontal: space(3), paddingVertical: space(2) },
  note: { color: colors.faint, fontSize: 12, lineHeight: 18, marginBottom: space(3) },
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
});
