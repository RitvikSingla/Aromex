# Handoff — Ticket #71

**Ticket:** #71 — [Bug] Android — numeric keyboard can't be dismissed on Sales money fields

## Summary
On the Android Sales screen, tapping a money field opened the numeric keypad with no working way to close it — the keyboard's **Done** key did nothing, leaving only the system back gesture. Root cause: the shared `MoneyField` composable passed `keyboardActions = KeyboardActions(onDone = { })`; a non-null but no-op `onDone` overrode Compose's default Done behaviour (hide keyboard). The fix makes Done explicitly hide the keyboard and clear focus, and adds tap-outside-to-dismiss on the Sales screen root so blank-space taps also dismiss the keyboard (covering the `ImeAction.Next` fields, which have no Done key). Both changes mirror patterns already established in `EntitiesScreen.kt` and `LoginScreen.kt`. Android-only; no shared-logic, iOS, or Desktop changes.

## Files changed
### Android
- `androidApp/src/main/kotlin/com/humblesolutions/aromex/ui/sales/SalesScreen.kt`
  - `MoneyField`: replaced the no-op `onDone` with `onDone = { focusManager.clearFocus(); keyboardController?.hide() }` so the Done key dismisses the keyboard (fixes Bank field and custom-line price field, both `ImeAction.Done`). `ImeAction.Next` fields keep Compose's default focus-advance.
  - `SalesScreen`: added a `pointerInput` + `detectTapGestures` tap-outside handler on the scrollable `Column` that clears focus and hides the keyboard on blank-space taps.
  - Added imports: `detectTapGestures`, `pointerInput`, `LocalFocusManager`, `LocalSoftwareKeyboardController`.

## How to test
1. Run the Android app and open the Sales screen.
2. Tap the **Bank** payment field → numeric keypad appears → press **Done** → keyboard hides. ✅
3. Tap **+ Add item** → in the custom-line dialog tap the **price** field → press **Done** → keyboard hides. ✅
4. Focus any money field (e.g. Cash / Card / unit price / discount) and tap an empty area of the screen → keyboard hides. ✅
5. Confirm `ImeAction.Next` fields still advance focus to the next field when Next is pressed.

## Acceptance criteria
- **Numeric keyboard on Sales money fields can be dismissed** — ✅ Met. Done now hides the keyboard; tap-outside also dismisses.
- **Bank field (ImeAction.Done) dismissable** — ✅ Met (verified on device).
- **Custom-line price field (ImeAction.Done) dismissable** — ✅ Met (verified on device).
- **Applies to all money fields** — ✅ Met — fix is in the shared `MoneyField`; tap-outside covers the `Next` fields too.

## Deviations / decisions
- The ticket offered "remove the empty `onDone`" OR "explicitly hide via `LocalSoftwareKeyboardController` / `FocusManager`" and/or tap-outside/scroll dismissal. Chose the **explicit hide** (not a bare removal) because it reads clearly and matches the existing `EntitiesScreen.kt` pattern, **plus** tap-outside dismissal to also cover the `ImeAction.Next` fields that have no Done key. Both are established patterns already in this codebase.
- Left `ImeAction.Next` fields on Compose's default (advance focus) rather than changing them to Done — preserves the existing field-to-field flow (cash → card → bank).

## Open questions / follow-ups
- None. iOS was already correct (native keyboard "Done" toolbar) and is untouched.

## Verification
- `:androidApp:compileDebugKotlin` passes clean.
- Both Done fields and tap-outside dismissal verified on-device by the developer.
