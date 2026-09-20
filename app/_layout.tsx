import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { ProtectionProvider } from '../src/state/protection';
import { SecurityProvider } from '../src/state/security';
import { colors } from '../src/theme';

export default function RootLayout() {
  return (
    <SecurityProvider>
      <ProtectionProvider>
        <StatusBar style="light" />
        <Stack
          screenOptions={{
            contentStyle: { backgroundColor: colors.bg },
            headerStyle: { backgroundColor: colors.bg },
            headerTintColor: colors.text,
            headerTitleStyle: { fontWeight: '700' },
          }}
        >
          <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
          <Stack.Screen name="settings/security" options={{ title: 'PIN & commitment' }} />
          <Stack.Screen name="settings/uninstall" options={{ title: 'Uninstall protection' }} />
          <Stack.Screen name="logs" options={{ title: 'Blocked log' }} />
          <Stack.Screen name="allowlist" options={{ title: 'Allowlist' }} />
        </Stack>
      </ProtectionProvider>
    </SecurityProvider>
  );
}
