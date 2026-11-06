# Nebula 1.5.0

**A Settings tab that does real work**, plus update checking, backups, a marketplace and
a local crash reporter. No placeholder screens: every control writes to storage or performs
the action it names.

## New features

| Feature | What is actually implemented |
| --- | --- |
| **Local settings** | Typed settings in one JSON file, written with temp-file + rename so a crash cannot tear it. |
| **Custom fonts** | Switches the app between the platform's real font families (`sans-serif`, `serif`, `monospace`, condensed, light, medium, casual, cursive) through `Typeface.create`, applied across the view tree. |
| **Custom animations** | Real `ViewPropertyAnimator` reveal for screens and sections, honoured by the "Custom animations" switch. |
| **Notification settings** | A real notification channel whose importance follows the setting (Min / Low / Default / High), recreated on change because Android channels are immutable, and a notification posted when a patch finishes. POST_NOTIFICATIONS is requested on Android 13+. |
| **Quick Settings tile** | A `TileService` that reads the real patch history and enabled-plugin count for its subtitle and opens Nebula when tapped. |
| **Automatic updates** | The GitHub Releases API, numeric version comparison (`1.10.0` > `1.9.9`), and a download that is only accepted when its SHA-256 matches the release's `.sha256` asset. The APK is handed to the system installer through the existing FileProvider. |
| **Backup and restore** | One JSON document with settings, state and the **full source** of every installed addon, exported and imported through the Storage Access Framework. Foreign files and newer formats are refused with a reason. |
| **Custom themes** | A theme builder that turns three colours into a real `.theme.css` with a META header; it then goes through the same parser and token bridge as an imported theme, and is applied immediately. |
| **Plugin marketplace** | A catalogue committed to this repository (`marketplace/index.json`, six entries), fetched from GitHub's raw host, with every download checked against its published SHA-256 before it reaches the addon store. |
| **Crash reports** | An uncaught-exception handler that writes the full stack trace to app storage (last 20 kept) and then delegates to Android's handler, so the crash dialog still appears. Viewable and clearable in Settings. |
| **Patch history in Settings** | Each recorded build with size, Discord version code, addon and colour-token counts. |

Themes in the patched app keep working through the colour-token bridge added in 1.4.0;
nothing about that path changed here beyond being documented.

## Not implemented, on purpose

* **Message customisation, custom profile themes and custom emoji rendering** in the
  patched Discord need Discord's own message/profile renderers. A hook that guesses at
  those internals would be a fake feature, so none was written.
* **Firebase Crashlytics** needs a Firebase project and `google-services.json`. Crash
  reporting is therefore local and real, and named accordingly.

## Verification for this build

* **48 unit tests, 0 failures** — new suites cover settings persistence (defaults,
  round-trip through a second instance, clamping, unknown keys surviving a rewrite),
  the backup format (round-trip, foreign file, newer format, entries without source),
  the update checker (real GitHub release shape, numeric version order, sidecar checksum,
  refusal when a release has no APK), the marketplace (path to raw URL, invalid checksum
  rejected, tampered bytes refused) and the theme builder (parsed by `MetaParser`,
  resolved by `ThemeTokens`, bad hex rejected).
* Release guards: version `1.5.0`, zero test failures, JNI descriptors intact in the
  built DEX, the patched-app loader still carrying the token bridge, the tile service
  present in the packaged manifest, and every catalogue checksum matching the file it
  points at.
* `apksigner verify` OK · 6272128 bytes (6.0 MB, was 5.9 MB) · SHA-256 `4dee0e8f6f667d92c09ab4bcdea94e7c25322f54ee428715192245cd0127bb37`.

## Known limitations

* No device or emulator here: fonts, animations, notifications, the tile, the installer
  hand-off and SAF backup are compiled and unit-tested but were not executed on hardware.
  Treat those as verified-by-build, not verified-by-use.
* Settings are a JSON document rather than Room. Room would add an annotation processor and
  a generated schema to a build that must stay small; the JSON store is real persistence on
  the same files.
* The marketplace catalogue lives in this repository, so it is only as fresh as the last push.
