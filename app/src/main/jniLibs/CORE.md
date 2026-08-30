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
| `arm64-v8a` | `5eadef7957e246cfc1f574d3cacd941a8353a35ca1a57914e3d7bd701582eb4f` |
| `armeabi-v7a` | `e73921f5ccc349e735454c5cebcd72b23fe67d82d421b63bd9927da21d7ee91e` |
| `x86_64` | `d7bfdc6ebb0cefc54809d61e31bbd89ffd2eb3227acde94d4802c688ddd6c5fe` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
