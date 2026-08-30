# libagento_core.so

The agent core, prebuilt. Loaded by `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); JNI entry points
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| core version | 0.2.0 (`AgentoCore.version()`) |
| built | 2026-08-30, `cargo ndk --release`, NDK 27.2.12479018 |
| source | agento core crate @ `8ed5e89+` (D15: ui.rs, media, queue routes, share_photos), corazón kernel @ `a69fbe7` (private) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `c4ce292b2dc48d4615ea0305c8d7a59930dc9a8088f269084d4bbcbf162e0275` |
| `armeabi-v7a` | `fb40687c99ef0a91353f2873fee7d5adc82652c22436b80ff4e7e683b4604d2c` |
| `x86_64` | `48452e875a5d8dc78990fd12da72c6cf320125df67289511287a0f0ee04d7458` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
