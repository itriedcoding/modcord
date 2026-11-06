/*
 * Nebula runtime prelude.
 *
 * Evaluated once per plugin, before bdapi.js and before the plugin source.
 * Everything here is plain JavaScript; the only things that cross into the
 * Android host are the `__n*` functions, which the Kotlin runtime injects.
 *
 * Timers and UI/network callbacks stay inside this JavaScript context: the host
 * only wakes the engine up (`__nebulaTimers.tick(id)`) or hands back a result
 * (`__nebulaCallbacks.invoke(id, json)`). That keeps the JNI boundary to strings
 * and primitives.
 */

var __nebulaPluginId = String(__nPluginId);
var __nebulaPluginName = String(__nPluginName);
var __nebulaMeta = (function () {
    try {
        return JSON.parse(__nMetaJson || "{}");
    } catch (error) {
        return {};
    }
})();

var __nebulaHost = {
    log: __nLog,
    dataLoad: __nDataLoad,
    dataSave: __nDataSave,
    dataDelete: __nDataDelete,
    toast: __nToast,
    notice: __nNotice,
    confirm: __nConfirm,
    themeVariables: __nThemeVariables,
    setAccent: __nSetAccent,
    listPlugins: __nListPlugins,
    setPluginEnabled: __nSetPluginEnabled,
    listThemes: __nListThemes,
    setThemeEnabled: __nSetThemeEnabled,
    registerSettings: __nRegisterSettings,
    loadSettings: __nLoadSettings,
    saveSetting: __nSaveSetting,
    fetch: __nFetch,
    cancelFetch: __nCancelFetch
};

var __nebulaTimers = (function () {
    var sequence = 0;
    var entries = {};

    function schedule(callback, delay, repeat) {
        if (typeof callback !== "function") return 0;
        var id = ++sequence;
        entries[id] = { callback: callback, repeat: repeat };
        __nScheduleTimer(id, Math.max(0, Number(delay) || 0), repeat ? 1 : 0);
        return id;
    }

    function clear(id) {
        var key = Number(id);
        if (!entries[key]) return;
        delete entries[key];
        __nCancelTimer(key);
    }

    function tick(id) {
        var key = Number(id);
        var entry = entries[key];
        if (!entry) return;
        if (!entry.repeat) delete entries[key];
        try {
            entry.callback();
        } catch (error) {
            __nLog("error", "timer callback failed: " + (error && error.message ? error.message : String(error)));
        }
    }

    function clearAll() {
        Object.keys(entries).forEach(function (id) {
            __nCancelTimer(Number(id));
        });
        entries = {};
    }

    return { schedule: schedule, clear: clear, tick: tick, clearAll: clearAll };
})();

function setTimeout(callback, delay) {
    return __nebulaTimers.schedule(callback, delay, false);
}

function setInterval(callback, delay) {
    return __nebulaTimers.schedule(callback, delay, true);
}

function clearTimeout(id) {
    __nebulaTimers.clear(id);
}

function clearInterval(id) {
    __nebulaTimers.clear(id);
}

function queueMicrotask(callback) {
    if (typeof callback === "function") setTimeout(callback, 0);
}

var __nebulaCallbacks = (function () {
    var sequence = 0;
    var entries = {};

    function register(callback) {
        if (typeof callback !== "function") return 0;
        var id = ++sequence;
        entries[id] = callback;
        return id;
    }

    function invoke(id, argumentsJson) {
        var key = Number(id);
        var callback = entries[key];
        delete entries[key];
        if (!callback) return;
        try {
            var args = argumentsJson ? JSON.parse(argumentsJson) : [];
            callback.apply(null, args);
        } catch (error) {
            __nLog("error", "callback failed: " + (error && error.message ? error.message : String(error)));
        }
    }

    return { register: register, invoke: invoke };
})();

/* CommonJS-style module wrapper, matching how BetterDiscord loads addons. */
var module = { exports: {} };
var exports = module.exports;

function require(path) {
    throw new Error(
        "require(\"" + path + "\") is not available: Nebula plugins are single files and " +
        "cannot load other JavaScript modules."
    );
}
