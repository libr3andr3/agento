# libagento_core.so

El núcleo del agente, precompilado. Lo carga `AgentoCore.kt`
(`System.loadLibrary("agento_core")`); puntos de entrada JNI
`Java_tech_yaya_agente_AgentoCore_{start,stop,port,version}`.

| | |
|---|---|
| versión del núcleo | 0.2.0 (`AgentoCore.version()`) |
| compilado | 2026-09-04, `cargo ndk --release` en la workstation yaya, NDK 27.2.12479018 |
| origen | crate del núcleo de agento, rama `one-app` @ `c05d938` (D18: `ship/business-agent` c4f7a59 ⊕ `sovereign` 778e899 — parches del 2026-09-04 ya incluidos — con los módulos de red restaurados: comunidad, mercado, billetera, monedas, malla, nodo; `networkPublish` en `core.yml`); kernel corazón @ `a69fbe7+` (privado) |

| ABI | sha256 |
|---|---|
| `arm64-v8a` | `22b4e600fce74b499b3f3627afcd78eed2463bc0a7d6a1d1f67bb99101e3a737` |
| `armeabi-v7a` | `3545d4223e5b2a6f7fd1b9dbd5849691603cec827e885a0bf1bd22d7e9fdd2e1` |
| `x86_64` | `90367ba1257c4b1e099d370b9c773ca465119104fe71f1cea5b23ef06df4d057` |

`sha256sum app/src/main/jniLibs/*/libagento_core.so` debe coincidir con esta
tabla. Procedimiento de refresco: `docs/RELEASE.md` → «Refrescar el núcleo
del agente». Los esquemas desde los que el núcleo compone viajan en
`app/src/main/assets/schemas/`.
