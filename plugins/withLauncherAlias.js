const { withAndroidManifest } = require('expo/config-plugins');

/**
 * Moves the MAIN/LAUNCHER intent filter off MainActivity and onto an <activity-alias>.
 *
 * This is what makes hiding the icon safe. An app whose *launcher activity* is disabled cannot be
 * started by anything at all - not by its own notification, not by a deep link, not by adb - which
 * is exactly how the first version of this feature locked people out of their own app.
 *
 * With the filter on an alias, hiding disables the alias only:
 *
 *   - the launcher entry disappears, which is the whole point
 *   - MainActivity stays enabled, so every way back in keeps working while the icon is gone
 *   - turning hiding off is a single component toggle with no restart and no races
 *
 * The alias copies the attributes that decide how the activity behaves (theme, launch mode,
 * orientation, config handling) so launching through it is indistinguishable from before.
 */

const LAUNCHER_FILTER = () => ({
  action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }],
  category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }],
});

const isLauncherFilter = (filter) => {
  const actions = (filter.action ?? []).map((entry) => entry.$?.['android:name']);
  const categories = (filter.category ?? []).map((entry) => entry.$?.['android:name']);
  return (
    actions.includes('android.intent.action.MAIN') &&
    categories.includes('android.intent.category.LAUNCHER')
  );
};

// Attributes that describe how the activity is presented and behaves. Anything unset on an alias
// falls back to the application, not to the target activity, so they have to be copied explicitly.
const INHERITED_ATTRIBUTES = [
  'android:theme',
  'android:launchMode',
  'android:configChanges',
  'android:screenOrientation',
  'android:windowSoftInputMode',
  'android:label',
  'android:icon',
  'android:roundIcon',
  'android:taskAffinity',
  'android:allowTaskReparenting',
  'android:finishOnTaskLaunch',
  'android:hardwareAccelerated',
  'android:resizeableActivity',
  'android:excludeFromRecents',
];

module.exports = function withLauncherAlias(config) {
  return withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults.manifest;
    const application = manifest.application?.[0];
    if (!application) throw new Error('withLauncherAlias: no <application> in the manifest');

    const pkg = cfg.android?.package ?? manifest.$?.['package'];
    if (!pkg) {
      throw new Error(
        'withLauncherAlias: the Android package name is unknown (set android.package in app.json)'
      );
    }

    const aliasName = `${pkg}.LauncherAlias`;
    const aliases = application['activity-alias'] ?? [];
    if (aliases.some((alias) => alias.$?.['android:name'] === aliasName)) {
      return cfg; // already applied
    }

    const activities = application.activity ?? [];
    const relative = '.MainActivity';
    const main = activities.find((activity) => {
      const name = activity.$?.['android:name'];
      return name === relative || name === `${pkg}.MainActivity`;
    });
    if (!main) {
      throw new Error(
        `withLauncherAlias: could not find MainActivity in the manifest (found: ${activities
          .map((activity) => activity.$?.['android:name'])
          .join(', ')})`
      );
    }

    const filters = main['intent-filter'] ?? [];
    const remaining = filters.filter((filter) => !isLauncherFilter(filter));
    if (remaining.length === filters.length) {
      throw new Error('withLauncherAlias: MainActivity has no MAIN/LAUNCHER intent filter to move');
    }
    // Keep the deep link filter that expo-router needs.
    main['intent-filter'] = remaining;

    const target = main.$['android:name'] === relative ? `${pkg}${relative}` : main.$['android:name'];
    const aliasAttributes = {
      'android:name': aliasName,
      'android:targetActivity': target,
      'android:enabled': 'true',
      'android:exported': 'true',
    };
    for (const attribute of INHERITED_ATTRIBUTES) {
      if (main.$[attribute] !== undefined) aliasAttributes[attribute] = main.$[attribute];
    }

    application['activity-alias'] = [
      ...aliases,
      { $: aliasAttributes, 'intent-filter': [LAUNCHER_FILTER()] },
    ];
    return cfg;
  });
};
