#!/usr/bin/env bash
# Publish a signed release APK to the update channel (agento.ceo/dl, direct
# download, no gate). See docs/RELEASE.md.
#
# Copies the APK to the web root as:
#   /dl/agento-<version>.apk   (immutable, versioned)
#   /dl/agento-beta.apk        (stable "latest" URL used by the page + mailer)
#   /dl/latest.json            ({version, file, size_mb, date} — page shows it)
#
# Usage: scripts/publish-apk.sh [path/to.apk]   (default: the release build)
set -euo pipefail
cd "$(dirname "$0")/.."
# Direct channel ships the RELEASE build signed with the upload key: Android
# Developer Verification requires sideloaded APKs to carry a registered
# signing certificate, and one key for both channels means one fingerprint
# to register. Build: docs/RELEASE.md → ./gradlew assembleRelease
# File names on the node stay agento-<ver>.apk / agento-beta.apk: installed
# business apps resolve updates through latest.json, which points at them.
APK=${1:-app/build/outputs/apk/release/app-release.apk}
NODE=${NODE:-node.yaya.tech}
WEBROOT=${WEBROOT:-docker/www/agento/dl}
[ -f "$APK" ] || { echo "no APK at $APK — build first"; exit 1; }

VER=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
CODE=$(sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
[ -n "$VER" ] && [ -n "$CODE" ] || { echo "could not read versionName/versionCode"; exit 1; }
SIZE_MB=$(awk "BEGIN{printf \"%.1f\", $(stat -c %s "$APK")/1048576}")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
DATE=$(date +%F)
# Update channel policy (read by the APK's UpdateCheck):
#   MIN_VERSION_CODE — builds below it get a BLOCKING "update required" banner
#                      (default: keep whatever the node already publishes, else 0).
#   RELEASE_NOTES    — one line shown in the banner / notification.
MIN=${MIN_VERSION_CODE:-$(ssh "$NODE" "python3 -c \"import json;print(json.load(open('$WEBROOT/latest.json')).get('minVersionCode',0))\" 2>/dev/null" || echo 0)}
MIN=${MIN:-0}
NOTES=${RELEASE_NOTES:-}
JSON=$(python3 - "$VER" "$CODE" "$MIN" "$SIZE_MB" "$SHA" "$DATE" "$NOTES" <<'PY2'
import json, sys
v, code, mn, size, sha, date, notes = sys.argv[1:]
print(json.dumps({
    "version": v, "versionCode": int(code), "minVersionCode": int(mn),
    "file": f"agento-{v}.apk", "url": f"https://agento.ceo/dl/agento-{v}.apk",
    "size_mb": float(size), "sha256": sha, "date": date, "notes": notes,
}))
PY2
)

scp -q "$APK" "$NODE:$WEBROOT/agento-$VER.apk.tmp"
ssh "$NODE" "cd $WEBROOT && mv -f agento-$VER.apk.tmp agento-$VER.apk && cp -f agento-$VER.apk agento-beta.apk.tmp && mv -f agento-beta.apk.tmp agento-beta.apk && cat > latest.json.tmp && mv -f latest.json.tmp latest.json && ls -la" <<<"$JSON"
echo "published v$VER (code $CODE, min $MIN) → https://agento.ceo/dl/agento-beta.apk"
echo "$JSON"
