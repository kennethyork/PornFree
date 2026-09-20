import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useProtection } from '../state/protection';
import { useSecurity } from '../state/security';
import { colors, radius, space } from '../theme';
import { Button, Card } from './ui';

type Phase = 'welcome' | 'working';

/**
 * The first-run setup, and the app's front door.
 *
 * It cannot be dismissed until a PIN exists, because the PIN is the only thing standing between a
 * moment of weakness and the settings. Native code enforces the same rule: the tunnel refuses to
 * start while no PIN is set, so this is a real gate rather than a screen that can be navigated past.
 */
export function SetupWizard() {
  const insets = useSafeAreaInsets();
  const { hasPin, acquirePin, completeOnboarding, onboarded } = useSecurity();
  const { status, start, requestPermission, error, dismissError, busy } = useProtection();
  // Someone who removed their PIN goes straight to choosing a new one.
  const [phase, setPhase] = useState<Phase>(onboarded ? 'working' : 'welcome');


  const stage: 'welcome' | 'pin' | 'start' | 'done' =
    phase === 'welcome' ? 'welcome' : !hasPin ? 'pin' : status?.running ? 'done' : 'start';

  const stepNumber = { welcome: 1, pin: 2, start: 3, done: 3 }[stage];

  const choosePin = async () => {
    await acquirePin(
      'This PIN guards everything in PornFree. Write it down somewhere you can find it again: there is no way to recover it.',
      'create'
    );
  };

  const enable = async () => {
    if (!status?.permissionGranted) {
      const granted = await requestPermission();
      if (!granted) return;
    }
    await start();
  };

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + space(8) }]}
    >
      <View style={styles.brand}>
        <Ionicons name="shield-checkmark" size={34} color={colors.accent} />
        <Text style={styles.brandName}>PornFree</Text>
        <Text style={styles.brandTag}>Setup takes under a minute</Text>
      </View>

      <View style={styles.dots}>
        {[1, 2, 3].map((index) => (
          <View
            key={index}
            style={[styles.dot, index <= stepNumber ? styles.dotActive : null]}
          />
        ))}
      </View>

      {error ? (
        <Card style={styles.errorCard}>
          <Text style={styles.errorText}>{error}</Text>
          <Button title="Dismiss" variant="ghost" onPress={dismissError} />
        </Card>
      ) : null}

      {stage === 'welcome' ? (
        <Card>
          <Text style={styles.title}>Filtering that no app can ignore</Text>
          <Text style={styles.body}>
            PornFree answers DNS on this phone, so every app is covered without any setup inside
            them. Nothing is sent anywhere except the DNS lookups themselves.
          </Text>
          <View style={styles.bullets}>
            <Bullet icon="list" text="156,000 adult domains are already blocked, offline." />
            <Bullet icon="key" text="A PIN is required, from this first screen onwards." />
            <Bullet icon="stats-chart" text="You see what was blocked, and how much." />
          </View>
          <Text style={styles.caveat}>
            Honest limits: a site reached by IP address, or one that is not on any list yet, can get
            through. Android will not let any app put a password on the uninstall button either;
            that needs the optional device-owner setup later on.
          </Text>
          <Button title="Get started" icon="arrow-forward" onPress={() => setPhase('working')} />
        </Card>
      ) : null}

      {stage === 'pin' ? (
        <Card>
          <Text style={styles.title}>Choose your PIN</Text>
          <Text style={styles.body}>
            The PIN is what stops protection from being switched off on a bad evening. It is asked
            for whenever something weakens filtering: turning protection off, editing lists,
            changing the resolver, clearing your stats or releasing the uninstall lock.
          </Text>
          <View style={styles.bullets}>
            <Bullet icon="information-circle" text="4 to 8 digits, entered twice." />
            <Bullet icon="warning" text="It cannot be recovered. Clearing the app data is the only reset." />
          </View>
          <Button title="Set a PIN" icon="key-outline" onPress={() => void choosePin()} />
        </Card>
      ) : null}

      {stage === 'start' ? (
        <Card>
          <Text style={styles.title}>Turn on protection</Text>
          <Text style={styles.body}>
            Android will ask once for permission to create a VPN. PornFree uses it only to see DNS:
            the tunnel carries the addresses of public resolvers and nothing else, so your normal
            traffic is untouched.
          </Text>
          <View style={styles.statusRow}>
            <Ionicons
              name={status?.permissionGranted ? 'checkmark-circle' : 'ellipse-outline'}
              size={18}
              color={status?.permissionGranted ? colors.success : colors.faint}
            />
            <Text style={styles.statusText}>
              {status?.permissionGranted ? 'VPN permission granted' : 'VPN permission not granted yet'}
            </Text>
          </View>
          <Button
            title="Allow and start filtering"
            icon="shield"
            loading={busy}
            onPress={() => void enable()}
          />
          <View style={styles.spaced}>
            <Button
              title="Not now"
              variant="ghost"
              onPress={() => void completeOnboarding()}
            />
          </View>
          <Text style={styles.hint}>
            You can start it later from the shield on the home screen. Your PIN stays in place either
            way.
          </Text>
        </Card>
      ) : null}

      {stage === 'done' ? (
        <Card>
          <View style={styles.doneRow}>
            <Ionicons name="checkmark-circle" size={30} color={colors.success} />
            <Text style={styles.title}>You are protected</Text>
          </View>
          <Text style={styles.body}>
            The app works like any other app from here. Two things worth knowing about:
          </Text>
          <View style={styles.bullets}>
            <Bullet
              icon="eye-off-outline"
              text="Settings → Visibility hides PornFree's icon, if you would rather not be reminded that it is there."
            />
            <Bullet
              icon="hourglass-outline"
              text="Settings → PIN & commitment adds a lock that refuses to switch filtering off until a timer runs out."
            />
          </View>
          <Button title="Finish" icon="checkmark" onPress={() => void completeOnboarding()} />
        </Card>
      ) : null}
    </ScrollView>
  );
}

function Bullet({ icon, text }: { icon: keyof typeof Ionicons.glyphMap; text: string }) {
  return (
    <View style={styles.bulletRow}>
      <Ionicons name={icon} size={16} color={colors.accent} />
      <Text style={styles.bulletText}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { backgroundColor: colors.bg, flex: 1 },
  content: { paddingBottom: space(12), paddingHorizontal: space(4) },
  brand: { alignItems: 'center', marginBottom: space(6) },
  brandName: { color: colors.text, fontSize: 24, fontWeight: '800', marginTop: space(2) },
  brandTag: { color: colors.muted, fontSize: 13, marginTop: space(1) },
  dots: { flexDirection: 'row', gap: space(2), justifyContent: 'center', marginBottom: space(5) },
  dot: {
    backgroundColor: colors.border,
    borderRadius: radius.pill,
    height: 6,
    width: 28,
  },
  dotActive: { backgroundColor: colors.accent },
  title: { color: colors.text, fontSize: 19, fontWeight: '700' },
  body: { color: colors.muted, fontSize: 13.5, lineHeight: 20, marginTop: space(3) },
  bullets: { gap: space(2.5), marginBottom: space(4), marginTop: space(4) },
  bulletRow: { alignItems: 'flex-start', flexDirection: 'row', gap: space(2.5) },
  bulletText: { color: colors.muted, flex: 1, fontSize: 12.5, lineHeight: 19 },
  caveat: {
    color: colors.faint,
    fontSize: 11.5,
    lineHeight: 17,
    marginBottom: space(4),
  },
  statusRow: {
    alignItems: 'center',
    backgroundColor: colors.cardAlt,
    borderRadius: radius.md,
    flexDirection: 'row',
    gap: space(2.5),
    marginBottom: space(4),
    marginTop: space(4),
    padding: space(3),
  },
  statusText: { color: colors.text, fontSize: 13 },
  doneRow: { alignItems: 'center', flexDirection: 'row', gap: space(3) },
  hint: { color: colors.faint, fontSize: 11.5, lineHeight: 17, marginTop: space(3) },
  spaced: { marginTop: space(3) },
  errorCard: { borderColor: colors.danger, marginBottom: space(4) },
  errorText: { color: colors.danger, fontSize: 13, marginBottom: space(3) },
});
