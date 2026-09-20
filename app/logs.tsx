import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, View } from 'react-native';

import { EmptyState } from '../src/components/ui';
import { formatRelative } from '../src/lib/format';
import { recentBlocked } from '../src/lib/history';
import { colors, radius, space } from '../src/theme';

type Row = { id: number; domain: string; at: number };

export default function LogsScreen() {
  const [rows, setRows] = useState<Row[]>([]);
  const [query, setQuery] = useState('');

  useEffect(() => {
    void recentBlocked(300)
      .then(setRows)
      .catch(() => setRows([]));
  }, []);

  const filtered = query
    ? rows.filter((row) => row.domain.includes(query.toLowerCase()))
    : rows;

  return (
    <View style={styles.screen}>
      <TextInput
        style={styles.search}
        placeholder="Filter domains"
        placeholderTextColor={colors.faint}
        autoCapitalize="none"
        autoCorrect={false}
        value={query}
        onChangeText={setQuery}
      />
      <FlatList
        data={filtered}
        keyExtractor={(item) => String(item.id)}
        contentContainerStyle={styles.list}
        ListEmptyComponent={<EmptyState text="No blocked lookups recorded yet." />}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <Ionicons name="close-circle" size={16} color={colors.danger} />
            <Text style={styles.domain} numberOfLines={1}>
              {item.domain}
            </Text>
            <Text style={styles.time}>{formatRelative(item.at)}</Text>
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  search: {
    backgroundColor: colors.card,
    borderColor: colors.border,
    borderRadius: radius.md,
    borderWidth: 1,
    color: colors.text,
    margin: space(4),
    paddingHorizontal: space(3),
    paddingVertical: space(3),
  },
  list: { paddingBottom: space(8), paddingHorizontal: space(4) },
  row: { alignItems: 'center', flexDirection: 'row', gap: space(2.5), paddingVertical: space(3) },
  domain: { color: colors.text, flex: 1, fontSize: 13.5 },
  time: { color: colors.faint, fontSize: 11.5 },
});
