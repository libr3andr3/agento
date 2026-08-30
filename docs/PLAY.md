# Google Play submission

The Play channel is the `play` flavor: package **`yaya.tech.agento`** (the
app already registered in Play Console), the same code and upload key as
the direct APK, minus what Play does not allow or does itself:

| | direct (`agento.ceo/dl`) | play |
|---|---|---|
| package id | `yaya.tech.agento.business` | `yaya.tech.agento` |
| updates | self-hosted (`latest.json`, `REQUEST_INSTALL_PACKAGES`) | Play |
| `QUERY_ALL_PACKAGES` | yes (Cobros: "is that wallet on this phone?") | no — common wallets are in `<queries>`, the rest is not checked |
| plans | a WhatsApp chat with agento's sales agent (D14) | the same WhatsApp chat — no purchase flow in either build |

Build and check:

```bash
source ~/.agento/keystore.env            # AGENTO_KEYSTORE / _PASS / _ALIAS / KEY_PASS
./gradlew bundlePlayRelease assemblePlayRelease
# app/build/outputs/bundle/playRelease/app-play-release.aab   ← upload this
# app/build/outputs/apk/play/release/app-play-release.apk     ← adb install for a sanity pass
jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging app/build/outputs/apk/play/release/app-play-release.apk | grep -E "^package|uses-permission"
```

Expected permissions on the Play build: `POST_NOTIFICATIONS`, `INTERNET`,
`ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `ACCESS_COARSE_LOCATION`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus the notification-listener
service (`BIND_NOTIFICATION_LISTENER_SERVICE` on the service, not a
`uses-permission`).

## Play Console checklist

**App content → Permissions declaration.** The listener is the product;
Play asks for a justification and a video.

> *Notification listener (core functionality):* agento is a receptionist
> for a business phone. It reads the notifications of the chat apps the
> owner explicitly enables (WhatsApp Business by default) and answers
> them through the notification's own inline reply. Without notification
> access the app has no function. Notifications from other apps are only
> used on-device to detect incoming payment alerts from the owner's bank
> or wallet; nothing from any notification is uploaded except the single
> customer message the AI answers, sent to our model gateway to generate
> the reply. Video: install → sign in → register → grant "Notification
> access" from the in-app prompt → a WhatsApp message arrives on the
> phone → the reply is sent from the notification.

- `RECORD_AUDIO`: voice answers in the setup interview (audio is
  transcribed, never stored). `ACCESS_COARSE_LOCATION`: district for
  "near me" (asked once, explained in-app). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`:
  the listener dies on OEM battery managers; the app asks the owner to
  exempt it.

**App content → Data safety** (what to declare):

| data | collected | shared | purpose | notes |
|---|---|---|---|---|
| Name, email, phone | yes | no | account management | Yaya ID sign-in (one-time code) |
| Messages (customer chats) | yes, processed | with our AI provider as processor | app functionality | the customer's message is sent to the model to produce the reply; stored on the phone, not on our servers |
| Audio | yes, processed | no | app functionality | interview voice notes, transcribed, not retained |
| Approximate location | yes | no | app functionality | optional |
| Device or other IDs | yes | no | app functionality | per-install agent key + hardware attestation |
| Photos | yes, processed | no | app functionality | optional catalog photo → extracted items |

Encrypted in transit: yes. Deletion: in-app ("Cerrar sesión" / uninstall
removes the phone's data; account deletion by email to the privacy
contact). Privacy policy: https://agento.ceo/privacidad.html — Terms:
https://agento.ceo/terminos.html.

- **Ads**: none. **Target audience**: 18+ (business owners). **Category**:
  Business. **Content rating**: utility, no user-generated public content.
- **App access**: reviewers can tap **"Continuar sin cuenta"** to enter
  without an account, then register any business name; the phone-number
  verification step is skipped when the server answers 503 — otherwise
  provide a test phone that receives WhatsApp. Put this in *App access →
  instructions*.
- **Financial features**: none (the app detects the owner's incoming
  payment notifications; it does not move money).

## Store listing (es-PE default, en-US second)

**Name**: `agento`

**Short (es)**: `Tu recepcionista con IA: responde tus chats, agenda citas y confirma pagos.`
**Short (en)**: `Your AI receptionist: answers your chats, books appointments, confirms payments.`

**Full (es)**

agento es la recepcionista con IA de tu negocio. Vive en tu teléfono y responde por ti en WhatsApp Business, Instagram, Messenger y Telegram: cotiza, agenda citas, toma pedidos y confirma pagos, las 24 horas, mientras tú trabajas.

CÓMO FUNCIONA
• Una entrevista de dos minutos, por voz o escrita, le enseña qué vendes, tus precios y tus horarios. También puedes tomarle una foto a tu carta o lista de precios.
• Lee las notificaciones de las apps de chat que tú actives y contesta desde la misma notificación, como lo harías tú.
• Detecta los avisos de pago de tu banco o billetera (Yape, Plin y otros) y los anota en la cita o el pedido.
• Cuando no sabe algo, te pregunta a ti; tu respuesta la aprende al instante.

TU NEGOCIO, EN TU MANO
• Hoy: citas, pedidos e ingresos del día.
• Conversaciones y clientes: qué dijo cada quien y qué hizo tu agente.
• Cobros: tú decides a dónde te pagan.
• Panel web en agento.ceo para verlo desde la computadora.

PRIVACIDAD PRIMERO
• Tus conversaciones y los datos de tu negocio viven en tu teléfono, no en nuestros servidores.
• Solo lee notificaciones de las apps que tú elijas, y solo después de que le des el permiso.
• Código abierto (AGPL): github.com/libr3andr3/agento

14 días gratis con todo. Después, Gratis (30 conversaciones al mes), Pro S/150 (1 000) o Max S/300 (3 000 y hasta 3 teléfonos) — sin permanencia, con boleta electrónica opcional.
agento es de Yaya Tech PBC.

**Full (en)**

agento is your business's AI receptionist. It lives on your phone and answers for you on WhatsApp Business, Instagram, Messenger and Telegram: it quotes, books appointments, takes orders and confirms payments, 24 hours a day, while you work.

HOW IT WORKS
• A two-minute interview, spoken or typed, teaches it what you sell, your prices and your hours. You can also photograph your menu or price list.
• It reads the notifications of the chat apps you enable and replies from the notification itself, the way you would.
• It detects payment alerts from your bank or wallet and records them against the booking or order.
• When it doesn't know something it asks you; your answer is learned on the spot.

YOUR BUSINESS, IN YOUR HAND
• Today: appointments, orders and earnings.
• Conversations and customers: what each one said and what your agent did.
• Payouts: you decide where customers pay you.
• Web console at agento.ceo for the desktop.

PRIVACY FIRST
• Your conversations and business data live on your phone, not on our servers.
• It only reads notifications from the apps you choose, and only after you grant access.
• Open source (AGPL): github.com/libr3andr3/agento

14 days free with everything. Then Free (30 conversations a month), Pro (1,000) or Max (3,000, up to 3 phones) — no commitment, optional e-invoicing.
agento is made by Yaya Tech PBC.

**Screenshots**: phone, 9:16, at least four — sign-in, the interview,
Hoy, Conversaciones, Cobros, Ajustes. Feature graphic 1024×500, icon
512×512 (`app/src/main/res/mipmap-anydpi-v26` is the adaptive source).

**Release notes (es)**: `Primera versión en Google Play de agento para negocios.`
