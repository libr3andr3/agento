# libagento_core.so

El núcleo del agente, precompilado. Lo carga `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); puntos de entrada JNI
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| versión del núcleo | 0.2.0 (`AgentoCore.version()`) |
| compilado | 2026-08-31, `cargo ndk --release`, NDK 27.2.12479018 |
| origen | crate del núcleo de agento @ `b783612` (plugin `disclosure` — transparencia de IA en el primer contacto, Ley 31814), kernel corazón @ `a69fbe7+` (privado) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `605acde29b42a5b8e192ba5a9720473090ccc2d790d66506195195d8ea470638` |
| `armeabi-v7a` | `a8b8731b92b9a3eb138d22fc9d10d6388c000f5b7e94d934d0f04783beabbd5b` |
| `x86_64` | `a1cecccd0006e2f877c35a0bd6456478b80d8643f013a32ec948241e93a3ab7f` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` debe coincidir con esta
tabla. Procedimiento de refresco: `docs/RELEASE.md` → «Refrescar el núcleo
del agente». Los esquemas desde los que el núcleo compone viajan en
`app/src/main/assets/schemas/`.
