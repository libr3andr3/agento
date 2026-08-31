# libagento_core.so

The agent core, prebuilt. Loaded by `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); JNI entry points
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| core version | 0.2.0 (`AgentoCore.version()`) |
| built | 2026-08-31, `cargo ndk --release`, NDK 27.2.12479018 |
| source | agento core crate @ `b783612` (disclosure plugin — first-contact AI transparency, Ley 31814), corazón kernel @ `a69fbe7+` (private) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `605acde29b42a5b8e192ba5a9720473090ccc2d790d66506195195d8ea470638` |
| `armeabi-v7a` | `a8b8731b92b9a3eb138d22fc9d10d6388c000f5b7e94d934d0f04783beabbd5b` |
| `x86_64` | `a1cecccd0006e2f877c35a0bd6456478b80d8643f013a32ec948241e93a3ab7f` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
