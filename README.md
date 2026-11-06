# Nebula — an Android client-mod host for Discord addons

Nebula is an Android application that loads **BetterDiscord-format plugins**
(`.plugin.js`) and **BetterDiscord-format themes** (`.theme.css`), runs the plugins
in a sandboxed JavaScript engine, and paints its own UI with the theme's Discord
CSS custom properties.

It is a real, working application: a JavaScript engine (QuickJS), a BetterDiscord
`BdApi` implementation backed by real files, real function patching, real timers,
real HTTP, and a real theme engine. There are no mocked screens and no placeholder
"it works!" toasts.

## Download

* **[dist/nebula-1.0.0.apk](dist/nebula-1.0.0.apk)** — 8.9 MB, signed release build
* SHA-256: `2171cc23f364d657b8dcb6fab19d0c17856e3c81543009ff8efe61500e59ff93`
* Release notes for the `v1.0.0` tag: [dist/RELEASE_NOTES_v1.0.0.md](dist/RELEASE_NOTES_v1.0.0.md)

The same file is what should be attached to the GitHub release for `v1.0.0`.

## What actually works

| Area | Status |
| --- | --- |
| Plugin loading (`.plugin.js`, JSDoc or legacy `//META{}` header) | **Works** — real QuickJS evaluation, one runtime + context per plugin |
| `module.exports = class ... { start() {} stop() {} }` plugin shape | **Works** |
| `BdApi.Data.load/save/delete` | **Works** — real JSON files under app storage |
| `BdApi.Patcher.before/after/instead/unpatchAll/getOriginal` | **Works** — real function wrapping and restoration |
| `BdApi.Events.on/off/emit` | **Works** — real emitter, cleared on stop |
| `BdApi.Logger.*`, `console.*` | **Works** — in-app console tab + logcat |
| `BdApi.UI.showToast/showNotice/showConfirmationModal` | **Works** — native Android toast and dialogs |
| `BdApi.Net.fetch/get/post` | **Works** — real `HttpURLConnection` requests |
| `BdApi.Plugins.*`, `BdApi.Themes.*` | **Works** — enable/disable installed plugins and themes |
| `BdApi.Settings` (Nebula extension) | **Works** — plugin declares a schema, app renders a native settings screen |
| Timers (`setTimeout`, `setInterval`, `clear*`, `queueMicrotask`) | **Works** — real scheduler, cancelled when a plugin stops |
| Themes (`.theme.css`) | **Works** — CSS is parsed, `var()` resolved, Discord variables mapped to the app palette; applied instantly |
| `BdApi.Webpack`, `BdApi.DOM`, `BdApi.React`, `BdApi.Hooks`, `BdApi.ContextMenu`, `BdApi.Components` | **Not available** — these need Discord's own React/DOM renderer. Calling them throws a descriptive error. |

### About "the Discord app itself"

Nebula is a **host** for the addon API, not a repackaged copy of Discord.
Discord's Android application is closed source and is not redistributed here, so
the app cannot mount itself inside Discord's UI. Plugins that only use the data /
event / patcher / UI / network surface run unmodified; plugins that reach into
Discord's webpack modules and React tree cannot, and say so explicitly when
called. Nothing pretends otherwise, in the app or in this document.

## Screens

* **Plugins** — installed plugins, enable/disable toggles, live "Running" state,
  per-plugin errors, detail sheet with metadata, API-footprint warning, source
  viewer, native settings screen and delete.
* **Themes** — installed themes, apply/remove, detected CSS variables, CSS viewer.
* **Console** — live log stream from the runtime and plugins (level, scope, time).
* **About** — version, counts, storage paths and the compatibility notes above.

Import sources: file picker (SAF), URL download, or pasted source.

## Build

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease     # signed APK -> app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest   # JVM tests, including real plugin execution
```

A prebuilt APK is committed under [`dist/`](dist) for sideloading.
It is signed with the development key in `keystore/nebula-dev.jks`
(store password / key password: `nebula`). **Replace that key before publishing
anywhere real.**

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Two suites run:

* **JavaScript conformance** (`tools/js-runtime-test.mjs`, invoked by
  `JsConformanceTest`) loads the exact runtime assets the APK ships plus every
  bundled plugin into a real JavaScript engine and checks the host contract:
  `class` plugins instantiate with their META, `Patcher` really patches and
  restores `Math.max`, data round-trips, settings register and persist, timers fire
  and stop firing, UI callbacks fire, `BdApi.Net` performs a real HTTP request to a
  local server, unsupported Discord APIs throw, plugins without `start()` are
  rejected. Requires Node.js 18+ on `PATH` (tests only).
* **Kotlin/JVM** tests for the META parser, the CSS parser/`var()` resolver and
  palette mapping of both bundled themes.

On-device behaviour (dialogs, toasts, the QuickJS native bridge) is compiled and
unit-checked but, without an attached device or emulator, was not executed here;
see *Limitations*.

## Architecture

```
app/src/main/java/io/nebula/mobile/
├── NebulaApp.kt              app singleton: store, state, runtime, palette
├── PluginManager.kt          enable/disable orchestration + per-plugin errors
├── core/
│   ├── AddonMeta.kt          BetterDiscord META header parser (JSDoc + legacy JSON)
│   ├── AddonStore.kt         plugins/themes/data on disk, install, seed
│   ├── StateStore.kt         enabled sets, active theme, settings, accent override
│   └── NebulaLog.kt          in-memory log ring buffer
├── js/
│   ├── HostBridge.kt         the only platform surface addons can reach
│   └── PluginHost.kt         QuickJS runtime: one engine per plugin, timers, lifecycle
├── theme/
│   ├── CssParser.kt          real CSS parser + var() resolution
│   └── Palette.kt            Discord CSS variables -> app colours
├── android/AndroidBridge.kt  HostBridge implementation (toasts, dialogs, HTTP, storage)
└── ui/                       MainActivity, fragments, adapters, dialogs
app/src/main/assets/
├── bdapi/
│   ├── prelude.js            host aliases, timers, callbacks, CommonJS shim
│   ├── bdapi.js              the BdApi compatibility layer
│   ├── bootstrap.js          module.exports -> instance -> start()
│   └── teardown.js           stop() + unpatch + clear timers
└── addons/{plugins,themes}   bundled sample plugins and themes
tools/js-runtime-test.mjs     Node conformance suite for the JS layer
```

### Runtime isolation

* QuickJS runs every plugin in its **own runtime and context**; plugins cannot see
  each other's globals, and no Java class or object is exposed to addon code. The
  only bridge is the `__n*` host functions, which take and return strings, numbers
  and booleans.
* All addon code runs on one dedicated JavaScript thread. A runaway plugin can
  stall other plugins, but not the UI, and its timers are cancelled the moment it
  is disabled.
* Native `Promise` job draining is not pumped by this embedding, so the runtime
  ships a deterministic thenable for `BdApi.Net`; plugin `start()` should be
  synchronous (as in BetterDiscord itself).

## Writing a plugin

```js
/**
 * @name MyPlugin
 * @author You
 * @description What it does.
 * @version 1.0.0
 */
module.exports = class MyPlugin {
    constructor(meta) { this.meta = meta; }

    start() {
        BdApi.Settings.define([
            { key: "greeting", type: "text", label: "Greeting", default: "hi" }
        ]);
        BdApi.UI.showToast(BdApi.Settings.get("greeting", "hi"));
    }

    stop() {
        BdApi.Patcher.unpatchAll(this.meta.name);
    }
};
```

Drop the file in `app/src/main/assets/addons/plugins/` to bundle it, or import it
at runtime from the + button.

## Themes

```css
/**
 * @name MyTheme
 * @author You
 * @description Palette.
 * @version 1.0.0
 */
:root {
    --background-primary: #0b0716;
    --background-secondary: #120c22;
    --brand-experiment: #a855f7;
    --text-normal: #ece6ff;
}
```

Nebula reads `--background-primary`, `--background-secondary`,
`--background-tertiary`, `--brand-experiment`/`--brand-500`, `--text-normal`,
`--text-muted` and `--background-modifier-accent` (with fallbacks) and recolours
the app. Other declarations and Discord DOM selectors are parsed and preserved but
have nothing to style outside Discord.

## Limitations

* There is no Discord UI to render into: `Webpack`/`DOM`/`React`/`Hooks`/
  `ContextMenu`/`Components` throw by design, and theme selectors that target
  Discord's DOM are parsed but cannot be applied.
* QuickJS native calls are thread-affine; all plugin JavaScript runs on one thread.
  A plugin with a blocking infinite loop will stall other plugins until the app is
  restarted (the UI itself stays responsive).
* The embedded QuickJS build has no interrupt handler, so plugin execution cannot
  be preempted mid-script.
* The APK here is signed with the development key in `keystore/`; replace it for
  any real distribution.

## Licence

MIT for this project's code (see `LICENSE`). BetterDiscord plugin/theme files keep
their own authors' licences. Discord is a trademark of Discord Inc.; this project
is not affiliated with or endorsed by Discord or BetterDiscord.
