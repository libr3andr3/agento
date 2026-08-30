# Security — what the app holds and who can reach it

## Secrets on the phone

| secret | where | why |
|---|---|---|
| device token | `Prefs` → `SecureStore` (AES-256-GCM, key in Android Keystore) | authorises every business call to the core; does not expire |
| core app key | `SecureStore` | per-install random; the core refuses loopback requests without it |
| identity KEK | `SecureStore` (32 bytes) | the core seals its Ed25519 seed under it — a copy of `agento.db` without the phone is not the business's identity |
| custom LLM key | `SecureStore` | only if the owner brought their own model |
| Yaya session, backup key | inside `agento.db`, sealed by the core | |

`SecureStore` is ~100 lines: hand-rolled AES/GCM under a Keystore key,
no user-authentication requirement (the listener runs with the phone
locked). A Keystore key lost to an OS quirk decrypts to `null`, which means
re-pairing — never a plaintext fallback.

Backups are off (`allowBackup=false` + `data_extraction_rules.xml`).
Cleartext HTTP is allowed only in debug builds (`network_security_config.xml`)
for a core or gateway reached over `adb reverse`. The loopback core is
plain HTTP by design — it never leaves the process boundary.

## Permissions, and what each one is for

| permission | why |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` (granted in system settings) | the product: read chat notifications, reply inline |
| `POST_NOTIFICATIONS` | tell the owner when the agent needs them, and about updates |
| `INTERNET`, `ACCESS_NETWORK_STATE` | the core's calls to the gateway; "sin conexión" wording |
| `RECORD_AUDIO` | the voice interview (audio goes to the core → transcription; never stored) |
| `ACCESS_COARSE_LOCATION` | a district for "near me"; asked once, with an explanation |
| `REQUEST_INSTALL_PACKAGES` | the self-hosted update channel |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | OEM battery killers stop the listener; we ask to be exempt |
| `QUERY_ALL_PACKAGES` + `<queries>` | Cobros checks whether the wallet the owner named is installed; Settings shows which chat apps are present |

The listener sees every notification on the phone. What the app does with
them: chat apps the owner enabled → the core; anything with an inline reply
from an unknown app → shape only (`UnknownAppObserver`), never content;
anything else → forwarded raw to the core *on this phone* for money
detection, and muted when the core says "no money here". Nothing is sent
to any other server.

## What reaches yaya.tech

- LLM, speech and vision calls, signed by the agent's key. The prompt
  contains what the agent needs to answer the current turn.
- Account and plan calls; encrypted backups on paid plans.
- The agent's public card (business name, offer, location, attestation).

The gateway keeps metadata (which agent, when, how much). It cannot read
relay traffic between agents: those are sealed boxes.

## Hardware attestation

`DeviceAttestation` generates a P-256 key in the TEE/StrongBox with an
attestation challenge bound to the agent id and hands the certificate chain
to the core, which publishes it with the card. The registry verifies it
against Google's roots and pins the signing certificate of this app
(`EXPECTED_SIGNERS`). Phones without attestation are simply "unverified".

## Reporting

Security issues: write to the contact address on https://agento.ceo/privacy.html. Please do not open a public issue for
a vulnerability.
