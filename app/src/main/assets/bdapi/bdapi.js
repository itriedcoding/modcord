/*
 * Nebula — BetterDiscord `BdApi` compatibility layer.
 *
 * Evaluated once per plugin inside that plugin's own JavaScript context, after
 * prelude.js. `__nebulaHost` (defined in the prelude) is the only bridge to the
 * Android host.
 *
 * Real implementations: Data (JSON files), Patcher (function wrapping), Events,
 * Logger, UI (native dialogs/toasts), Net (real HTTP), Plugins/Themes control,
 * Settings (native settings screen, a Nebula extension).
 *
 * Discord-renderer APIs (Webpack/DOM/React/Hooks/ContextMenu/Components) cannot
 * exist without Discord's own React tree and throw a descriptive error.
 */

const __nebulaBridge = __nebulaHost;
const __pluginId = __nebulaPluginId;
const __pluginName = __nebulaPluginName;

function toText(value) {
    if (typeof value === "string") return value;
    if (value === undefined) return "undefined";
    if (value === null) return "null";
    if (typeof value === "function") return "function " + (value.name || "anonymous");
    if (value instanceof Error) return value.message;
    try {
        return JSON.stringify(value, null, 2);
    } catch (error) {
        return String(value);
    }
}

function joinArgs(args, skipFirst) {
    const parts = [];
    for (let i = skipFirst ? 1 : 0; i < args.length; i++) parts.push(toText(args[i]));
    return parts.join(" ");
}

/* ------------------------------------------------------------------------- */
/* Logger                                                                     */
/* ------------------------------------------------------------------------- */

function logWith(level, args) {
    const skipFirst = typeof args[0] === "string" && args.length > 1;
    const text = joinArgs(args, skipFirst);
    __nebulaBridge.log(level, text);
}

const Logger = {
    log(...args) { logWith("log", args); },
    info(...args) { logWith("info", args); },
    warn(...args) { logWith("warn", args); },
    error(...args) { logWith("error", args); },
    debug(...args) { logWith("debug", args); },
    trace(...args) { logWith("debug", args); },
    stacktrace(...args) { logWith("error", args); }
};

/* ------------------------------------------------------------------------- */
/* Data                                                                       */
/* ------------------------------------------------------------------------- */

function dataName(fileName) {
    let name = fileName === undefined || fileName === null ? "data" : String(fileName);
    if (!name) name = "data";
    if (!/\.json$/i.test(name)) name += ".json";
    return name;
}

const Data = {
    load(fileName) {
        const raw = __nebulaBridge.dataLoad(dataName(fileName));
        if (!raw) return {};
        try {
            return JSON.parse(raw);
        } catch (error) {
            Logger.error(__pluginName, "stored data is not valid JSON:", error.message);
            return {};
        }
    },
    save(fileName, data) {
        if (data === undefined) {
            data = fileName;
            fileName = "data";
        }
        __nebulaBridge.dataSave(dataName(fileName), JSON.stringify(data === undefined ? {} : data, null, 2));
    },
    delete(fileName) {
        __nebulaBridge.dataDelete(dataName(fileName));
    }
};

/* ------------------------------------------------------------------------- */
/* Patcher                                                                    */
/* ------------------------------------------------------------------------- */

const patches = new Map();
let patchSequence = 0;

function resolvePatchArgs(args) {
    if (typeof args[0] === "string") {
        return { caller: args[0], target: args[1], key: args[2], callback: args[3] };
    }
    return { caller: __pluginName, target: args[0], key: args[1], callback: args[2] };
}

function unpatch(id) {
    const entry = patches.get(id);
    if (!entry) return;
    if (entry.target[entry.key] === entry.wrapper) {
        entry.target[entry.key] = entry.original;
    }
    patches.delete(id);
    entry.cancelled = true;
}

function addPatch(type, rawArgs) {
    const spec = resolvePatchArgs(rawArgs);
    const { target, key, callback } = spec;

    if (!target || key === undefined || key === null) {
        Logger.error(__pluginName, "Patcher." + type + " needs a target and a function name");
        return null;
    }
    if (typeof callback !== "function") {
        Logger.error(__pluginName, "Patcher." + type + " needs a callback function");
        return null;
    }
    const original = target[key];
    if (typeof original !== "function") {
        Logger.error(__pluginName, "Patcher." + type + ": '" + String(key) + "' is not a function");
        return null;
    }

    const wrapper = function (...callArgs) {
        if (type === "before") {
            const replacement = callback.call(this, this, callArgs, original);
            const finalArgs = Array.isArray(replacement) ? replacement : callArgs;
            return original.apply(this, finalArgs);
        }
        if (type === "instead") {
            return callback.call(this, this, callArgs, original);
        }
        const result = original.apply(this, callArgs);
        const patched = callback.call(this, this, callArgs, result);
        return patched === undefined ? result : patched;
    };
    wrapper.__nebulaOriginal = original;
    wrapper.__nebulaPatched = true;

    target[key] = wrapper;
    const id = ++patchSequence;
    patches.set(id, {
        caller: spec.caller || __pluginName,
        target,
        key,
        original,
        wrapper,
        type
    });
    return function cancel() { unpatch(id); };
}

const Patcher = {
    before(...args) { return addPatch("before", args); },
    after(...args) { return addPatch("after", args); },
    instead(...args) { return addPatch("instead", args); },
    unpatchAll(caller) {
        const who = caller === undefined ? __pluginName : String(caller);
        for (const [id, entry] of patches) {
            if (entry.caller === who) unpatch(id);
        }
    },
    getPatchesByCaller(caller) {
        const who = caller === undefined ? __pluginName : String(caller);
        const list = [];
        for (const [id, entry] of patches) {
            if (entry.caller !== who) continue;
            list.push({
                id,
                type: entry.type,
                key: entry.key,
                cancel() { unpatch(id); }
            });
        }
        return list;
    },
    getOriginal(fn) {
        let current = fn;
        while (current && current.__nebulaOriginal) current = current.__nebulaOriginal;
        return current;
    }
};

/* ------------------------------------------------------------------------- */
/* Events                                                                     */
/* ------------------------------------------------------------------------- */

const listeners = new Map();

const Events = {
    on(event, callback) {
        if (typeof callback !== "function") return function () {};
        const key = String(event);
        if (!listeners.has(key)) listeners.set(key, []);
        listeners.get(key).push(callback);
        return function () { Events.off(key, callback); };
    },
    off(event, callback) {
        const key = String(event);
        const list = listeners.get(key);
        if (!list) return;
        listeners.set(key, list.filter((fn) => fn !== callback));
    },
    emit(event, ...args) {
        const key = String(event);
        const list = (listeners.get(key) || []).slice();
        for (const listener of list) {
            try {
                listener(...args);
            } catch (error) {
                Logger.error(__pluginName, "event handler for '" + key + "' failed:", error.message);
            }
        }
    }
};

/* ------------------------------------------------------------------------- */
/* UI                                                                         */
/* ------------------------------------------------------------------------- */

const UI = {
    showToast(content, options) {
        __nebulaBridge.toast(typeof content === "string" ? content : toText(content));
        if (options && typeof options.callback === "function") options.callback();
    },
    showNotice(content, options) {
        const opts = options || {};
        __nebulaBridge.notice(String(opts.title || __pluginName), typeof content === "string" ? content : toText(content));
    },
    showConfirmationModal(title, content, options) {
        const opts = options || {};
        const text = typeof content === "string" ? content : toText(content);
        __nebulaBridge.confirm(
            __nebulaCallbacks.register((confirmed) => {
                try {
                    if (confirmed) {
                        if (typeof opts.onConfirm === "function") opts.onConfirm();
                    } else if (typeof opts.onCancel === "function") {
                        opts.onCancel();
                    }
                } catch (error) {
                    Logger.error(__pluginName, "confirmation callback failed:", error.message);
                }
            }),
            String(title || __pluginName),
            text,
            String(opts.confirmText || "OK"),
            String(opts.cancelText || "Cancel")
        );
    },
    alert(title, content) {
        __nebulaBridge.notice(String(title || __pluginName), typeof content === "string" ? content : toText(content));
    }
};

/* ------------------------------------------------------------------------- */
/* Plugins / Themes                                                           */
/* ------------------------------------------------------------------------- */

function parseJsonList(raw) {
    try {
        const parsed = JSON.parse(raw || "[]");
        return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
        return [];
    }
}

function findAddon(raw, id) {
    const wanted = String(id);
    return parseJsonList(raw).find((entry) => entry.id === wanted || entry.name === wanted) || null;
}

const Plugins = {
    isEnabled(id) {
        const plugin = findAddon(__nebulaBridge.listPlugins(), id);
        return plugin ? !!plugin.enabled : false;
    },
    enable(id) { return __nebulaBridge.setPluginEnabled(String(id), true); },
    disable(id) { return __nebulaBridge.setPluginEnabled(String(id), false); },
    toggle(id) { return __nebulaBridge.setPluginEnabled(String(id), !Plugins.isEnabled(id)); },
    get(id) { return findAddon(__nebulaBridge.listPlugins(), id); },
    get list() { return parseJsonList(__nebulaBridge.listPlugins()); }
};

const Themes = {
    isEnabled(id) {
        const theme = findAddon(__nebulaBridge.listThemes(), id);
        return theme ? !!theme.enabled : false;
    },
    enable(id) { return __nebulaBridge.setThemeEnabled(String(id), true); },
    disable(id) { return __nebulaBridge.setThemeEnabled(String(id), false); },
    get(id) { return findAddon(__nebulaBridge.listThemes(), id); },
    get list() { return parseJsonList(__nebulaBridge.listThemes()); },

    /* Nebula extensions: the accent lives in the host, not in Discord. */
    setAccent(hex) {
        return __nebulaBridge.setAccent(hex === undefined || hex === null ? "" : String(hex));
    },
    getAccent() {
        try {
            return JSON.parse(__nebulaBridge.themeVariables())["--nebula-accent"] || null;
        } catch (error) {
            return null;
        }
    },
    variables() {
        try {
            return JSON.parse(__nebulaBridge.themeVariables());
        } catch (error) {
            return {};
        }
    }
};

/* ------------------------------------------------------------------------- */
/* Net                                                                        */
/* ------------------------------------------------------------------------- */

function thenable(executor) {
    const state = { settled: false, ok: false, value: undefined, handlers: [], rejectHandler: null };

    function run() {
        if (!state.settled) return;
        if (!state.ok) {
            if (state.rejectHandler) {
                const rejecter = state.rejectHandler;
                state.rejectHandler = null;
                rejecter(state.value);
            }
            return;
        }
        while (state.handlers.length) {
            const handler = state.handlers.shift();
            try {
                handler(state.value);
            } catch (error) {
                Logger.error(__pluginName, error.message);
            }
        }
    }

    const promise = {
        then(onFulfilled, onRejected) {
            if (typeof onFulfilled === "function") state.handlers.push(onFulfilled);
            if (typeof onRejected === "function") state.rejectHandler = onRejected;
            setTimeout(run, 0);
            return promise;
        },
        catch(onRejected) { return promise.then(null, onRejected); },
        finally(callback) {
            return promise.then(
                (value) => { callback(); return value; },
                (reason) => { callback(); throw reason; }
            );
        }
    };

    executor(
        (value) => { state.settled = true; state.ok = true; state.value = value; setTimeout(run, 0); },
        (reason) => { state.settled = true; state.ok = false; state.value = reason; setTimeout(run, 0); }
    );
    return promise;
}

const Net = {
    fetch(url, options) {
        const opts = options || {};
        const headers = opts.headers || {};
        const body = opts.body === undefined || opts.body === null ? "" : String(opts.body);
        return thenable((resolve) => {
            __nebulaBridge.fetch(
                __nebulaCallbacks.register((ok, status, text) => {
                    resolve({
                        ok: !!ok,
                        status: Number(status),
                        statusText: String(status),
                        headers: {},
                        text: () => thenable((done) => done(text)),
                        json: () => thenable((done, fail) => {
                            try {
                                done(JSON.parse(text));
                            } catch (error) {
                                fail(error);
                            }
                        })
                    });
                }),
                String(url),
                String(opts.method || "GET"),
                JSON.stringify(headers),
                body
            );
        });
    },
    get(url, options) {
        const opts = options || {};
        opts.method = "GET";
        return Net.fetch(url, opts);
    },
    post(url, options) {
        const opts = options || {};
        opts.method = "POST";
        return Net.fetch(url, opts);
    }
};

/* ------------------------------------------------------------------------- */
/* Settings (Nebula extension: native settings screen)                        */
/* ------------------------------------------------------------------------- */

let settingsListeners = [];

function readSettings() {
    try {
        const parsed = JSON.parse(__nebulaBridge.loadSettings() || "{}");
        return parsed && typeof parsed === "object" ? parsed : {};
    } catch (error) {
        return {};
    }
}

const Settings = {
    /**
     * Declares a native settings screen.
     * [{ key, type: "boolean"|"text"|"number"|"select", label, description, default, options }]
     */
    define(schema) {
        return __nebulaBridge.registerSettings(JSON.stringify(schema || []));
    },
    all() { return readSettings(); },
    get(key, fallback) {
        const value = readSettings()[String(key)];
        return value === undefined ? fallback : value;
    },
    set(key, value) {
        __nebulaBridge.saveSetting(String(key), JSON.stringify(value === undefined ? null : value));
        Settings.notify();
        return value;
    },
    onChange(callback) {
        if (typeof callback !== "function") return function () {};
        settingsListeners.push(callback);
        return function () {
            settingsListeners = settingsListeners.filter((fn) => fn !== callback);
        };
    },
    notify() {
        const snapshot = readSettings();
        for (const listener of settingsListeners.slice()) {
            try {
                listener(snapshot);
            } catch (error) {
                Logger.error(__pluginName, error.message);
            }
        }
    }
};

/* ------------------------------------------------------------------------- */
/* Discord-renderer APIs that cannot exist here                              */
/* ------------------------------------------------------------------------- */

function unavailable(name) {
    const fail = () => {
        throw new Error(
            "BdApi." + name + " is not available in Nebula on Android. It requires Discord's own " +
            "React/DOM renderer, which a standalone Android mod host cannot provide. Port the plugin " +
            "to the Nebula APIs (Data, Patcher, Events, UI, Settings, Net)."
        );
    };
    const stub = {};
    const methods = [
        "getModule", "getByProps", "getByPrototype", "getByDisplayName", "getByStrings",
        "getWithKey", "getStore", "getStoreByName", "waitForModule", "find", "findAll",
        "getAll", "getAllByProps", "getAllByPrototype", "getAllByDisplayName",
        "getAllByStrings", "getAllByKeys", "findByProps", "findByPrototype",
        "findByDisplayName", "findByStrings", "findWithKey", "createElement",
        "createContext", "useState", "useEffect", "useMemo", "useCallback", "useRef",
        "createAddonPanel", "createItem", "patchElement", "getDefaultInstance",
        "get", "set", "add", "remove", "render", "patch", "unpatch"
    ];
    for (const method of methods) stub[method] = fail;
    return stub;
}

/* ------------------------------------------------------------------------- */
/* Teardown                                                                   */
/* ------------------------------------------------------------------------- */

function teardown() {
    for (const [id, entry] of Array.from(patches)) {
        if (entry.caller === __pluginName || entry.caller === __pluginId) unpatch(id);
    }
    listeners.clear();
    settingsListeners = [];
}

const BdApi = {
    version: "nebula-1.0.0",
    Logger,
    Data,
    Patcher,
    Events,
    UI,
    Net,
    Plugins,
    Themes,
    Settings,
    Webpack: unavailable("Webpack"),
    DOM: unavailable("DOM"),
    React: unavailable("React"),
    ReactDOM: unavailable("ReactDOM"),
    Hooks: unavailable("Hooks"),
    Components: unavailable("Components"),
    ContextMenu: unavailable("ContextMenu"),
    Utils: unavailable("Utils")
};

globalThis.BdApi = BdApi;

globalThis.console = {
    log(...args) { logWith("log", args); },
    info(...args) { logWith("info", args); },
    warn(...args) { logWith("warn", args); },
    error(...args) { logWith("error", args); },
    debug(...args) { logWith("debug", args); },
    trace(...args) { logWith("debug", args); },
    dir(...args) { logWith("log", args); },
    assert(condition, ...args) {
        if (!condition) logWith("error", ["assertion failed", ...args]);
    },
    time() {},
    timeEnd() {}
};

globalThis.__nebulaTeardown = teardown;
globalThis.__nebulaSettingsChanged = function () { Settings.notify(); };
