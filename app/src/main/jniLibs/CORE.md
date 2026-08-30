# libagento_core.so

The agent core, prebuilt. Loaded by `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); JNI entry points
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| core version | 0.2.0 (`AgentoCore.version()`) |
| built | 2026-08-30, `cargo ndk --release`, NDK 27.2.12479018 |
| source | agento core crate @ `21e7346+` (D15 ui/media/queue, D16 audit chain), corazón kernel @ `a69fbe7` (private) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `61618c5bc68b306bd3c7678573f057d4742a38f0c1e3cb890348dbe49a19e433` |
| `armeabi-v7a` | `015ccf563e69b80f474b71a631e277edbbffafad4a200ab647bec967aaa7099a` |
| `x86_64` | `c6f9dafc34e08d1cf983464227f20f246df8cb64f4a052d6368066a5e19f1c35` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` must match this table.
Refresh procedure: `docs/RELEASE.md` → "Refreshing the agent core".
The schemas the core composes from ship in `app/src/main/assets/schemas/`.
