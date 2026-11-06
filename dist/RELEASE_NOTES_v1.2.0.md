# Nebula 1.2.0

Nebula now patches the Discord that is **already installed on the phone** instead of
asking you to supply an APK file.

## New: automatic detection

The Patch tab asks `PackageManager` for Discord (`com.discord`, plus the alpha and beta
package names), reads that installation's own base APK, and prepares it as the patch
source automatically. A file picker remains for other builds and for devices where
another app's APK cannot be read.

* `<queries>` entries were added: without them Android 11+ hides other packages from
  `PackageManager`, so detection would always report "not found".
* `REQUEST_INSTALL_PACKAGES` is now declared. Without it Android 8+ blocks handing a
  patched APK to the system installer.
* Split installs are detected and reported. Only the base APK can be patched; if the
  bundle or native libraries live in a split, patching refuses and names the piece
  instead of shipping an incomplete app.
* Disk space is checked before work starts: a patch needs about three times the APK
  size, and running out halfway would produce a truncated file.

## Verification for this build

* 23 unit tests pass, including `InstalledDiscordTest` (real byte-for-byte copying,
  refusal messages for missing and undersized APKs, split-install summarising) and the
  full `PatchPipelineTest` patch/sign/align suite.
* Release APK: 9512342 bytes, signed, `apksigner verify` OK, SHA-256 `c24dc09ce9f6ea12a814ddee5b6251f8d834e354dd5a5372a9630da209a7e070`.

## Known limitations

* Automatic detection needs Android to let one app read another's APK. Most builds allow
  it; when it is refused the app says so and offers the file picker.
* CSS themes cannot style the patched app: Discord Android renders native React Native
  views with no DOM.
* A patched build uses a different signing key, so the official Discord must be
  uninstalled first and you will sign in again.
* The pipeline is verified on fixture APKs under JVM tests and the signature/alignment
  rules it enforces are the real ones, but it has not been run against a real Discord
  APK on a physical device here.
