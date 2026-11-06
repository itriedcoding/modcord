/**
 * @name SessionTimer
 * @author Nebula
 * @description Tracks how long Nebula has been used and persists it to the plugin's own data file.
 * @version 1.0.0
 * @source https://github.com/nebula-android/nebula/tree/main/app/src/main/assets/addons/plugins
 */

module.exports = class SessionTimer {
    constructor(meta) {
        this.meta = meta;
        this.timer = null;
    }

    start() {
        var self = this;
        this.startedAt = Date.now();
        var stats = BdApi.Data.load("stats");
        this.sessions = (Number(stats.sessions) || 0) + 1;
        this.totalMs = Number(stats.totalMs) || 0;

        this.persist = function () {
            BdApi.Data.save("stats", {
                sessions: self.sessions,
                totalMs: self.totalMs + (Date.now() - self.startedAt),
                updated: new Date().toISOString()
            });
        };

        this.timer = setInterval(this.persist, 15000);
        this.persist();

        BdApi.UI.showToast("Session #" + this.sessions + " started");
        BdApi.Events.emit("sessiontimer:started", this.sessions);
        BdApi.Logger.info(this.meta.name, "session " + this.sessions + " started");
    }

    stop() {
        if (this.timer) clearInterval(this.timer);
        if (this.persist) this.persist();
        BdApi.Logger.info(this.meta.name, "session persisted");
    }
};
