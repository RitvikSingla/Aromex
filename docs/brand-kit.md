# Aromex — Brand Kit & Design System

The shared visual language for all Aromex UI. Each platform implements this natively (Compose / SwiftUI /
Compose-Desktop) — there is **no shared UI**, but every platform's theme derives from these tokens.

Anchored on the **legacy app's blue** (`Color(red: 0.25, green: 0.33, blue: 0.54)` = `#40548A`).

## Colors

**Primary — "Aromex Blue"**
| Token | Hex | Use |
|---|---|---|
| Blue/600 **Brand** | `#40548A` | primary buttons, brand, active, links |
| Blue/700 | `#33477A` | pressed, header gradient end |
| Blue/800 | `#283A63` | deep headings / gradient deep |
| Blue/500 | `#4E63A0` | hover |
| Blue/100 | `#E4E9F4` | tints, selected bg, chips, icon bg |
| Blue/50 | `#F1F4FA` | field focus fill, subtle panels |

**Semantic** (from legacy — used for money in/out & status)
| Token | Hex | Use |
|---|---|---|
| Success | `#2E9E66` | money-in, positive, "live" |
| Warning | `#CC6633` | money-out, attention |
| Error | `#D64545` | errors, negative, invalid fields |

**Neutrals**
| Token | Hex |
|---|---|
| Background | `#F5F6FA` |
| Surface (cards/fields) | `#FFFFFF` |
| Surface-alt | `#EEF1F7` |
| Border / divider | `#E3E7F0` |
| Text/primary | `#1A1F2B` |
| Text/secondary | `#5A6172` |
| Text/tertiary (hints, labels) | `#99A0B0` |
| Disabled | `#C7CCD8` |
| On-blue (text on blue) | `#FFFFFF` |

*(Dark mode later: bg `#12151C`, surface `#1B1F2A`, brand lightens to `#7E92C9`.)*

## Typography — **Inter** (system-ui fallback)
Money values use **tabular figures**, right-aligned.
| Style | Size / Line / Weight |
|---|---|
| App name / display | 32 / 40 / Bold, +2% tracking, UPPERCASE |
| Screen title ("Welcome back") | 28 / 34 / Bold |
| Section title | 18 / 24 / SemiBold |
| Body | 16 / 24 / Regular · strong = Medium |
| Button / label | 14–15 / 20 / SemiBold |
| Field label (uppercase) | 12 / 16 / SemiBold, +4% tracking, tertiary |
| Hint / caption / error | 13 / 18 / Regular |

## Spacing (4pt base): 4 · 8 · 12 · 16 · 20 · 24 · 32 · 40 · 48
Screen padding **20** · field & primary-button height **52** · gap between fields **16**.

## Radius: fields/chips **10** · buttons **12** · cards/header **16–20** · pill **999**
## Elevation: card = y2 blur8 `rgba(26,31,43,.06)` · modal = y8 blur24 `rgba(26,31,43,.12)`

## Components
- **Primary button** — filled `#40548A`, white 15/SemiBold, radius 12, height 52, full-width; pressed
  `#33477A`; disabled `#E4E9F4` + tertiary text; loading = white spinner + label (e.g. "Signing in…").
- **Secondary button** — white surface, 1.5px `#E3E7F0` border, `#1A1F2B` text, radius 12, height 52.
- **Text field** — white, 1.5px `#E3E7F0` border, radius 10, height 52; uppercase label above (12,
  tertiary); focus = `#40548A` 2px border + `#F1F4FA` fill; **error = `#D64545` label + border** with a
  13px `#D64545` message + small error icon below; password variant has a trailing eye toggle.
- **Card** — white, radius 16, padding 20, card shadow, optional 1px `#E3E7F0`.
- **Chip** — pill, `#E4E9F4` bg + `#33477A` text (semantic chips use the semantic tint).
- **States** — loading = centered spinner + caption; empty = muted line-icon + message; error card =
  `#FDECEC` bg + error text + outline Retry.

## Logo & brand
- Wordmark **"AROMEX"** — Inter Bold, uppercase, +2% tracking; `#40548A` on light, white on blue.
- Mark — a rounded-square containing an "A"/triangle monogram (outline on blue, filled `#40548A` on light).
- Tagline — *"A Humble Solutions Product."*

## Reference
Approved Figma (auth): https://www.figma.com/make/NS1KnzqJpEsXVGShudAaJK/Aromex
