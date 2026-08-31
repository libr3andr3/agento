# Envío a Google Play

El canal Play es el flavor `play`: paquete **`yaya.tech.agento`** (la app ya
registrada en Play Console), el mismo código y la misma clave de subida que
el APK directo, menos lo que Play no permite o hace por su cuenta:

| | direct (`agento.ceo/dl`) | play |
|---|---|---|
| id de paquete | `yaya.tech.agento.business` | `yaya.tech.agento` |
| actualizaciones | propias (`latest.json`, `REQUEST_INSTALL_PACKAGES`) | Play |
| `QUERY_ALL_PACKAGES` | sí (Cobros: «¿esa billetera está en este teléfono?») | no — las billeteras comunes van en `<queries>`, el resto no se verifica |
| planes | un chat de WhatsApp con el agente de ventas de agento (D14) | el mismo chat de WhatsApp — no hay flujo de compra en ningún build |

Compilar y verificar:

```bash
source ~/.agento/keystore.env            # AGENTO_KEYSTORE / _PASS / _ALIAS / KEY_PASS
./gradlew bundlePlayRelease assemblePlayRelease
# app/build/outputs/bundle/playRelease/app-play-release.aab   ← esto se sube
# app/build/outputs/apk/play/release/app-play-release.apk     ← adb install para una pasada de cordura
jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging app/build/outputs/apk/play/release/app-play-release.apk | grep -E "^package|uses-permission"
```

Permisos esperados en el build de Play: `POST_NOTIFICATIONS`, `INTERNET`,
`ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `ACCESS_COARSE_LOCATION`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, más el servicio del listener de
notificaciones (`BIND_NOTIFICATION_LISTENER_SERVICE` en el servicio, no un
`uses-permission`).

## Checklist de Play Console

**App content → Permissions declaration.** El listener es el producto; Play
pide una justificación y un video. El texto siguiente se pega tal cual en la
consola (en inglés):

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

- `RECORD_AUDIO`: respuestas por voz en la entrevista de configuración (el
  audio se transcribe, no se guarda). `ACCESS_COARSE_LOCATION`: distrito para
  «cerca de mí» (se pide una vez, explicado en la app).
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: el listener muere con los gestores
  de batería de los fabricantes; la app le pide al dueño la exención.

**App content → Data safety** (qué declarar):

| dato | se recolecta | se comparte | propósito | notas |
|---|---|---|---|---|
| Nombre, correo, teléfono | sí | no | gestión de cuenta | inicio de sesión Yaya ID (código de un uso) |
| Mensajes (chats de clientes) | sí, procesados | con nuestro proveedor de IA como encargado | funcionalidad | el mensaje del cliente se envía al modelo para producir la respuesta; se guarda en el teléfono, no en nuestros servidores |
| Audio | sí, procesado | no | funcionalidad | notas de voz de la entrevista, transcritas, no retenidas |
| Ubicación aproximada | sí | no | funcionalidad | opcional |
| IDs de dispositivo u otros | sí | no | funcionalidad | clave de agente por instalación + atestación por hardware |
| Fotos | sí, procesadas | no | funcionalidad | foto opcional del catálogo → ítems extraídos |

Cifrado en tránsito: sí. Eliminación: en la app («Cerrar sesión» /
desinstalar borra los datos del teléfono; eliminación de cuenta en https://yaya.tech/eliminar-cuenta o por correo al
contacto de privacidad). Política de privacidad:
https://agento.ceo/privacidad.html — Términos: https://agento.ceo/terminos.html.

- **Anuncios**: ninguno. **Público objetivo**: 18+ (dueños de negocio).
  **Categoría**: Empresa. **Clasificación de contenido**: utilidad, sin
  contenido público generado por usuarios.
- **App access**: los revisores pueden tocar **«Continuar sin cuenta»** para
  entrar sin cuenta y registrar cualquier nombre de negocio; el paso de
  verificación del número se salta cuando el servidor responde 503 — si no,
  provee un teléfono de prueba que reciba WhatsApp. Esto va en *App access →
  instructions*.
- **Funciones financieras**: ninguna (la app detecta las notificaciones de
  pago entrantes del dueño; no mueve dinero).

## Ficha de la tienda (es-PE por defecto, en-US segundo)

**Nombre**: `agento`

**Corto (es)**: `Tu recepcionista con IA: responde tus chats, agenda citas y confirma pagos.`
**Corto (en)**: `Your AI receptionist: answers your chats, books appointments, confirms payments.`

**Completo (es)**

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

**Completo (en)**

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

**Capturas**: teléfono, 9:16, mínimo cuatro — inicio de sesión, la
entrevista, Hoy, Conversaciones, Cobros, Ajustes. Feature graphic 1024×500,
icono 512×512 (`app/src/main/res/mipmap-anydpi-v26` es la fuente adaptativa).

**Notas de versión (es)**: `Primera versión en Google Play de agento para negocios.`

## Adiciones D17 (1.18.0)

Permisos: `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALENDAR`,
`WRITE_CALENDAR` — pedidos en tiempo de ejecución solo cuando el dueño activa
«Guardar clientes en Contactos» / «Guardar citas en el Calendario». Data
safety: los contactos y eventos de calendario se escriben en el dispositivo
(los propios clientes y citas del dueño) y nunca se recolectan ni
transmiten; la app lee Contactos solo para no duplicar lo que escribió.
Texto de la función: «Tus clientes se quedan contigo: en tus Contactos y tu
Calendario, y exportables (.vcf, .csv, .ics).»
