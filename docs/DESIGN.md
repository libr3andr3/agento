# agento — design system (v2 "warm paper")

Every screen reads as ONE app — and NOT as stock Android. Build strictly from
these tokens — a hardcoded hex, ad-hoc dp, or `Widget.Material3.*` style
reference in a layout is a defect.

## Typeface
Plus Jakarta Sans, bundled in `res/font/` (`@font/jakarta`, weight-mapped
400/500/600/700/800). The theme sets it globally and overrides every
`textAppearance*` attr — layouts use `?attr/textAppearance…` and get Jakarta
for free. From code, use `R.style.TextAppearance_Agento_*` (never
`TextAppearance_Material3_*` — that resurrects Roboto).

Ramp: Display/HeadlineMedium 800 (−2% tracking) · Headline/TitleLarge 700 ·
Title 600 · Body 400 · Label 600.

## Color (use `@color/…`, defined in res/values/colors.xml)
Token NAMES are load-bearing (Kotlin binds them); values are the theme.
- `agento_primary` #0B7B5B (emerald) — actions, active states
- `agento_primary_container` #DDF3E7 / `agento_on_primary_container` #0A4632
- `agento_secondary` #A6650A (amber) + `agento_secondary_container` #FCEED3 — pending/warnings
- `agento_error` #BA342A + `agento_error_container` #FBEAE7 — expired/off
- `agento_surface` #F6F5F0 — warm-ivory screen ground
- `agento_surface_card` #FFFFFF — floating cards
- `agento_surface_variant` #ECEAE2 — inactive chips, agent bubbles
- `agento_on_surface` #1B1F1C / `agento_on_surface_muted` #6D726C
- `agento_outline` #E4E2D9 — input strokes only (cards are strokeless)
- v2: `agento_hero_grad_start/end` (gradient brand moments), `agento_halo`
  (10% emerald wash), `agento_shadow`, `agento_input_bg`,
  `agento_input_stroke` (res/color state list: outline → emerald on focus)

## Shape & space (res/values/design.xml dims)
- spacing: `space_xs` 4 · `space_s` 8 · `space_m` 16 · `space_l` 24 · `space_xl` 32
- corners: cards `corner_card` 24dp · controls `corner_control` 26dp (pill on
  52–56dp) · bubbles `corner_bubble` 20dp · sheets `corner_sheet` 28dp
- Cards: theme default = `Widget.Agento.Card` (white, strokeless,
  `card_elevation` 2dp soft shadow). Tinted banners (trial/off/gap) stay FLAT:
  explicit `cardElevation 0dp`, `strokeWidth 0dp`.
- Buttons: theme default MaterialButton = emerald pill
  (`Widget.Agento.Button`); variants `.Text`, `.Tonal`, `.Glyph` (48dp square
  composer icons). Primary CTAs `cta_height` 56dp.
- Chips: `Widget.Agento.Chip` — strokeless pill, colors bound from code via
  container tokens.
- Inputs: `Widget.Agento.TextInput` (white fill, 18dp radius, hairline stroke →
  emerald on focus, no floating label) for chat-style fields; registration
  forms keep OutlinedBox with floating labels + `corner_control` radii.

## Gradient brand moments (use sparingly — these ARE the brand)
`bg_brand_mark` (welcome squircle) · `bg_hero_halo` (radial wash behind it) ·
`bg_earn_hero` (dashboard earnings card) · `bg_done_sheet` (celebration).
Emerald 315° gradient, white text (#B3FFFFFF for secondary labels).

## Voice & tone
Spanish-first, warm, short. Emoji sparingly (one per surface, purposeful).
Every empty state teaches, every error state offers the next step, every
loading state is visible (progress indicator, disabled CTA with spinner).

## Hard rules
- NO new gradle dependencies. Available: material 1.12 (full M3), appcompat,
  recyclerview, constraintlayout if listed, exifinterface.
- themes.xml / colors.xml / design.xml / font/ ARE the design system: change
  them deliberately, in their own PR, never as a side effect of a screen.
- New strings go in per-area files: res/values/strings_<area>.xml (Spanish)
  + res/values-en/strings_<area>.xml (English).
- Single-Activity-per-surface stays (no Compose, no nav-graph rewrite).
- Accessibility: contentDescription on every icon-only control; touch targets
  ≥48dp; text contrast ≥4.5:1 on its surface.
