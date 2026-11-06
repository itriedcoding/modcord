/*
 * Nebula plugin teardown. Calls stop(), removes every patch and listener the
 * plugin registered, and clears its timers. Errors from stop() are re-thrown so
 * the host can log them, but the cleanup still runs.
 */
(function () {
    var failure = null;

    try {
        var instance = globalThis.__nebulaInstance;
        if (instance && typeof instance.stop === "function") {
            instance.stop();
        }
    } catch (error) {
        failure = error;
    }

    try {
        if (typeof globalThis.__nebulaTeardown === "function") {
            globalThis.__nebulaTeardown();
        }
    } catch (error) {
        if (!failure) failure = error;
    }

    try {
        __nebulaTimers.clearAll();
    } catch (error) {
        // Timers may already be gone if the context is being discarded.
    }

    globalThis.__nebulaInstance = null;

    if (failure) throw failure;
})();
