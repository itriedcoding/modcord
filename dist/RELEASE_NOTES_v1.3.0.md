# Nebula 1.3.0

A smaller app, and the Discord flow now also works when Discord is not installed yet.

## Lighter: 9.1 MB → 5.9 MB (3.1 MB smaller)

Measured on the built APK, not estimated.

* R8 code shrinking and resource shrinking are enabled for release builds; DEX is now
  776 KB compressed (was 3,365 KB).
* Resources are limited to English, halving `resources.arsc` from 985 KB to 447 KB. The
  app only ships English UI text, so nothing is lost.
* All four ABIs for the QuickJS native libraries are kept: arm64-v8a, armeabi-v7a, x86,
  x86_64.

### A real bug caught before shipping

Enabling R8 exposed a keep-rule mistake: `-keep class io.github.taoweiji.quickjs.**` is
the **Maven group id**, and the actual Java package is `com.quickjs`. The rule matched
nothing, so R8 renamed the **JNI-bound** QuickJS classes — `QuickJS`, `JSContext`,
`JSFunction`, `JavaCallback` — which would have made every plugin call fail at runtime
on a device.

The rule now targets `com.quickjs.**`, and the build checks the produced DEX for those
class descriptors before publishing: if they are missing, the release step refuses to
run. That check is what blocked the two incorrect attempts instead of shipping them.

## Discord install flow

* Discord is still detected from the app installed on the phone; Nebula downloads nothing.
* When Discord is **not** installed, the Patch tab offers "Install Discord from the store"
  (Play Store, with a browser fallback) and re-detects automatically when you come back.
* Nebula deliberately does not fetch Discord APKs from mirrors: only Discord distributes
  Discord, and an APK from a third-party mirror cannot be verified.

## Verification for this build

* 23 unit tests pass (patch pipeline, installed-APK handling, JS conformance, CSS and
  META parsers).
* `apksigner verify` OK · size 6237864 bytes · SHA-256 `d333f168b1523333729c953897d79fa44ff458c732c138e6170c717773d3e170`.
* JNI surface verified in the built DEX: `com/quickjs/QuickJS`, `JSContext`,
  `JSFunction`, `JSArray`, `JavaCallback` all present unobfuscated.
* Assets intact: 4 `bdapi` runtime files, 6 bundled addons, loader + signing keystore.

## Known limitations

* The app was not run on a physical device or emulator here, so the obfuscated build's
  runtime behaviour is verified by DEX inspection, not by execution.
* CSS themes cannot style the patched Discord app (native React Native views, no DOM).
* Hermes-bytecode Discord builds are still refused with an explanation.
