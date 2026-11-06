# Nebula 1.1.0

Nebula can now **modify the real Discord application**, not just host BetterDiscord
addons beside it.

## New: Patch tab

Pick an installed Discord APK and Nebula will:

1. open it as a ZIP and locate Discord's React Native bundle
   (`assets/index.android.bundle`);
2. append the Nebula loader plus the source of every enabled plugin to that bundle,
   wrapped so a loader failure cannot break Discord's own code;
3. rewrite the container entry by entry, keeping each entry's compression method and
   aligning stored entries the way Android requires (native libraries 4096-byte,
   `resources.arsc` and dex 4-byte), dropping the old signature files;
4. sign the result with **v1 + v2 + v3** using `apksig` — the same implementation
   `apksigner` uses — and verify the signature again before offering it to the
   system installer.

Once patched, the loader runs inside Discord's own JavaScript context and reads
Discord's metro module registry, so `Patcher` and `Webpack.findByProps` operate on the
modules Discord actually uses.

## Refused instead of faked

* **Hermes bytecode** bundles (starting `C6 1F BC`) are refused with an explanation.
  Bytecode is not JavaScript text, so a text injection cannot work.
* Bundles that are neither Hermes nor valid UTF-8 are refused.

## Verification for this build

* 19 unit tests pass, including `PatchPipelineTest`, which builds a real fixture APK,
  patches it and asserts: the output verifies under apksig (v1, v2 and v3), untouched
  entries are byte-identical, the old signature is gone, the v1 manifest was
  regenerated for the patched bundle, native libraries stay stored and 4096-byte
  aligned, Hermes and missing-bundle inputs are refused, and the patched bundle still
  parses under `node --check`.
* Release APK: 9,511,642 bytes, signed, `apksigner verify` OK.

## Known limitations

* CSS themes cannot style the patched app: Discord Android renders native React
  Native views with no DOM. The loader reports this at runtime instead of pretending.
* A patched build uses a different signing key than the official app, so uninstall the
  official Discord first and expect to sign in again.
* The patch pipeline is verified on a fixture APK under JVM tests. It has **not** been
  run against a real Discord APK on a device here, because that means redistributing
  and testing a closed-source binary.
* QuickJS still runs plugin JavaScript on one thread; a blocking infinite loop stalls
  other plugins until restart.
