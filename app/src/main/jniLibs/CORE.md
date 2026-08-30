# libagento_core.so

The agent core, prebuilt. Loaded by `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); JNI entry points
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| core version | 0.2.0 (`AgentoCore.version()`) |
| built | 2026-08-30, `cargo ndk --release`, NDK 27.2.12479018 |
| source | agento core crate @ `1c495f9+`, corazón kernel @ `a69fbe7` (private) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `0a078d627f8542cd136540a9d47c09feea10c4003dbfc290dfe2db6af3addaee` |
| `armeabi-v7a` | `5d25f6558d8e48b90c0f11bbaea650fb533431f2e7f5bbc165b9982a885ed5af` |
| `x86_64` | `2bc5d36e1c1516f2b5164517fe33bdf90967b7655f449e08fcaacf8ed7c9077f` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
