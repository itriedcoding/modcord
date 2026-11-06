# Nebula — an Android client-mod host for Discord addons

Nebula is an Android application that loads **BetterDiscord-format plugins**
(`.plugin.js`) and **BetterDiscord-format themes** (`.theme.css`), runs the plugins
in a sandboxed JavaScript engine, and paints its own UI with the theme's Discord
CSS custom properties.

It is a real, working application: a JavaScript engine (QuickJS), a BetterDiscord
`BdApi` implementation backed by real files, real function patching, real timers,
real HTTP, and a real theme engine. There are no mocked screens and no placeholder
"it works!" toasts.

It can also **modify the real Discord application**: the **Patch** tab injects that
plugin runtime into Discord's own React Native bundle, rebuilds the APK, signs it and
verifies the signature. See [Patching the Discord app itself](#patching-the-discord-app-itself).

## Download

Latest: **[Nebula 1.1.0](https://github.com/itriedcoding/modcord/releases/tag/v1.1.0)**

* **[dist/nebula-1.1.0.apk](dist/nebula-1.1.0.apk)** — 9.1 MB, signed release build
* SHA-256: `4132870b50541a3a60e1bcbe46f11db5540620a565384eb375b140a403c931bd`
* Release notes: [dist/RELEASE_NOTES_v1.1.0.md](dist/RELEASE_NOTES_v1.1.0.md)
* Previous build: [dist/nebula-1.0.0.apk](dist/nebula-1.0.0.apk) (`v1.0.0`)

`.github/workflows/publish-release.yml` attaches the newest `dist/nebula-*.apk` to its
release automatically, so the shipped artifact and the release cannot drift apart.

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
| Patching a real Discord APK | **Works** — bundle injection, entry-level ZIP rewrite with Android alignment, `apksig` v1+v2+v3, verified after signing; Hermes builds refused with a reason |
| `BdApi.Webpack`, `BdApi.DOM`, `BdApi.React`, `BdApi.Hooks`, `BdApi.ContextMenu`, `BdApi.Components` | **Not available** — these need Discord's own React/DOM renderer. Calling them throws a descriptive error. |

### Patching the Discord app itself

The **Patch** tab modifies a real Discord APK instead of hosting addons beside it:

1. The APK is opened as a ZIP and its React Native bundle
   (`assets/index.android.bundle`) is located.
2. The Nebula loader plus the source of every enabled plugin is appended to that
   bundle, wrapped in `try/catch` so a loader failure can never break Discord's own
   code.
3. The container is rewritten entry by entry, preserving each entry's compression
   method and aligning stored entries the way Android requires (native libraries
   4096-byte, `resources.arsc` and dex 4-byte). The old signature files are dropped.
4. The result is signed with v1 + v2 + v3 using `apksig` — the same implementation
   `apksigner` uses — with alignment preserved, then **verified again** before it is
   offered to the system installer.

Two cases are refused rather than faked:

* A build whose bundle is **Hermes bytecode** (starts with `C6 1F BC`). Bytecode is
  not JavaScript text, so a text injection cannot work; the patcher says so and
  produces no APK.
* A bundle that is neither Hermes nor valid UTF-8.

Once patched, the loader runs inside Discord's own JavaScript context and reads
Discord's metro module registry (`global.__r`), so `Patcher`, `Webpack.findByProps`
and the rest operate on the modules Discord actually uses. What it still cannot do is
style native React Native views: **CSS themes need a DOM, which Discord Android does
not have**, and the loader reports that instead of pretending a theme applied.
Plugins that need a DOM or React exports throw a descriptive error.

A patched build is signed with a different key than the official app, so Android
treats it as a separate install: uninstall the official Discord first, and sign in
again. No Discord code is redistributed here — the patcher takes an APK you already
have and writes a new one.

## Screens

* **Plugins** — installed plugins, enable/disable toggles, live "Running" state,
  per-plugin errors, detail sheet with metadata, API-footprint warning, source
  viewer, native settings screen and delete.
* **Themes** — installed themes, apply/remove, detected CSS variables, CSS viewer.
* **Patch** — pick an installed Discord APK, see which plugins will be embedded,
  patch and sign it, read the verification report, then install or share the result.
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
* **Patch pipeline** (`PatchPipelineTest`) builds a real fixture APK, patches it, and
  asserts that the output verifies with `apksig` (v1, v2 **and** v3), that untouched
  entries stay byte-identical, that the old signature is gone and the v1 manifest was
  regenerated for the patched bundle, that native libraries stay stored and
  4096-byte aligned, that Hermes and missing-bundle inputs are refused, and — when
  Node is installed — that the patched bundle still parses under `node --check`.

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
├── patch/
│   ├── ApkRewriter.kt        streaming ZIP rewrite with Android alignment rules
│   ├── BundlePatcher.kt      bundle discovery, Hermes detection, loader injection
│   ├── ApkSigning.kt         apksig v1+v2+v3 signing and verification
│   └── DiscordPatcher.kt     end-to-end patch orchestration
└── ui/                       MainActivity, fragments, adapters, dialogs
app/src/main/assets/
├── bdapi/
│   ├── prelude.js            host aliases, timers, callbacks, CommonJS shim
│   ├── bdapi.js              the BdApi compatibility layer
│   ├── bootstrap.js          module.exports -> instance -> start()
│   └── teardown.js           stop() + unpatch + clear timers
├── patcher/
│   ├── nebula-payload.js     loader injected into Discord's bundle
│   └── signing.jks           dev key used to sign patched builds
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

* Host mode has no Discord UI to render into: `Webpack`/`DOM`/`React`/`Hooks`/
  `ContextMenu`/`Components` throw by design, and theme selectors that target
  Discord's DOM are parsed but cannot be applied.
* Patched mode reaches Discord's real modules, but CSS themes still cannot apply:
  Discord Android renders native React Native views with no DOM. Theme files can be
  embedded, and the loader reports the limitation at runtime.
* Patched mode needs a Discord build that ships a plain JavaScript bundle;
  Hermes-bytecode builds are refused. The patch pipeline is verified on a fixture APK
  under JVM tests — it has **not** been run against a real Discord APK on a device
  here, because that means redistributing and testing a closed-source binary.
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
