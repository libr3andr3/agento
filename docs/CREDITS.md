# Créditos prepagados: el modelo de ingresos

Desde 1.22.0 agento no vende planes. Cada cuenta Yaya tiene un **saldo de
créditos prepagados en dólares** (circuito cerrado) que el agente consume
solo cuando se **confirma un resultado**: una cita o una venta. Este
documento es la fuente de verdad del modelo; la app (este repo), el núcleo
y la pasarela (repo `agento.ceo`) lo implementan tal cual.

## 1. Precios

| concepto | valor |
|---|---|
| resultado confirmado con un cliente **conocido** | **USD 1.00** |
| resultado confirmado con un cliente **nuevo** | **USD 2.00** |
| volumen: a partir del resultado cobrado nº 101 en el mes calendario | precios a la mitad: USD 0.50 / 1.00 |
| tope duro mensual | **USD 199.00** por cuenta y mes calendario |
| regalo de bienvenida | **USD 12.00** al crear la cuenta (= 6 clientes nuevos) |
| reversión | cancelación o reembolso dentro de las **24 h** del cargo → crédito devuelto |

- Los precios son en USD e **incluyen impuestos**. La pasarela guarda por
  cada cargo la tasa aplicada y el neto (§ 6).
- **Cliente nuevo** = primera vez que ese número de teléfono tiene un
  resultado confirmado con **este negocio**. Es por negocio, no global: el
  mismo cliente es «nuevo» para cada negocio distinto que lo atiende.
- **Confirmado** =
  - donde los **adelantos están habilitados** para el país del negocio
    (`depositsEnabled = true`): el turno quedó reservado **y** llegó la
    notificación del adelanto (o, si el negocio no configuró adelanto, el
    turno quedó reservado);
  - donde los adelantos están **deshabilitados**: el dueño aceptó — el turno
    quedó reservado / el pedido quedó aceptado (`status = confirmed`).
  - Nunca se cobra por chats, cotizaciones, ni pedidos abandonados
    (`pending_payment` que nunca se pagó).
- Volumen y tope se calculan **en la pasarela**, nunca en el teléfono. El mes
  calendario se cierra en `EXPIRY_TZ` (America/Lima por defecto), igual que
  el resto del libro mayor.

## 2. Saldo, gracia y modo manual

El saldo **nunca decide a qué clientes se atiende**. Por debajo de USD 2 el
agente sigue agendando todo.

| saldo | estado | qué hace el agente | qué ve el dueño |
|---|---|---|---|
| ≥ 2.00 | `ok` | todo | franja verde con saldo y precios |
| 0.00 – 1.99 | `low` | todo | franja ámbar «Recarga pronto» |
| −4.00 – −0.01 | `grace` | todo (la cuenta puede quedar en −4.00) | franja roja «Saldo negativo» |
| < −4.00 | `manual` | **deja de responder**; cada mensaje pasa al dueño por la ruta de entrega manual existente (`attention`, `escalated_human`); el chat nunca se bloquea | franja roja «Modo manual», chip «Modo manual», **una** notificación con enlace de recarga |

Al pasar a `manual`, la pasarela envía **un** WhatsApp al dueño con el enlace
de recarga (`topupUrl`); no se repite hasta que el saldo vuelva a ≥ 0 y
caiga otra vez. La app además muestra una alerta local (como máximo cada
6 h mientras dure).

## 3. Términos del circuito cerrado

Texto que la app muestra bajo el botón de recarga y en el registro (ES/PT/EN;
`credits_terms` / `reg_terms_*`), y que la pasarela devuelve como
`termsVersion`:

> Los créditos solo se canjean por servicios de Agento. No son transferibles
> entre cuentas ni canjeables por dinero; los reembolsos se hacen únicamente
> al medio de pago original. No son dinero, ni un depósito, ni una cuenta de
> pago.

La aceptación se guarda en la cuenta (`accounts.terms_version`,
`accounts.terms_accepted_at`): la app la envía en `POST /api/onboard_business`
(`termsVersion`, `termsAcceptedAt`) y el núcleo la reenvía a la pasarela
(`POST /v1/account/profile`). Versión vigente: **`2026-09`**.

## 4. Categoría del negocio y negocios prohibidos

El registro pide una categoría de una lista fija (`GET /api/categories`,
con lista embebida en la app por si no hay red):

`peluqueria` peluquería/barbería · `estetica` estética/uñas · `consultorio`
consultorio/clínica · `veterinaria` · `gimnasio` gimnasio/clases ·
`restaurante` restaurante/delivery · `ropa` ropa/tienda · `servicios`
servicios técnicos · `otro`.

La pasarela también publica la lista **prohibida** (por defecto:
`farmacia_sin_receta`, `armas`, `apuestas`, `adulto`, `cripto_intercambio`,
`prestamos`). Esas opciones aparecen en el selector para que el gate pueda
actuar: elegir una muestra la pantalla neutra «Agento no está disponible
para este tipo de negocio» y el registro no continúa. La categoría viaja a
la pasarela (`account.category`) y queda en `businesses.category`.

## 5. Adelantos por país

`depositsEnabled` viene del resumen de créditos (`GET /api/credits`) y sale de
la tabla `country_config` de la pasarela; **la app nunca lleva la lista de
países**. Con `false` el agente reserva sin pedir adelanto (aunque el negocio
tenga `bookingDeposit` configurado) y el panel muestra «Adelantos:
próximamente en tu país».

## 6. Impuestos

Los precios son tax-inclusive. La tabla `country_config` fija la tasa por
país y cada débito guarda `tax_rate` y `net_cents = round(amount / (1 +
rate))`:

| país | tasa | | país | tasa |
|---|---|---|---|---|
| PE | 18 % | | AR | 21 % |
| MX | 16 % | | BR | 17 % (default) |
| CO | 19 % | | EC | 15 % |
| CL | 19 % | | PA | 7 % |
| otros | 0 % hasta que se fije | | | |

Solo se registra: la facturación (boletas, IVA local) es asunto de
`docs/FINANCE.md` en `agento.ceo`.

## 7. Datos (pasarela, `gateway/migrations/022_prepaid.sql`)

- `accounts.country` — ISO-2 derivado del código de marcación del WhatsApp
  al crear la cuenta; **inmutable**. `accounts.category`,
  `accounts.terms_version`, `accounts.terms_accepted_at`.
- `country_config (iso, deposits_enabled, tax_rate, display_currency)`.
- `prepaid_ledger (id, account, kind, amount_cents, outcome_id, business,
  client_hash, is_new_client, tax_rate, net_cents, method, external_id, note,
  created_at)` — `kind` ∈ `grant | debit | reversal | topup | refund`.
  Cents USD; el saldo es `SUM(amount_cents)`. Índice único
  `(account, outcome_id, kind)` = idempotencia de débitos y reversiones.
- `prepaid_clients (account, business, client_hash, first_at)` — quién ya es
  conocido, por negocio.
- `prepaid_months (account, month, outcomes, charged_cents)` — contador
  mensual para volumen y tope.
- `prepaid_welcome (phone)` — un regalo por número de WhatsApp, para siempre.
- `prepaid_handoffs (account, notified_at)` — el aviso de modo manual, uno por
  episodio.

`client_hash = sha256("{business_id}:{teléfono canónico E.164}")` se calcula
en el teléfono: la pasarela nunca ve el número del cliente.

## 8. Endpoints

### Pasarela (`agento.ceo/gateway`, auth = agente firmado y vinculado, o sesión Yaya)

| método + ruta | cuerpo | devuelve |
|---|---|---|
| `GET /v1/credits` | | `{balance, currency:"USD", state, grace:-4, prices:{known:1,new:2,volumeAfter:100,volumeKnown:0.5,volumeNew:1,monthlyCap:199}, welcome:12, month:{outcomes, charged, capReached}, country, depositsEnabled, taxRate, displayCurrency, termsVersion, topup:{presets:[10,25,50], selected:25, methods:["card"|"yape"], url}, ledger:[…50]}` |
| `POST /v1/outcomes/confirm` | `{outcomeId, business, clientHash, kind:"booking"\|"sale", customer?}` | idempotente por `outcomeId`: `{charged, isNewClient, balance, state, action?:"no_credits", month:{…}, duplicate?}` |
| `POST /v1/outcomes/reverse` | `{outcomeId}` | dentro de 24 h del cargo: `{reversed, balance}`; `409` fuera de ventana; idempotente |
| `GET /v1/wallets` | | `{version, wallets:[{package, name, countries:[…]}]}` (archivo `WALLETS_FILE`, `gateway/wallets.json` por defecto) |
| `GET /v1/categories` | | `{categories:[{key, es, pt, en}], prohibited:[…]}` (`CATEGORIES_FILE`) |
| `POST /v1/account/profile` | `{category?, termsVersion?, termsAcceptedAt?}` | `403 {"error":{"type":"prohibited"}}` si la categoría está prohibida |
| `POST /v1/topup/session` | `{amount: 10\|25\|50, method?:"card"}` | `{url}` de Dodo Checkout (tarjeta; Dodo es merchant of record; metadata `{account_id, amount_usd}`) |
| `POST /v1/topup/yape` | `{amountMinor}` (céntimos PEN, solo PE) | `{ref, amount, currency:"PEN", usdCents, pay:{yape, plin, payee}}` — se acredita cuando yaya.cash ve la transferencia; `method = yape`, `igv_inclusive` |
| `POST /v1/webhooks/dodo` | evento Standard Webhooks | verifica firma; idempotente por id de pago; `payment.succeeded` → `topup`; `refund.*` → `refund` (puede dejar saldo negativo); WhatsApp de confirmación al dueño |

Regalo de bienvenida: en `POST /v1/accounts/otp/check` cuando la cuenta se
crea con un teléfono verificado que nunca lo recibió.

### Núcleo (loopback, `docs/CORE-API.md`)

| método + ruta | notas |
|---|---|
| `GET /api/credits` | el resumen de arriba, con caché local cuando la pasarela no responde (`cached: true`) |
| `GET /api/dashboard` → `credits` | `{balance, state, grace, prices, depositsEnabled, month}` |
| `POST /api/execute_action` → `action: "no_credits"` | solo en estado `manual`: `agentResponse` vacío (la app no envía nada), `actionData:{manual:true, balance, topupUrl}`, `attention:[{kind:"no_credits", urgent:true, …}]` |
| `POST /api/topup/session` | `{amount, method:"card"\|"yape"}` → lo que devuelve la pasarela |
| `GET /api/wallets` · `GET /api/categories` | proxies con caché |
| `POST /api/onboard_business` | acepta `category`, `termsVersion`, `termsAcceptedAt`; `403 {"error":"prohibited_category"}` |
| `POST /api/outcomes/{id}/reverse` | el dueño cancela/reembolsa desde la app |

Dónde cobra el núcleo (`server/src/outcomes.rs::confirm`): al pasar una cita
o un pedido a `confirmed` — `book_appointment` (sin adelanto), el adelanto
que llega por `payment_event` o `collect_payment`, `create_order`. Las
cancelaciones (`handle_cancellation`, `/api/outcomes/{id}/reverse`) piden la
reversión. Si la pasarela no responde, el cargo queda en cola local
(`outcomes.synced = 0`) y se reintenta en el siguiente turno o panel: el
agente **no deja de agendar por falta de red**.

## 9. Recargas

- **Tarjeta (todos los países)**: Dodo Payments, productos one-time de USD
  10 / 25 / 50 (`DODO_TOPUP_10/25/50`), precio tax-inclusive, moneda local
  adaptativa en el checkout. La app abre la URL en una Custom Tab; el saldo
  llega por webhook.
- **Yape/Plin (solo Perú)**: la ruta de recarga existente verificada por
  notificación; el monto en S/ es IGV-inclusive y se convierte a USD con
  `PEN_PER_USD`.
- Presets 10 / 25 / 50; el del medio preseleccionado.

Variables: `DODO_API_KEY`, `DODO_WEBHOOK_SECRET`, `DODO_TOPUP_10`,
`DODO_TOPUP_25`, `DODO_TOPUP_50`, `PEN_PER_USD`, `WALLETS_FILE`,
`CATEGORIES_FILE`, `TOPUP_URL`. Sin secretos en el código.

## 10. Migración desde 1.21

- Instalaciones que usaban «Continuar sin cuenta»: al abrir 1.22.0 van a
  iniciar sesión; el negocio y el device token sobreviven.
- `minVersionCode = 66`: la API del loopback cambió (`/api/plan` desaparece);
  los builds anteriores ven el banner de actualización obligatoria.
- Cuentas con plan vigente: la pasarela convierte lo que quede en créditos
  (criterio comercial, fuera de este repo). El regalo de USD 12 es una sola
  vez por número de WhatsApp.

## 11. Verificación

- Pruebas unitarias en `gateway/src/prepaid.rs`: nuevo vs conocido por
  negocio, mitad de precio en el 101, tope de 199, reversión a 24 h, gracia →
  modo manual, webhook idempotente.
- Guion manual `agento.ceo/scripts/credits-e2e.sh`: registrar → bienvenida
  12 → 7 resultados (mezcla nuevo/conocido) → gracia → entrega manual → pago
  de prueba Dodo → saldo restaurado.
