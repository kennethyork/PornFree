import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import type { ReactNode } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';

import { SetupWizard } from '../src/components/setup-wizard';
import { ProtectionProvider } from '../src/state/protection';
import { SecurityProvider, useSecurity } from '../src/state/security';
import { colors, space } from '../src/theme';

/**
 * The app is unusable until the setup is done, and the setup is not done until a PIN exists.
 * Native code enforces the same rule for the tunnel itself, so no screen here is load bearing
 * on its own.
 */
function ProtectionGate({ children }: { children: ReactNode }) {
  const { ready, hasPin, onboarded } = useSecurity();

  if (!ready) {
    return (
      <View style={styles.boot}>
        <ActivityIndicator color={colors.accent} />
        <Text style={styles.bootText}>Starting PornFree…</Text>
      </View>
    );
  }

  if (!hasPin || !onboarded) return <SetupWizard />;

  return <>{children}</>;
}

export default function RootLayout() {
  return (
    <SecurityProvider>
      <ProtectionProvider>
        <StatusBar style="light" />
        <ProtectionGate>
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
        </ProtectionGate>
      </ProtectionProvider>
    </SecurityProvider>
  );
}

const styles = StyleSheet.create({
  boot: {
    alignItems: 'center',
    backgroundColor: colors.bg,
    flex: 1,
    justifyContent: 'center',
  },
  bootText: { color: colors.muted, fontSize: 13, marginTop: space(4) },
});
