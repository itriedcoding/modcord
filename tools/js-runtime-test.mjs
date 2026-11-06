#!/usr/bin/env node
/*
 * Conformance tests for Nebula's JavaScript layer.
 *
 * These tests execute the *same* files the APK ships
 * (app/src/main/assets/bdapi/{prelude,bdapi,bootstrap,teardown}.js) plus the
 * bundled plugins, in a real JavaScript engine (the Node.js V8 VM), against an
 * implementation of the host contract identical to AndroidBridge.
 *
 * Run: node tools/js-runtime-test.mjs
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = path.join(ROOT, "app", "src", "main", "assets");
const readAsset = (...parts) => fs.readFileSync(path.join(ASSETS, ...parts), "utf8");

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ------------------------------------------------------------------------- */
/* Host surface (mirrors AndroidBridge)                                       */
/* ------------------------------------------------------------------------- */

class Host {
    constructor() {
        this.logs = [];
        this.toasts = [];
        this.notices = [];
        this.confirms = [];
        this.data = new Map();
        this.schemas = new Map();
        this.settings = new Map();
        this.accents = [];
        this.pluginStates = [];
        this.pluginsJson = JSON.stringify([
            { id: "demo", name: "Demo", version: "1.0.0", author: "Test", enabled: true, running: true },
        ]);
        this.themesJson = JSON.stringify([
            { id: "purple", name: "Purple", version: "1.0.0", author: "Test", enabled: false, running: false },
        ]);
    }

    messages() {
        return this.logs.map((entry) => entry.message);
    }

    log(level, scope, message) {
        this.logs.push({ level, scope, message });
    }
}

/* ------------------------------------------------------------------------- */
/* Plugin runtime (mirrors PluginHost on Android)                             */
/* ------------------------------------------------------------------------- */

class PluginRuntime {
    constructor(id, name, meta, host, serverBase) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.serverBase = serverBase;
        this.timers = new Map();

        const context = vm.createContext({ console });
        this.context = context;

        context.__nPluginId = id;
        context.__nPluginName = name;
        context.__nMetaJson = JSON.stringify(meta || {});

        context.__nLog = (level, message) => {
            host.log(String(level), name, String(message));
        };
        context.__nDataLoad = (file) => host.data.get(`${id}/${file}`) ?? "";
        context.__nDataSave = (file, json) => {
            host.data.set(`${id}/${file}`, String(json));
            return true;
        };
        context.__nDataDelete = (file) => host.data.delete(`${id}/${file}`);
        context.__nToast = (message) => host.toasts.push(String(message));
        context.__nNotice = (title, body) => host.notices.push([String(title), String(body)]);
        context.__nConfirm = (callbackId, title, body, okLabel, cancelLabel) => {
            host.confirms.push({ title, body, okLabel, cancelLabel });
            this.invokeCallback(callbackId, [true]);
        };
        context.__nThemeVariables = () => JSON.stringify({ "--nebula-accent": "#7C5CFC" });
        context.__nSetAccent = (hex) => {
            host.accents.push(hex === "" ? null : String(hex));
            return true;
        };
        context.__nListPlugins = () => host.pluginsJson;
        context.__nSetPluginEnabled = (pluginId, enabled) => {
            host.pluginStates.push([String(pluginId), !!enabled]);
            return true;
        };
        context.__nListThemes = () => host.themesJson;
        context.__nSetThemeEnabled = () => true;
        context.__nRegisterSettings = (json) => {
            host.schemas.set(id, String(json));
            return true;
        };
        context.__nLoadSettings = () => {
            const result = {};
            for (const [key, value] of host.settings) {
                if (!key.startsWith(`${id}.`)) continue;
                result[key.slice(id.length + 1)] = JSON.parse(value);
            }
            return JSON.stringify(result);
        };
        context.__nSaveSetting = (key, json) => {
            host.settings.set(`${id}.${key}`, String(json));
            return true;
        };
        context.__nFetch = (callbackId, url, method, headersJson, body) => {
            (async () => {
                try {
                    const response = await fetch(url, {
                        method,
                        headers: JSON.parse(headersJson || "{}"),
                        body: method === "GET" || !body ? undefined : body,
                    });
                    const text = await response.text();
                    this.invokeCallback(callbackId, [response.ok, response.status, text]);
                } catch (error) {
                    this.invokeCallback(callbackId, [false, 0, String(error.message || error)]);
                }
            })();
        };
        context.__nCancelFetch = () => {};
        context.__nScheduleTimer = (timerId, delay, repeat) => {
            const key = Number(timerId);
            const run = () => {
                try {
                    this.evaluate(`__nebulaTimers.tick(${key})`, "timer.js");
                } catch (error) {
                    host.log("error", name, String(error && error.message ? error.message : error));
                }
            };
            const handle = repeat
                ? setInterval(run, Math.max(16, Number(delay) || 16))
                : setTimeout(run, Number(delay) || 0);
            this.timers.set(key, handle);
        };
        context.__nCancelTimer = (timerId) => {
            const key = Number(timerId);
            const handle = this.timers.get(key);
            if (!handle) return;
            clearTimeout(handle);
            clearInterval(handle);
            this.timers.delete(key);
        };

        this.evaluate(readAsset("bdapi", "prelude.js"), "prelude.js");
        this.evaluate(readAsset("bdapi", "bdapi.js"), "bdapi.js");
    }

    evaluate(code, filename) {
        return vm.runInContext(code, this.context, { filename });
    }

    load(source, filename) {
        this.evaluate(source, filename);
    }

    start() {
        this.evaluate(readAsset("bdapi", "bootstrap.js"), "bootstrap.js");
    }

    stop() {
        this.evaluate(readAsset("bdapi", "teardown.js"), "teardown.js");
    }

    invokeCallback(callbackId, args) {
        this.evaluate(
            `__nebulaCallbacks.invoke(${Number(callbackId)}, ${JSON.stringify(JSON.stringify(args))})`,
            "callback.js",
        );
    }

    dispose() {
        for (const handle of this.timers.values()) {
            clearTimeout(handle);
            clearInterval(handle);
        }
        this.timers.clear();
    }
}

/* ------------------------------------------------------------------------- */
/* Test plumbing                                                              */
/* ------------------------------------------------------------------------- */

let passed = 0;
let failed = 0;

async function test(name, body) {
    try {
        await body();
        passed++;
        console.log(`PASS  ${name}`);
    } catch (error) {
        failed++;
        console.error(`FAIL  ${name}`);
        console.error(`      ${error && error.message ? error.message : error}`);
    }
}

function pluginFiles() {
    const dir = path.join(ASSETS, "addons", "plugins");
    return fs
        .readdirSync(dir)
        .filter((name) => name.endsWith(".plugin.js"))
        .map((name) => ({ name, source: fs.readFileSync(path.join(dir, name), "utf8") }));
}

function metaOf(source) {
    const block = source.match(/\/\*\*([\s\S]*?)\*\//);
    const meta = {};
    if (!block) return meta;
    for (const line of block[1].split("\n")) {
        const match = line.match(/^\s*\*\s*@(\w+)\s+(.*)$/);
        if (match) meta[match[1].toLowerCase()] = match[2].trim();
    }
    return meta;
}

const server = http.createServer((request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ ok: true, path: request.url }));
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const baseUrl = `http://127.0.0.1:${server.address().port}`;

/* ------------------------------------------------------------------------- */
/* Tests                                                                      */
/* ------------------------------------------------------------------------- */

await test("every bundled plugin starts and stops", async () => {
    const files = pluginFiles();
    assert.ok(files.length >= 4, "expected bundled plugins");
    for (const file of files) {
        const host = new Host();
        const meta = metaOf(file.source);
        const runtime = new PluginRuntime("test-" + file.name, meta.name || file.name, meta, host, baseUrl);
        runtime.load(file.source, file.name);
        runtime.start();
        runtime.stop();
        runtime.dispose();
    }
});

await test("class plugins receive their META object", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("meta", "MetaPlugin", { name: "MetaPlugin", version: "3.4.5" }, host, baseUrl);
    runtime.load(
        `module.exports = class MetaPlugin {
            constructor(meta) { this.meta = meta; }
            start() { BdApi.Logger.info(this.meta.name, "meta version " + this.meta.version); }
            stop() {}
        };`,
        "meta.plugin.js",
    );
    runtime.start();
    assert.ok(host.messages().some((m) => m.includes("meta version 3.4.5")), host.messages().join("\n"));
    runtime.stop();
});

await test("Patcher wraps and restores functions", async () => {
    const host = new Host();
    const source = pluginFiles().find((file) => file.name.includes("patch-demo")).source;
    const runtime = new PluginRuntime("patchdemo", "PatchDemo", metaOf(source), host, baseUrl);
    runtime.load(source, "patch-demo.plugin.js");
    runtime.start();

    const started = host.messages().find((message) => message.includes("greet =>"));
    assert.ok(started, "patch demo did not log");
    assert.match(started, /HELLO NEBULA \(PATCHED\)/);
    assert.match(started, /Math\.max => 4242/);

    runtime.stop();
    const stopped = host.messages().find((message) => message.includes("patches restored"));
    assert.ok(stopped, "teardown did not log");
    assert.match(stopped, /Math\.max\(1, 2\) => 2/);
});

await test("Data round-trips through the host", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("dataplugin", "DataPlugin", {}, host, baseUrl);
    runtime.load(
        `module.exports = class DataPlugin {
            start() {
                BdApi.Data.save("config", { seen: 7, label: "nebula" });
                const loaded = BdApi.Data.load("config");
                BdApi.Logger.info("DataPlugin", "loaded " + loaded.seen + " " + loaded.label);
            }
            stop() {}
        };`,
        "data.plugin.js",
    );
    runtime.start();
    const stored = host.data.get("dataplugin/config.json");
    assert.ok(stored && stored.includes('"seen": 7'), "data not persisted: " + stored);
    assert.ok(host.messages().some((m) => m.includes("loaded 7 nebula")), host.messages().join("\n"));
});

await test("Settings schema registers and values persist", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("settingsplugin", "SettingsPlugin", {}, host, baseUrl);
    runtime.load(
        `module.exports = class SettingsPlugin {
            start() {
                BdApi.Settings.define([
                    { key: "mode", type: "select", label: "Mode",
                      options: [{ label: "A", value: "a" }, { label: "B", value: "b" }], default: "a" },
                    { key: "enabled", type: "boolean", label: "Enabled", default: true }
                ]);
                BdApi.Settings.set("mode", "b");
                BdApi.Logger.info("SettingsPlugin", "mode=" + BdApi.Settings.get("mode"));
            }
            stop() {}
        };`,
        "settings.plugin.js",
    );
    runtime.start();
    assert.ok((host.schemas.get("settingsplugin") || "").includes('"mode"'), "schema missing");
    assert.equal(host.settings.get("settingsplugin.mode"), '"b"');
    assert.ok(host.messages().some((m) => m.includes("mode=b")), host.messages().join("\n"));
});

await test("Settings.onChange fires when a setting changes", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("changeplugin", "ChangePlugin", {}, host, baseUrl);
    runtime.load(
        `module.exports = class ChangePlugin {
            start() {
                BdApi.Settings.onChange(function (values) {
                    BdApi.Logger.info("ChangePlugin", "changed mode=" + values.mode);
                });
                BdApi.Settings.set("mode", "z");
            }
            stop() {}
        };`,
        "change.plugin.js",
    );
    runtime.start();
    assert.ok(host.messages().some((m) => m.includes("changed mode=z")), host.messages().join("\n"));
});

await test("unsupported Discord APIs fail loudly", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("needswebpack", "NeedsWebpack", {}, host, baseUrl);
    runtime.load(
        `module.exports = class NeedsWebpack {
            start() { BdApi.Webpack.getModule(function () { return true; }); }
            stop() {}
        };`,
        "webpack.plugin.js",
    );
    assert.throws(() => runtime.start(), /not available in Nebula/);
});

await test("plugins without start() are rejected clearly", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("broken", "Broken", {}, host, baseUrl);
    runtime.load(`module.exports = class Broken {};`, "broken.plugin.js");
    assert.throws(() => runtime.start(), /does not define start\(\)/);
});

await test("timers fire and stop firing after stop()", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("timerplugin", "TimerPlugin", {}, host, baseUrl);
    runtime.load(
        `module.exports = class TimerPlugin {
            start() {
                setTimeout(function () { BdApi.Logger.info("TimerPlugin", "timeout fired"); }, 20);
                setInterval(function () { BdApi.Logger.info("TimerPlugin", "interval fired"); }, 20);
            }
            stop() {}
        };`,
        "timer.plugin.js",
    );
    runtime.start();

    const deadline = Date.now() + 2000;
    while (Date.now() < deadline && !host.messages().some((m) => m.includes("timeout fired"))) {
        await sleep(20);
    }
    assert.ok(host.messages().some((m) => m.includes("timeout fired")), "timeout never fired");

    runtime.stop();
    await sleep(100);
    const before = host.messages().filter((m) => m.includes("interval fired")).length;
    await sleep(200);
    const after = host.messages().filter((m) => m.includes("interval fired")).length;
    assert.equal(after, before, "interval kept running after stop()");
    runtime.dispose();
});

await test("UI toast, notice and confirmation reach the host and call back", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("uiplugin", "UiPlugin", {}, host, baseUrl);
    runtime.load(
        `module.exports = class UiPlugin {
            start() {
                BdApi.UI.showToast("hello from ui");
                BdApi.UI.showNotice("Notice body", { title: "Notice title" });
                BdApi.UI.showConfirmationModal("Confirm?", "Really?", {
                    confirmText: "Yes",
                    cancelText: "No",
                    onConfirm: function () { BdApi.Logger.info("UiPlugin", "confirmed"); }
                });
            }
            stop() {}
        };`,
        "ui.plugin.js",
    );
    runtime.start();
    assert.deepEqual(host.toasts, ["hello from ui"]);
    assert.deepEqual(host.notices[0], ["Notice title", "Notice body"]);
    assert.equal(host.confirms[0].title, "Confirm?");
    assert.equal(host.confirms[0].okLabel, "Yes");
    assert.ok(host.messages().some((m) => m.includes("confirmed")), host.messages().join("\n"));
});

await test("Net performs a real request and resolves its thenable", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("netplugin", "NetPlugin", {}, host, baseUrl);
    runtime.load(
        `module.exports = class NetPlugin {
            start() {
                BdApi.Net.fetch("${baseUrl}/probe").then(function (response) {
                    response.json().then(function (body) {
                        BdApi.Logger.info("NetPlugin", "status " + response.status + " ok " + response.ok + " path " + body.path);
                    });
                });
            }
            stop() {}
        };`,
        "net.plugin.js",
    );
    runtime.start();

    const deadline = Date.now() + 3000;
    while (Date.now() < deadline && !host.messages().some((m) => m.includes("status 200"))) {
        await sleep(20);
    }
    const line = host.messages().find((m) => m.includes("status 200"));
    assert.ok(line, "net request never resolved: " + host.messages().join(" | "));
    assert.match(line, /ok true path \/probe/);
    runtime.dispose();
});

await test("accent plugin reaches the appearance bridge", async () => {
    const host = new Host();
    const source = pluginFiles().find((file) => file.name.includes("accent-cycle")).source;
    const runtime = new PluginRuntime("accentcycle", "AccentCycle", metaOf(source), host, baseUrl);
    runtime.load(source, "accent-cycle.plugin.js");
    runtime.start();
    assert.ok(host.accents.length > 0, "accent bridge not called");
    assert.match(host.accents[0], /^#[0-9A-Fa-f]{6}$/);
    runtime.stop();
    runtime.dispose();
});

await test("JVM/Java access is not exposed", async () => {
    const host = new Host();
    const runtime = new PluginRuntime("javaaccess", "JavaAccess", {}, host, baseUrl);
    runtime.load(
        `module.exports = class JavaAccess {
            start() { new java.io.File("/etc/passwd"); }
            stop() {}
        };`,
        "java.plugin.js",
    );
    assert.throws(() => runtime.start(), /java is not defined|java/);
});

/* ------------------------------------------------------------------------- */

server.close();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
