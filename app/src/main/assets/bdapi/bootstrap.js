/*
 * Nebula plugin bootstrap.
 *
 * Evaluated after the plugin source. Turns `module.exports` into a live instance
 * (the BetterDiscord contract) and calls `start()`. Also accepts a plugin that
 * declares global start()/stop() functions.
 */
(function () {
    var exported = typeof module !== "undefined" && module ? module.exports : undefined;
    var instance = null;

    if (typeof exported === "function") {
        instance = new exported(__nebulaMeta);
    } else if (exported && typeof exported === "object") {
        instance = exported;
    }

    if (instance && typeof instance.start === "function") {
        globalThis.__nebulaInstance = instance;
        instance.start();
        return;
    }

    if (typeof globalThis.start === "function") {
        globalThis.__nebulaInstance = {
            start: globalThis.start,
            stop: typeof globalThis.stop === "function" ? globalThis.stop : null
        };
        globalThis.start();
        return;
    }

    throw new Error(
        "Plugin \"" + __nebulaPluginName + "\" does not define start(). BetterDiscord plugins must " +
        "export a class (or object) with start() and stop() methods."
    );
})();
