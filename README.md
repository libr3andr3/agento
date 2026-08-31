# agento

**Una recepcionista con IA que vive en el teléfono Android del dueño del negocio.**

agento lee los mensajes que el teléfono del negocio ya recibe — WhatsApp,
WhatsApp Business, Instagram, Messenger, Telegram, SMS — a través del sistema
de notificaciones de Android, y los responde como el negocio: cotiza precios,
agenda citas, toma pedidos y confirma pagos (Yape, Plin, cualquier billetera o
banco que publique una notificación). Sin APIs de plataformas, sin scraping,
sin un servidor que guarde las conversaciones. El teléfono *es* el servidor.

[![ci](https://github.com/libr3andr3/agento/actions/workflows/ci.yml/badge.svg)](https://github.com/libr3andr3/agento/actions/workflows/ci.yml)

```
  el cliente escribe por WhatsApp
          │
          ▼  Android publica una notificación
  ┌──────────────────────────────┐
  │ AgenteNotificationListener   │  lee remitente + texto (MessagingStyle)
  └──────────────┬───────────────┘
                 │ HTTP en 127.0.0.1  (ServerClient)
  ┌──────────────▼───────────────┐
  │ núcleo del agente            │  el agente: SQLite, herramientas, memoria
  │ libagento_core.so            │  ← solo la llamada al modelo de lenguaje
  │ (Rust, corre dentro del app) │    sale del teléfono (yaya.tech o tu propia clave)
  └──────────────┬───────────────┘
                 │ texto de respuesta
  ┌──────────────▼───────────────┐
  │ respuesta inline de la       │  el mismo botón "Responder" que ves en
  │ notificación (RemoteInput)   │  la bandeja de notificaciones
  └──────────────────────────────┘
```

Este repositorio es la app Android: el caparazón en Kotlin, sus pantallas y
una copia precompilada del núcleo del agente que las mueve. Está hecho para
gente que quiere entender, modificar y extender un agente real, en
producción, construido sobre notificaciones.

## Cumplimiento: Ley 31814 (Perú)

Perú es el primer país de América Latina con un marco regulatorio integral de
inteligencia artificial en vigor: la **Ley N.° 31814** y su reglamento
(**D.S. N.° 115-2025-PCM**, vigente desde enero de 2026). agento está diseñado
para cumplir sus obligaciones de transparencia para sistemas de IA de riesgo
aceptable, y varias de sus propiedades van más allá de lo exigido:

- **El agente se presenta como IA.** Desde la versión 1.19.0, el primer
  mensaje que un cliente recibe se abre con «Hola, soy el asistente con IA de
  {negocio}», y el agente tiene prohibido hacerse pasar por una persona: si le
  preguntan si es un bot, lo confirma. Esto vive en el núcleo como un plugin de
  comportamiento (`disclosure`), no como texto opcional que el dueño pueda
  quitar.
- **Registro auditable.** Cada turno, herramienta ejecutada y pago leído queda
  en una cadena de auditoría firmada (Ed25519) y encadenada por hash, con
  anclas contrafirmadas por la pasarela — verificable desde el propio teléfono
  (Ajustes → Auditoría). Ver `docs/ARCHITECTURE.md` § «La cadena de auditoría».
- **Datos personales en el teléfono.** Las conversaciones y los datos de los
  clientes no se almacenan en nuestros servidores (relevante también para la
  Ley 29733 de protección de datos personales). Ver «Qué sale del teléfono»
  más abajo y `docs/SECURITY.md`.

## Buscamos colaboradores

agento es software libre en producción y buscamos gente que quiera meterle
mano. Nos sirven especialmente:

- **Android/Kotlin** — pantallas, el listener, compatibilidad con más
  fabricantes y apps de chat.
- **Seguridad** — revisión del modelo de amenazas, del almacenamiento de
  secretos, de la cadena de auditoría y de la atestación; toda mirada con
  formación en ciberseguridad es bienvenida (los límites de confianza están
  documentados en `docs/ARCHITECTURE.md` y `docs/SECURITY.md`).
- **Verticales** — los esquemas y `skill.md` por rubro en `assets/schemas/`:
  enseñarle al agente cómo funciona una peluquería, una pollería, un taller.
- **Traducciones** — los textos de usuario viven en `res/values` (español) y
  `res/values-en` (inglés).

Abre un issue o un pull request; las reglas están en `CONTRIBUTING.md`. Las
vulnerabilidades de seguridad NO van en un issue público — ver
`docs/SECURITY.md` § «Reportes».

## Inicio rápido

Necesitas un JDK 17+ y Android Studio (o solo el Android SDK). Nada más: ni
Rust, ni NDK, ni claves de API.

```bash
git clone https://github.com/libr3andr3/agento.git
cd agento
./gradlew assembleDirectDebug
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
```

Luego, en el teléfono:

1. Abre **agento** e inicia sesión con un Yaya ID (nombre, correo, teléfono →
   código de un solo uso).
2. Registra el negocio (nombre, rubro, teléfono del dueño).
3. Deja que la entrevista de configuración haga sus preguntas — por voz o
   escribiendo. También puedes fotografiar una carta o lista de precios y el
   núcleo extrae los ítems.
4. Concede el **Acceso a notificaciones** cuando lo pida. Ese permiso es el
   que hace que todo funcione; sin él, el agente está sordo.
5. Escríbele al teléfono del negocio desde otro teléfono. Mira llegar la
   respuesta.

Todo lo que el agente hace aparece en el panel y en
**Ajustes → Registro de actividad**.

La app también funciona en el emulador (se incluye un núcleo `x86_64`), pero
el emulador no tiene WhatsApp — usa el chat del gerente en el panel para
hablar con el agente directamente.

## Dónde está cada cosa

```
app/src/main/
  AndroidManifest.xml          permisos (cada uno explicado), pantallas, el servicio listener
  java/tech/yaya/agente/
    AgenteApp.kt               Application: arranca el núcleo temprano, crea los canales de notificación
    AgentoCore.kt              puente JNI a libagento_core.so — start/stop, config, puerto
    ServerClient.kt            el ÚNICO cliente HTTP hacia el núcleo en loopback (reglas de hilos adentro)
    AgenteNotificationListener.kt  el corazón: notificación entra → núcleo → respuesta inline sale
    PaymentDetector.kt         reenvía notificaciones de bancos/billeteras al núcleo (detección de dinero)
    UnknownAppObserver.kt      aprende apps de chat nuevas por la forma de sus notificaciones
    NotificationProfile.kt, ProfileStore.kt   el estado de prueba de una app aprendida
    Prefs.kt                   ajustes + estado local (SharedPreferences)
    SecureStore.kt             AES-GCM bajo el Android Keystore para los secretos en Prefs
    ReplyLog.kt                el registro de actividad (últimos 100 eventos)
    OwnerAlerts.kt             notificaciones locales cuando el agente necesita al dueño
    UpdateCheck.kt             canal de actualización propio (/dl/latest.json)
    DeviceAttestation.kt       atestación de clave por hardware entregada al núcleo (mejor esfuerzo)
    LocationHelper.kt          ubicación aproximada → la ubicación pública del negocio
    Screens.kt                 qué pantalla es «home»
    ── pantallas ──
    WelcomeActivity.kt         launcher: un router sin UI
    AccountActivity.kt         inicio de sesión Yaya ID (código de un uso por correo/WhatsApp)
    RegistrationActivity.kt    negocio → teléfono del dueño → código, paso a paso
    OnboardingActivity.kt      la entrevista de configuración y, después, el chat del gerente
    DashboardActivity.kt       home: estado, ingresos, agenda, conversaciones, preguntas abiertas
    MainActivity.kt            Ajustes: interruptor maestro, apps conectadas, respuesta fija, registro
    CrmListActivity.kt, ConversationActivity.kt   conversaciones y clientes
    PayoutActivity.kt          Cobros: a dónde te pagan los clientes
    PlanActivity.kt            planes de la cuenta Yaya
    BackupUpsellActivity.kt    se muestra una vez tras la entrevista
    Countries.kt, CountryPicker.kt, SupportedApps.kt   catálogos pequeños
  res/                         layouts, strings (values = español, values-en = inglés), tokens de diseño
  assets/schemas/              lo que el agente sabe preguntar: campos base + bundles por vertical
  jniLibs/<abi>/libagento_core.so   el núcleo del agente (ver jniLibs/CORE.md)
docs/                          ARCHITECTURE, CORE-API, DESIGN, SECURITY, RELEASE
scripts/                       verify-apk.sh (verificar una descarga), publish-apk.sh (canal de actualización)
```

Empieza por `AgenteNotificationListener.kt` — léelo de arriba abajo (430
líneas) y entiendes el producto. Luego `ServerClient.kt` para ver cómo la app
habla con el núcleo, y después `docs/ARCHITECTURE.md`.

## El núcleo del agente

`libagento_core.so` es el agente en sí: una biblioteca en Rust que corre
dentro del proceso de la app, guarda cada conversación, cita, pago y dato
aprendido en `filesDir/agento.db`, y expone una API HTTP pequeña en
`127.0.0.1:<puerto aleatorio>`. El lado Kotlin nunca toca la base de datos;
solo llama a esa API (`docs/CORE-API.md`).

El núcleo viene precompilado aquí para `arm64-v8a`, `armeabi-v7a` y `x86_64`.
Su código fuente depende de un kernel privado y no es parte de este repo; la
superficie JNI y cada endpoint que la app usa están documentados para que
puedas construir encima. `app/src/main/jniLibs/CORE.md` registra qué build
está incluido (fecha, commit de origen y sha256 por ABI).

## Qué sale del teléfono

- La llamada al modelo de lenguaje, a `yaya.tech` por defecto (medida,
  firmada con la clave propia del agente) o a cualquier endpoint compatible
  con OpenAI con tu propia clave (Ajustes → mantén presionado el encabezado
  *Motor de IA* en un build de debug).
- Voz y fotos del catálogo, para transcripción y extracción, por la misma vía.
- Tu cuenta Yaya (inicio de sesión, plan) y, en planes pagados, un respaldo
  cifrado.

Los mensajes de los clientes nunca se suben a ningún lado como tales; el
modelo ve el turno que se le pide responder. Ver `docs/SECURITY.md`.

## Compilar un release

```bash
export AGENTO_KEYSTORE=~/.agento/upload-keystore.jks \
       AGENTO_KEYSTORE_PASS=… AGENTO_KEY_ALIAS=agento-upload AGENTO_KEY_PASS=…
./gradlew assembleDirectRelease     # → app/build/outputs/apk/direct/release/app-direct-release.apk
./gradlew bundlePlayRelease         # → app/build/outputs/bundle/playRelease/app-play-release.aab (docs/PLAY.md)
scripts/verify-apk.sh               # verifica una descarga publicada de punta a punta
```

Dos canales se compilan del mismo código: `direct` (el APK en agento.ceo, con
autoactualización) y `play` (Google Play, paquete `yaya.tech.agento`, sin
permisos de instalación ni de visibilidad de paquetes). Procedimiento
completo, numeración de versiones y canal de actualización: `docs/RELEASE.md`;
el envío a Play: `docs/PLAY.md`.

## Licencia

[GNU AGPL v3](LICENSE). agento es software libre: puedes ejecutarlo,
estudiarlo, modificarlo y redistribuirlo, y cualquiera a quien le des un
build — o a quien se lo sirvas por red — recibe los mismos derechos y el
código fuente. Las contribuciones se aceptan bajo la misma licencia.

## Idioma

El producto es primero en español (Perú), con inglés como segundo idioma. La
documentación está en español; los identificadores y comentarios del código
están en inglés. Los textos de usuario viven en `res/values` (es) y
`res/values-en`.

## Estado

Esta es la app que está en producción en [agento.ceo](https://agento.ceo).
El paquete Kotlin sigue siendo `tech.yaya.agente` (el primer nombre de la
app) — no se puede renombrar sin recompilar el núcleo, cuyos puntos de
entrada JNI llevan ese nombre.
