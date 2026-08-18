# agento — design system (v1)

Every screen reads as ONE app. Build strictly from these tokens — a hardcoded
hex or ad-hoc dp in a layout is a defect.

## Color (use `@color/…`, defined in res/values/colors.xml)
- `agento_primary` #047857 (emerald) — actions, active states, FABs
- `agento_on_primary` #FFFFFF
- `agento_primary_container` #D1FAE5 — chips, highlights, own-chat bubbles
- `agento_on_primary_container` #064E3B
- `agento_secondary` #B45309 (amber) — warnings-lite, pending states
- `agento_secondary_container` #FEF3C7 — trial banner (active), pending chips
- `agento_error` #B3261E, `agento_error_container` #FDE7E9 — expired/off states
- `agento_surface` #FBFDF9 — screen background
- `agento_surface_card` #FFFFFF — cards
- `agento_surface_variant` #E8F0EA — inactive chips, dividers area, other-bubbles
- `agento_on_surface` #191C1A — primary text
- `agento_on_surface_muted` #5C6660 — secondary text
- `agento_outline` #D3DDD6 — strokes

## Shape & space (res/values/design.xml dims)
- spacing: `space_xs` 4dp, `space_s` 8dp, `space_m` 16dp, `space_l` 24dp, `space_xl` 32dp
- corners: cards `corner_card` 20dp, buttons/inputs `corner_control` 14dp, chat bubbles `corner_bubble` 18dp
- Cards: MaterialCardView, cardElevation 0dp, app:strokeColor agento_outline, strokeWidth 1dp, radius corner_card.
- Buttons: MaterialButton; primary actions filled (agento_primary); secondary = TonalButton style (primary_container); destructive = error colors. Min height 52dp for primary CTAs.
- Text: Material3 text appearances (`?attr/textAppearanceHeadlineSmall`, `TitleMedium`, `BodyMedium`, `LabelMedium`). Never raw textSize unless a token exists.

## Voice & tone
Spanish-first, warm, short. Emoji sparingly (one per surface, purposeful).
Every empty state teaches ("Aún no tienes citas — comparte tu número y tu
agente las agendará solo"), every error state offers the next step, every
loading state is visible (progress indicator, disabled CTA with spinner).

## Hard rules
- NO new gradle dependencies. Available: material 1.12 (full M3), appcompat,
  recyclerview, constraintlayout if listed, exifinterface.
- NO edits to themes.xml/colors.xml/design.xml (owned by the integrator).
- New strings go in YOUR OWN files: res/values/strings_<area>.xml (Spanish)
  + res/values-en/strings_<area>.xml (English). Never touch the shared
  strings.xml beyond deleting strings you truly orphaned — prefer leaving them.
- Single-Activity-per-surface stays (no Compose, no nav-graph rewrite).
- Accessibility: contentDescription on every icon-only control; touch targets ≥48dp.
