# Changelog

## 1.23.0 · versionCode 68 · 2026-09-04

**Una sola app: el internet de los agentes vuelve al núcleo** (D18 en el
repo del núcleo). El interruptor es uno solo, en el registro: «Participar
del internet de los agentes», encendido por defecto.

- Registro: nuevo interruptor bajo los términos. Encendido, el agente del
  negocio se publica en la red yaya, otros agentes lo alcanzan cifrado de
  extremo a extremo, los negocios cercanos del mismo rubro le refieren
  clientes y gana el precio del lead por cada referido que registra. Apagado,
  el agente no aparece, no recibe mensajes de la red ni referidos, y no gana
  nada por ella. Solo el «no» explícito viaja (`network: false` en
  `/api/onboard_business`); el valor `networkPublish` se cambia después
  hablando con el agente («no quiero aparecer en la red» / «quiero volver a
  la red»).
- Núcleo: el mismo crate que ya corría (parches del 2026-09-04 incluidos)
  con los módulos de red restaurados y montados en todo núcleo de negocio:
  comunidad (referidos entre pares), mercado, billetera, monedas, malla
  post-cuántica, nodo WhatsApp. Rutas nuevas en el loopback: `/api/mesh/*`,
  `/api/coins/*`, `/api/node/*`. Esquema `core.yml` con `networkPublish`.
- Nada de D14–D17 cambia: planes, vendedor, transparencia IA, cadena de
  auditoría, fotos del catálogo, diseño de la app, exportación de contactos.

## 1.22.1 · versionCode 67 · 2026-09-04

- Núcleo: cuando el cliente avisa que ya pagó una cita reservada sin depósito
  (estado `confirmed`, sin pago), `collect_payment` ahora la encuentra y
  verifica la notificación real en vez de responder "nada pendiente"; antes
  el agente volvía a revisar disponibilidad, re-reservaba y re-cobraba (4
  llamadas al modelo y 18k tokens por un "gracias"). Una llamada idéntica
  repetida dentro del mismo turno devuelve el resultado ya obtenido.
  Conversación típica de 6 mensajes: 13 → 10 llamadas, 55k → 42k tokens.

## 1.22.0 · versionCode 66 · 2026-09-03

**La API del loopback cambió: `minVersionCode` sube a 66** (los builds
anteriores ven el banner de actualización obligatoria; publicar con
`MIN_VERSION_CODE=66 scripts/publish-apk.sh`).

- Créditos prepagados en lugar de planes (`docs/CREDITS.md`): USD 1 por
  resultado confirmado con un cliente conocido, USD 2 con uno nuevo, USD 12
  de regalo al registrarse; volumen y tope mensual calculados en la pasarela.
  El saldo nunca decide a quién se atiende: gracia hasta −USD 4 y luego modo
  manual (el dueño responde). Pantalla de créditos con recarga por tarjeta
  (Dodo, Custom Tab) y Yape/Plin en Perú; términos del circuito cerrado.
- Solo con cuenta: desaparece «Continuar sin cuenta». La cuenta es el número
  de WhatsApp.
- Registro: tipo de negocio obligatorio (lista fija + lista prohibida que
  publica la pasarela → pantalla neutra) y aceptación de términos con fecha.
- Último paso de la entrevista: en qué apps responde el agente y de qué
  billeteras lee avisos de pago (catálogo por país, empujado por la pasarela
  como JSON con lista embebida de respaldo). Todo se decide en el teléfono.
- «Adelantos: próximamente en tu país» cuando la pasarela dice
  `depositsEnabled = false`; la app no lleva lista de países.
- Idiomas: español, portugués e inglés; selector en Ajustes.
- `/api/plan`, `/api/account/guest` y `/api/account/plan/request*` ya no se
  llaman.
