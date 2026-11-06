# Nebula 1.0.0

Android host for BetterDiscord-format plugins (`.plugin.js`) and themes (`.theme.css`).

## Download

* `nebula-1.0.0.apk` (8.9 MB, signed release build)
* SHA-256: `2171cc23f364d657b8dcb6fab19d0c17856e3c81543009ff8efe61500e59ff93`

## Requirements

* Android 8.0 (API 26) or newer.
* arm64-v8a, armeabi-v7a, x86 or x86_64 device/emulator.

## Install

1. Download the APK.
2. Allow installation from unknown sources for your file manager/browser.
3. Open the APK to install. Package id: `io.nebula.mobile`.

The APK is signed with the repository's development key (`keystore/nebula-dev.jks`,
password `nebula`). Rebuild and re-sign with your own key before any real
distribution.

## What is included

* QuickJS runtime, one engine per plugin, running BetterDiscord `module.exports = class`
  plugins with `start()` / `stop()`.
* `BdApi`: Data, Patcher, Events, Logger, UI (native dialogs/toasts), Net (real HTTP),
  Plugins, Themes, and a native settings screen driven by a plugin-declared schema.
* Theme engine that parses BetterDiscord CSS, resolves `var()` chains and maps Discord
  custom properties onto the app palette.
* Four bundled sample plugins and two bundled themes, plus import by file, URL or pasted
  source.

## Known limitations

* Nebula does not modify the official Discord app. `BdApi.Webpack`, `DOM`, `React`,
  `Hooks`, `ContextMenu` and `Components` need Discord's renderer and throw an explicit
  error; plugins using them must be ported to the Nebula APIs.
* Themes are applied as palettes; selectors targeting Discord's DOM cannot be rendered.
* Plugin JavaScript runs on one dedicated thread and cannot be preempted mid-script.
