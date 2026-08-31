# Publicar una versión

## Versiones

`app/build.gradle.kts` → `versionCode` (entero, siempre sube) y `versionName`
(`major.minor.patch`). Los teléfonos instalados deciden si actualizar solo
por `versionCode`, así que cada build publicado necesita uno nuevo.

## Build firmado

El keystore de subida nunca entra al repo. Exporta su ubicación y contraseñas
(un `keystore.env` que haces `source` es la vía usual):

```bash
export AGENTO_KEYSTORE=~/.agento/upload-keystore.jks
export AGENTO_KEYSTORE_PASS=…
export AGENTO_KEY_ALIAS=agento-upload
export AGENTO_KEY_PASS=…
./gradlew assembleDirectRelease
# → app/build/outputs/apk/direct/release/app-direct-release.apk  (minificado con R8, firmado)
./gradlew bundlePlayRelease
# → app/build/outputs/bundle/playRelease/app-play-release.aab      (Google Play, docs/PLAY.md)
```

`direct` y `play` son los dos canales (flavors) de la misma app; ver
`app/build.gradle.kts`. Sin esas variables las tareas de release igual
compilan pero producen artefactos sin firmar — bien para CI, no instalables
junto a uno firmado.

Verifica la firma antes de publicar (la pasarela fija este digest como
`EXPECTED_SIGNERS`, y `scripts/verify-apk.sh` lo comprueba al descargar):

```bash
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/direct/release/app-direct-release.apk
# Signer #1 certificate SHA-256 digest: 0204f2e455438244720aa79c9421e70927d957259cc77ce95b69148a44a35df2
```

## El canal de actualización

La app se distribuye desde `https://agento.ceo/dl/`, no desde Play. Tres
archivos ahí mueven las actualizaciones (`UpdateCheck.kt`):

| archivo | qué |
|---|---|
| `agento-<versión>.apk` | inmutable, uno por versión |
| `agento-beta.apk` | siempre el último (la landing lo enlaza) |
| `latest.json` | `{version, versionCode, minVersionCode, file, url, size_mb, sha256, date, notes}` |

Las apps instaladas consultan `latest.json` al abrir el panel y cuando el
listener se reconecta. `versionCode` más nuevo que el instalado → banner y
notificación; instalado por debajo de `minVersionCode` → el banner bloquea
hasta que el dueño actualice (úsalo cuando cambie la API del núcleo). El APK
se descarga con `DownloadManager`, se verifica su `sha256`, y se entrega al
instalador de paquetes.

`scripts/publish-apk.sh` sube el APK y escribe `latest.json`:

```bash
RELEASE_NOTES="Cobros: nuevas billeteras" scripts/publish-apk.sh            # mantiene minVersionCode
MIN_VERSION_CODE=51 RELEASE_NOTES="…" scripts/publish-apk.sh             # obliga a actualizar a los builds viejos
NODE=… WEBROOT=… scripts/publish-apk.sh path/al/app-release.apk           # otro host / ruta
```

Después verifica la descarga como lo haría una persona cuidadosa (hash, zip,
firmante, badging) e instálala en un teléfono:

```bash
scripts/verify-apk.sh https://agento.ceo/dl/latest.json --install
```

## Checklist

- [ ] `versionCode` subido; `versionName` coincide con lo que vas a anunciar
- [ ] `./gradlew assembleDebug lintDebug` en verde (el CI lo hace en cada push)
- [ ] APK de release firmado por la clave de subida (digest de arriba)
- [ ] instalado en un teléfono real: iniciar sesión, registrar, la
      entrevista, un mensaje de WhatsApp respondido, una notificación de
      Yape registrada
- [ ] `latest.json` publicado; `scripts/verify-apk.sh` pasa
- [ ] si cambió la API del núcleo: `minVersionCode` subido

## Refrescar el núcleo del agente

`app/src/main/jniLibs/<abi>/libagento_core.so` es un precompilado del crate
del núcleo (privado). Para publicar un núcleo nuevo: compílalo para las tres
ABIs, reemplaza los tres archivos, actualiza `app/src/main/jniLibs/CORE.md`
(versión, commit de origen, sha256 de cada archivo), y si `assets/schemas/`
cambió en el núcleo, copia eso también. `AgentoCore.installSchemas` re-copia
los esquemas cada vez que cambia el `versionCode`, así que sube la versión.
