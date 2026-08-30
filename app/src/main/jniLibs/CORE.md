# libagento_core.so

The agent core, prebuilt. Loaded by `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); JNI entry points
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| core version | 0.2.0 (`AgentoCore.version()`) |
| built | 2026-08-30, `cargo ndk --release`, NDK 27.2.12479018 |
| source | agento core crate @ `6a4583b`, corazón kernel @ `a69fbe7` (private) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `3a161817e4f18477279dac14d0a731399129de9668d35b2d461d3dca7ba286f9` |
| `armeabi-v7a` | `b111dff62d35cdcdea7a2062c37f2d629a9795d1f239fac7fe670c50dcd8ccb2` |
| `x86_64` | `81a4bed1a7c55d7c224535b00935434e941eb721f0a46c3af7c1ee8a3181636e` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
