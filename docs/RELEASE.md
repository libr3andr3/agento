# Releasing

## Versions

`app/build.gradle.kts` → `versionCode` (integer, always goes up) and
`versionName` (`major.minor.patch`). Installed phones decide whether to
update by `versionCode` alone, so every published build needs a new one.

## Signed build

The upload keystore never enters the repo. Export its location and
passwords (a `keystore.env` you `source` is the usual way):

```bash
export AGENTO_KEYSTORE=~/.agento/upload-keystore.jks
export AGENTO_KEYSTORE_PASS=…
export AGENTO_KEY_ALIAS=agento-upload
export AGENTO_KEY_PASS=…
./gradlew assembleDirectRelease
# → app/build/outputs/apk/direct/release/app-direct-release.apk  (R8-minified, shrunk, signed)
./gradlew bundlePlayRelease
# → app/build/outputs/bundle/playRelease/app-play-release.aab      (Google Play, docs/PLAY.md)
```

`direct` and `play` are the two channels (flavors) of the one app; see
`app/build.gradle.kts`. Without these variables the release tasks still
compile but produce unsigned artifacts — fine for CI, not installable next
to a signed one.

Check the signature before publishing (the gateway pins this digest as
`EXPECTED_SIGNERS`, and `scripts/verify-apk.sh` checks it on download):

```bash
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/direct/release/app-direct-release.apk
# Signer #1 certificate SHA-256 digest: 0204f2e455438244720aa79c9421e70927d957259cc77ce95b69148a44a35df2
```

## The update channel

The app is distributed from `https://agento.ceo/dl/`, not from Play. Three
files there drive updates (`UpdateCheck.kt`):

| file | what |
|---|---|
| `agento-<version>.apk` | immutable, one per version |
| `agento-beta.apk` | always the latest (the landing page links it) |
| `latest.json` | `{version, versionCode, minVersionCode, file, url, size_mb, sha256, date, notes}` |

Installed apps fetch `latest.json` when the dashboard opens and when the
listener reconnects. `versionCode` newer than installed → a banner and a
notification; installed below `minVersionCode` → the banner blocks until
the owner updates (use this when the core's API changes). The APK is
downloaded with `DownloadManager`, its `sha256` verified, then handed to the
package installer.

`scripts/publish-apk.sh` uploads the APK and writes `latest.json`:

```bash
RELEASE_NOTES="Cobros: nuevas billeteras" scripts/publish-apk.sh            # keeps minVersionCode
MIN_VERSION_CODE=51 RELEASE_NOTES="…" scripts/publish-apk.sh             # forces old builds to update
NODE=… WEBROOT=… scripts/publish-apk.sh path/to/app-release.apk           # another host / path
```

Then verify the download the way a careful user would (hash, zip, signer,
badging) and install it on a phone:

```bash
scripts/verify-apk.sh https://agento.ceo/dl/latest.json --install
```

## Checklist

- [ ] `versionCode` bumped; `versionName` matches what you will announce
- [ ] `./gradlew assembleDebug lintDebug` green (CI does this on every push)
- [ ] release APK signed by the upload key (digest above)
- [ ] installed on a real phone: sign in, register, the interview, one
      WhatsApp message answered, one Yape notification recorded
- [ ] `latest.json` published; `scripts/verify-apk.sh` passes
- [ ] if the core's API changed: `minVersionCode` raised

## Refreshing the agent core

`app/src/main/jniLibs/<abi>/libagento_core.so` is a prebuilt of the core
crate (private). To ship a new core: build it for the three ABIs, replace
the three files, update `app/src/main/jniLibs/CORE.md` (version, source
commit, sha256 of each file), and if `assets/schemas/` changed in the core,
copy those too. `AgentoCore.installSchemas` re-copies schemas whenever
`versionCode` changes, so bump the version.
