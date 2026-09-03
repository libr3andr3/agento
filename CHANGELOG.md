# Changelog

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
