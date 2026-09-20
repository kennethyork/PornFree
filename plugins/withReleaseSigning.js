const { withAppBuildGradle } = require('expo/config-plugins');

/**
 * Signs release builds with a real key when one is configured.
 *
 * The generated Android project signs `release` with the debug keystore, which is fine for a local
 * test but wrong for anything you intend to keep: an app signed with the debug key can never be
 * updated by a properly signed build, because the signatures do not match.
 *
 * This plugin only injects the *loading* of `credentials/keystore.properties`; the passwords stay
 * in that gitignored file, so rotating them never needs another prebuild. With no properties file
 * present the project is left exactly as Expo generated it, so a fresh clone still builds.
 */

const PROPERTIES_LOADING = `
// --- injected by plugins/withReleaseSigning.js ---
def pfKeystoreProps = new Properties()
def pfKeystorePropsFile = new File(projectRoot, "credentials/keystore.properties")
if (pfKeystorePropsFile.exists()) {
    pfKeystorePropsFile.withInputStream { pfKeystoreProps.load(it) }
}
def pfHasReleaseKeystore = pfKeystoreProps.getProperty("storeFile") != null
// --- end injection ---
`;

const SIGNING_CONFIG = `
        if (pfHasReleaseKeystore) {
            release {
                storeFile new File(projectRoot, pfKeystoreProps.getProperty("storeFile"))
                storePassword pfKeystoreProps.getProperty("storePassword")
                keyAlias pfKeystoreProps.getProperty("keyAlias")
                keyPassword pfKeystoreProps.getProperty("keyPassword")
            }
        }
`;

module.exports = function withReleaseSigning(config) {
  return withAppBuildGradle(config, (cfg) => {
    if (cfg.modResults.language !== 'groovy') {
      throw new Error('withReleaseSigning expects a Groovy app/build.gradle');
    }

    let contents = cfg.modResults.contents;
    if (contents.includes('pfHasReleaseKeystore')) return cfg; // already applied

    const fail = (what) => {
      throw new Error(
        `withReleaseSigning could not find ${what} in android/app/build.gradle. The Expo template ` +
          'probably changed: update this plugin rather than shipping a debug-signed release.'
      );
    };

    const anchor = 'def projectRoot = rootDir.getAbsoluteFile().getParentFile().getAbsolutePath()';
    if (!contents.includes(anchor)) fail('the projectRoot declaration');
    contents = contents.replace(anchor, anchor + PROPERTIES_LOADING);

    if (!contents.includes('    signingConfigs {')) fail('the signingConfigs block');
    contents = contents.replace('    signingConfigs {', `    signingConfigs {${SIGNING_CONFIG}`);

    const releaseSigning = `            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig signingConfigs.debug`;
    if (!contents.includes(releaseSigning)) fail('the release signingConfig line');
    contents = contents.replace(
      releaseSigning,
      `            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig pfHasReleaseKeystore ? signingConfigs.release : signingConfigs.debug`
    );

    cfg.modResults.contents = contents;
    return cfg;
  });
};
