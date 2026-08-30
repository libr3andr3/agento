# libagento_core.so

The agent core, prebuilt. Loaded by `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); JNI entry points
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| core version | 0.2.0 (`AgentoCore.version()`) |
| built | 2026-08-29, `cargo ndk --release`, NDK 27.2.12479018 |
| source | agento core crate @ `ba963b7`, corazón kernel @ `a69fbe7` (private) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `709ca5d17eb3f7e26d3771f14c452e2d74edcfca7bf9e7269a81b42b5d663bc2` |
| `armeabi-v7a` | `70ebbd2a7ca30404d6f4367a5470e5b904054c05da575b5a2627c6afdd8de325` |
| `x86_64` | `ac7182f4541ca03c9dc1b907cdadc4cb4c22b9d1dceacc3ec6136aafd2eb9072` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
