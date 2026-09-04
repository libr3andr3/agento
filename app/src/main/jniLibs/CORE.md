# libagento_core.so

El núcleo del agente, precompilado. Lo carga `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); puntos de entrada JNI
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| versión del núcleo | 0.2.0 (`AgentoCore.version()`) |
| compilado | 2026-09-04, `cargo ndk --release` en node.yaya.tech, NDK 27.2.12479018 |
| origen | crate del núcleo de agento @ `c4f7a59` + parches locales del 2026-09-04: `collect_payment` ve citas confirmadas sin pagar, guardia de llamadas repetidas por turno, cierre veraz al agotar rondas, salida del bucle tras una llamada repetida, cliente LLM nativo Qwen (tool calls XML, `LLM_THINKING`), regla de hierro del asistente (una reserva existe solo con resultado de herramienta en este turno); kernel corazón @ `a69fbe7+` (privado) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `97d408930cca769354bbaa255922ec6fcc7d5b94596202624e11d1c8633d8417` |
| `armeabi-v7a` | `1363bc1fd85292910f0b8e5fc7510a9959bd3c56baa9e236120b118791c69c85` |
| `x86_64` | `de4494fadfd28ec5e65bdde9779e73888c4251866e3fe65adc7628eca5d64d19` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` debe coincidir con esta
tabla. Procedimiento de refresco: `docs/RELEASE.md` → «Refrescar el núcleo
del agente». Los esquemas desde los que el núcleo compone viajan en
`app/src/main/assets/schemas/`.
