# Nebula 1.4.0

**CSS themes now restyle the patched Discord app**, plus three new features.

## Themes apply to native views — no DOM needed

Previous versions said CSS could not style the patched app because Discord renders native
React Native views. That was true of *CSS as CSS*, but not the end of the story: Discord's
UI reads colour tokens from plain JS objects inside its bundle, and those keys are the
camelCased form of Discord's CSS variables.

1. The theme is parsed with the project's real CSS parser (comments, `var()` chains,
   fallbacks, `@media`) via the new `ThemeTokens`.
2. Each custom property becomes a Discord token: `--background-primary` →
   `backgroundPrimary`, `--brand-500` → `brand500` plus `brand`/`brandExperiment`
   aliases, and so on for every variable the theme declares.
3. Colours become React Native colours (`#RRGGBB`, `rgba(...)` when alpha is present;
   alpha is a byte, so 0.5 becomes 0.498).
4. The injected loader finds every Discord module exposing those token keys, writes the
   values in, and asks Discord's stores to repaint.

If no module matches, the loader reports exactly that — there is no silent success path.

## New features

* **Patch history** — each built APK is recorded (file, size, SHA-256, source Discord
  version, addon and token counts) in a real JSON file, newest first.
* **Discord update detection** — when installed Discord differs from the version your last
  patch was built from, the Patch tab says so and asks you to re-patch.
* **API coverage warnings** — enabled plugins are scanned for `BdApi.DOM`, `React`,
  `Hooks`, `Components` and `ContextMenu`; each such call is listed before patching.

## Verification for this build

* **31 unit tests, 0 failures** — new suites for the token bridge (real CSS to real
  tokens, `var()` chains and fallbacks, alpha as `rgba`, every bundled theme producing
  valid colours), patch history (real JSON round-trip, duplicate handling, limits, corrupt
  file), and an end-to-end check that resolved tokens land in the patched bundle payload.
* `apksigner verify` OK · 6238384 bytes (5.9 MB, was 5.9 MB) · SHA-256 `ca4880aadcb0bfa4a4ced2651cb74e16acfb31673c02846c1fc3e114282616a5`.
* JNI surface re-verified in the built DEX (`com/quickjs/QuickJS`, `JSContext`,
  `JSFunction`, `JavaCallback` unobfuscated).

## Known limitations

* Token application depends on Discord's module layout. A build whose colour objects are
  shaped differently reports "no colour module exposed any of its tokens" instead of
  half-applying a theme. There is still no physical-device run here: this is verified by
  unit tests over the real parser and payload, not by watching Discord repaint.
* Hermes-bytecode Discord builds are still refused with an explanation.
* A patched build uses a different signing key, so uninstall official Discord first.
