#!/usr/bin/env bash
# Trust, but verify: fetch the manifest and the APK it points at, and check
# everything a careful person would before sideloading:
#   1. sha256 of the download matches the manifest
#   2. the zip is intact (unzip -t)
#   3. the APK signature verifies and the signer is OUR upload certificate
#   4. package id / versionCode / versionName from the badging match
#   5. (optional) install on the connected phone and read back the version
#
#   scripts/verify-apk.sh [manifest-url] [--install]      (docs/RELEASE.md)
set -euo pipefail
MANIFEST=${1:-https://agento.ceo/dl/latest.json}; shift || true
INSTALL=0; for a in "$@"; do [ "$a" = --install ] && INSTALL=1; done
EXPECTED_SIGNER=${EXPECTED_SIGNER:-0204f2e455438244720aa79c9421e70927d957259cc77ce95b69148a44a35df2}
EXPECTED_PKG=${EXPECTED_PKG:-yaya.tech.agento.business}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
echo "== manifest $MANIFEST"
curl -fsSL "$MANIFEST" -o "$TMP/latest.json"
python3 -c "import json; v=json.load(open('$TMP/latest.json')); print('   version', v['version'], 'code', v['versionCode'], 'min', v['minVersionCode'], 'built on', v.get('builtOn','?'))"
URL=$(python3 -c "import json; print(json.load(open('$TMP/latest.json'))['url'])")
SHA=$(python3 -c "import json; print(json.load(open('$TMP/latest.json'))['sha256'])")
CODE=$(python3 -c "import json; print(json.load(open('$TMP/latest.json'))['versionCode'])")
VER=$(python3 -c "import json; print(json.load(open('$TMP/latest.json'))['version'])")
echo "== download $URL"
curl -fsSL "$URL" -o "$TMP/app.apk"; ls -l "$TMP/app.apk" | awk '{print "   bytes", $5}'
echo "== 1. sha256"; GOT=$(sha256sum "$TMP/app.apk" | cut -d' ' -f1); [ "$GOT" = "$SHA" ] && echo "   OK $GOT" || { echo "   MISMATCH manifest=$SHA got=$GOT"; exit 1; }
echo "== 2. zip integrity"; unzip -tq "$TMP/app.apk" | sed 's/^/   /'
echo "== 3. signature"; "$BT/apksigner" verify --print-certs "$TMP/app.apk" > "$TMP/sig.txt"
DIGEST=$(grep -i 'SHA-256 digest' "$TMP/sig.txt" | head -1 | awk '{print $NF}')
grep -E 'Verified using v[0-9]' "$TMP/sig.txt" | sed 's/^/   /' || true
[ "$DIGEST" = "$EXPECTED_SIGNER" ] && echo "   signer OK $DIGEST" || { echo "   SIGNER MISMATCH $DIGEST"; exit 1; }
echo "== 4. badging"; "$BT/aapt2" dump badging "$TMP/app.apk" > "$TMP/badging.txt"; B=$(head -1 "$TMP/badging.txt")
PKG=$(echo "$B" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"); BCODE=$(echo "$B" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p"); BVER=$(echo "$B" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
echo "   $PKG versionCode=$BCODE versionName=$BVER"
[ "$PKG" = "$EXPECTED_PKG" ] && [ "$BCODE" = "$CODE" ] && [ "$BVER" = "$VER" ] || { echo "   BADGING MISMATCH"; exit 1; }
echo "   permissions: $(grep -c "uses-permission" "$TMP/badging.txt")"
echo "== all checks passed"
if [ "$INSTALL" = 1 ]; then
  export PATH="$SDK/platform-tools:$PATH"
  echo "== 5. install"; adb install -r "$TMP/app.apk" | tail -1
  adb shell dumpsys package "$EXPECTED_PKG" | grep -m1 versionName | sed 's/^ */   installed /'
fi
