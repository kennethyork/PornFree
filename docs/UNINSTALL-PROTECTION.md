# Uninstall protection

## The short version

**Android does not let an app ask for a password when it is uninstalled.** The uninstall flow belongs
to the launcher and to Settings, and no public API lets an app intervene in it. Anyone claiming
otherwise in an app description is describing something that no longer exists:

- Before Android 7, an *active device admin* blocked its own uninstall. That power was removed for
  security reasons, and `dpm remove-active-admin` reflects it: device admin is now only about
  policies, not about protecting the app itself.
- `DevicePolicyManager.setUninstallBlocked()` still exists and still works, but it may only be called
  by a **device owner** or **profile owner**. That status is not something an app can grant itself.

So BlockPorna offers the one mechanism that is real, and is explicit about its cost: a one-time
device owner setup over USB, after which the uninstall lock is enforced by Android itself and can
only be released from inside the app, with your PIN.

## What the lock gives you

With device owner mode active and the lock on:

- Removing BlockPorna from the home screen, from Settings, or through the Play Store is refused by
  Android while the lock is on.
- Switching the lock off, releasing device owner mode, or changing always-on VPN all require your PIN
  (verified in native code, not just in the UI).
- Removing the PIN also releases the lock, so there is never a lock without a password.
- The lock cannot be switched *on* at all until a PIN exists.

Always-on VPN can be enabled at the same time, so filtering comes back by itself after a reboot
without anyone opening the app. Lockdown mode is never enabled: BlockPorna only routes DNS, and
lockdown would cut off everything else.

## What it does not give you

Be clear-eyed about this, because a false sense of security is worse than none:

- **Factory reset** always removes the app and everything on the device.
- **Recovery mode** and flashing do whatever they like.
- **A computer with adb and developer options enabled** can run
  `adb shell dpm remove-active-admin <component>` and then uninstall normally. If you want that door
  closed, turn off USB debugging after setup.
- **Safe mode** prevents third-party apps from running; the lock does not make the phone unusable
  enough to be a real deterrent.
- Anyone who knows your PIN can release everything. Use a PIN you do not use anywhere else.

The honest framing: this is a speed bump for the version of you that wants out at 1 a.m., not a
jail for the device.

## Setup

1. **Set a PIN** in *Settings → PIN & commitment*. The lock refuses to switch on without one.
2. On the phone: *Settings → About phone → tap Build number seven times*, then enable **USB
   debugging** in Developer options.
3. **Remove every account** from the phone (*Settings → Passwords & accounts*). Android refuses to
   grant device owner status while accounts exist. You can add them back afterwards.
4. Connect the phone to a computer with `adb` installed and run:

   ```sh
   adb shell dpm set-device-owner dev.blockporna.app/expo.modules.blockpornavpn.BlockPornaDeviceAdminReceiver
   ```

   If adb complains about accounts, remove the remaining ones and try again. Some devices also want
   `--user 0` appended.

5. Back in BlockPorna: *Settings → Uninstall protection → Check again*, then switch on **Block
   uninstall** (and **Always-on VPN** if you want filtering to survive a reboot).

## Removing device owner mode

From inside the app (needs your PIN): *Settings → Uninstall protection → Release device owner mode*.

From a computer, if you would rather not use the app:

```sh
adb shell dpm remove-active-admin dev.blockporna.app/expo.modules.blockpornavpn.BlockPornaDeviceAdminReceiver
```

Both paths release the uninstall lock and the always-on VPN setting.

## Why not profile owner, or a "parental control" API?

- **Profile owner** requires a work profile or a managed device; it is a heavier setup than most
  people want on a personal phone. BlockPorna supports it if it is already provisioned, since the
  same lock API applies.
- **Google Play's parental controls / Google Family Link** are managed by Google's own apps on
  managed accounts. They cannot be wired into a third-party blocker, and they would mean handing the
  filtering decisions to a service rather than to this app.
- **Accessibility-service tricks** that detect and block the uninstall UI are fragile, are treated as
  malware behaviour by Play policy, and break the moment the launcher or Settings changes. BlockPorna
  does not do this.
