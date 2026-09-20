# PornFree

An Android-only, open-source porn blocker built with Expo.
`https://github.com/kennethyork/PornFree` It filters DNS on the device, keeps
working offline, has no account, no server and no telemetry, and does not care which app is asking.

```
Shield  →  blocklists + allowlist  →  every DNS lookup on the phone  →  blocked or relayed
```

## What it actually does

PornFree runs a local `VpnService` that captures **only DNS**. Android routes the addresses of
public resolvers into the tunnel, so lookups are intercepted no matter which app asks for them or
whether the app hard-codes `8.8.8.8`. Each query is matched against your blocklists, blocked names
are answered locally, and everything else is relayed to a family-safe resolver you choose.

Because no general traffic enters the tunnel, there is no userspace TCP/IP stack, no throughput
cost and negligible battery impact. Requests to known DNS-over-HTTPS and DNS-over-TLS endpoints
are answered with an ICMP "port unreachable", which makes apps fail fast and fall back to the plain
DNS that *is* filtered.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the details.

## Honest limitations

A blocker that overpromises is worse than no blocker, so:

- **Blocking is by domain name.** A site reached by IP address, or served from a domain no list
  knows yet, gets through. The upstream family resolver is the second line of defence.
- **Android cannot put a password on the uninstall button.** The uninstall dialog belongs to the OS;
  no app can intercept it. PornFree's PIN protects everything *inside* the app, and real uninstall
  protection is available through Android's **device owner** mode, which has to be granted once over
  USB. See [docs/UNINSTALL-PROTECTION.md](docs/UNINSTALL-PROTECTION.md).
- **A custom encrypted-DNS setting bypasses filtering.** If you keep Android's *Private DNS* pointed
  at a hostname, or a browser's own DoH resolver, lookups can go somewhere PornFree cannot see.
  Set *Private DNS* to Off/Automatic and turn off "Use secure DNS" in Chrome and Firefox.
- **Never enable "Block connections without VPN".** PornFree is a DNS filter, not a full tunnel;
  lockdown mode would route all traffic into a tunnel that only understands DNS and take you offline.
- **It is not parental-control software.** Per-app rules, device profiles, remote management and
  tamper-proof installation are out of scope.

## Features

- DNS filtering for every app on the device, from a bundled 156,000-domain adult list
- Additional lists one tap away (OISD NSFW, and any hosts file or domain list by URL)
- Allowlist for domains a list is too aggressive about
- Five resolver presets, all of them family filters, so upstream filtering backs up the lists
- Optional blocking of encrypted-DNS bypass, with a bundled list of ~65 DoH/DoT endpoints
- Choose how blocked names answer: empty answer, `0.0.0.0`, or `NXDOMAIN`
- PIN (4-8 digits) mandatory from the first screen: the tunnel will not start without one, and the
  PIN is required to switch protection off, change lists, edit the allowlist, clear stats, or turn
  off uninstall protection
- Commitment lock: protection refuses to be switched off until a timer you set runs out
- Stats: per-day chart, top blocked domains, session and lifetime counters
- Restarts itself after a reboot when the VPN permission is still granted
- Optional device-owner mode: uninstall lock plus always-on VPN
- No analytics, no accounts, no network calls other than DNS and list downloads

## Screenshots

Build and run it — the UI is a dark, four-tab app: **Shield**, **Lists**, **Stats**, **Settings**.

## Requirements

| Tool | Version |
| --- | --- |
| Node.js | 20.19+ (or 22.13+, 24.3+) |
| JDK | 17 or 21 |
| Android SDK | platform 36, build-tools 36 |
| Android device or emulator | Android 7.0 (API 24) or newer |

## Build and install

This app contains native code (a Kotlin Expo module), so **Expo Go cannot run it**.

```sh
npm install

# Build the debug APK and install it on the connected device, in one step
npx expo run:android
```

Or produce an APK without a device attached:

```sh
npx expo prebuild -p android
cd android && ./gradlew assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release builds

Debug builds are signed with Android's debug key: fine for testing, useless for anything you want to
keep, because an app signed with the debug key can never be updated by a properly signed build. The
repository therefore ships a config plugin, [plugins/withReleaseSigning.js](plugins/withReleaseSigning.js),
which signs release builds with a real key whenever one is configured.

```sh
# One-time. Keep the keystore out of version control, and back it up: losing it means you can
# never update an installed copy again.
keytool -genkeypair -v -storetype PKCS12 -keystore credentials/pornfree-release.jks \
  -alias pornfree -keyalg RSA -keysize 2048 -validity 10000
cp credentials/keystore.properties.example credentials/keystore.properties   # then fill it in

npm run android:release
# -> android/app/build/outputs/apk/release/app-release.apk
```

With no `credentials/keystore.properties` present the generated project is left exactly as Expo
produced it, so a fresh clone still builds a release APK (signed with the debug key). `credentials/`
is gitignored; only the `.example` template is committed.

The `android:release` script builds `arm64-v8a` and `armeabi-v7a`, which covers every real phone and
keeps the download small. Drop the `-PreactNativeArchitectures` flag to include `x86`/`x86_64` for
emulators.

### Builds from CI

[.github/workflows/ci.yml](.github/workflows/ci.yml) has two jobs. `verify` runs the type check, the
JS bundle, a prebuild and the unit tests on every push and pull request. `apk` builds the release
APK and attaches it to the run, so a testable build needs nothing but a push: open the run under
**Actions** and download it from **Artifacts**.

It signs with the debug key unless you add two repository secrets, which is enough for testing and
nothing more:

| Secret | Contents |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 credentials/pornfree-release.jks` |
| `ANDROID_KEYSTORE_PROPERTIES` | the four lines of `credentials/keystore.properties`, with `storeFile=credentials/pornfree-release.jks` |

With those in place the job signs with your real key, and the APK it produces can update an installed
copy. Note that an app signed with the debug key can never be updated by one signed with the release
key; uninstall and reinstall when you switch.

The `android/` directory is generated by `expo prebuild` and is not checked in. Everything specific
to this app lives in `modules/pornfree-vpn/`.

## First run

The app opens on a three-step setup and does not let you past it without a PIN. That is not a
nag screen: native code refuses to create the tunnel while no PIN is set, so "no PIN" and "no
protection" are the same state by construction.

1. **Choose a PIN** (4-8 digits, entered twice). It cannot be recovered, and clearing the app data
   is the only reset.
2. **Grant the VPN permission.** Android shows its one-time consent dialog.
3. **Finish.** The bundled adult list is already installed and filtering starts immediately.

Two consequences worth knowing:

- Removing the PIN stops protection and releases the uninstall lock. The setup screen comes back
  until a new PIN exists.
- Reaching the end of setup does not require starting protection: choosing *Not now* leaves the PIN
  in place and the app usable, with the shield ready when you want it.

Optionally set up uninstall protection (**Settings → Uninstall protection**) and a commitment lock
(**Settings → PIN & commitment**).

## Development

```sh
npm run typecheck                                    # TypeScript
cd android && ./gradlew :pornfree-vpn:testDebugUnitTest   # Kotlin unit tests (19 tests)
```

The DNS wire format, the matcher and the list parser are pure Kotlin with no Android imports, so
they are covered by JVM unit tests: `modules/pornfree-vpn/android/src/test/`.

## Project layout

```
app/                              screens (expo-router)
  (tabs)/index.tsx                shield, today's counters, recent blocks
  (tabs)/lists.tsx                blocklists, custom lists, allowlist
  (tabs)/stats.tsx                history and top domains
  (tabs)/settings.tsx             resolver, blocking mode, network, behaviour
  settings/security.tsx           PIN and commitment lock
  settings/uninstall.tsx          device owner setup, uninstall lock
src/                              app logic: state, PIN, SQLite history, theme
modules/pornfree-vpn/           the local Expo module (Kotlin)
  android/src/main/java/...       VpnService, DNS/IP wire format, matcher, store
  android/src/main/assets/        bundled blocklists
  android/src/test/               JVM unit tests
docs/                             architecture and uninstall-protection notes
```

## Privacy

Everything happens on the device.

- **Leaves the device:** the DNS queries themselves, relayed to the resolver you picked, and
  blocklist downloads from URLs you added. Nothing else, ever.
- **Stored on the device:** your settings, the list files, a rolling seven-day log of blocked
  domain names, daily counters, and the SHA-256 of your PIN (the PIN itself is never stored).
- **Not collected:** which app made a request, page contents, identifiers, crash reports, analytics.

Clearing the app's data resets all of it.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Setup will not let me continue | A PIN is required; there is no way past that screen, by design. |
| Protection stops after a while | Exclude PornFree from battery optimisation. |
| A site still loads | Add it to a custom list; check Private DNS is Off/Automatic and browser DoH is disabled. |
| Some app broke | Add its domain to the allowlist, or switch the resolver preset. |
| Nothing loads at all | Turn off "Block connections without VPN" in the system VPN settings. |
| DNS feels slow | Your upstream resolver is slow; pick a closer preset in Settings. |

## Contributing

```sh
git clone https://github.com/kennethyork/PornFree.git
cd PornFree
npm install
npx expo run:android
```

Issues and pull requests are welcome at <https://github.com/kennethyork/PornFree>. Please keep the
promises honest: if a change makes the app claim protection it cannot deliver, it will not be
merged.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
