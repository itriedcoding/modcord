/**
 * @name AccentCycle
 * @author Nebula
 * @description Rotates the app accent colour on a timer. Exercises real timers, Settings, the events bus and the appearance bridge.
 * @version 1.0.0
 * @source https://github.com/nebula-android/nebula/tree/main/app/src/main/assets/addons/plugins
 */

module.exports = class AccentCycle {
    constructor(meta) {
        this.meta = meta;
        this.timer = null;
        this.index = 0;
    }

    start() {
        var self = this;
        BdApi.Settings.define([
            {
                key: "enabled",
                type: "boolean",
                label: "Enabled",
                description: "Rotate the accent colour automatically.",
                default: true
            },
            {
                key: "interval",
                type: "number",
                label: "Interval (ms)",
                description: "Delay between colours. Minimum 1000 ms.",
                default: 8000
            },
            {
                key: "palette",
                type: "text",
                label: "Colours",
                description: "Comma separated hex colours.",
                default: "#7C5CFC,#22D3EE,#F472B6,#34D399"
            }
        ]);

        this.unsubscribe = BdApi.Settings.onChange(function () {
            self.restart();
        });

        this.restart();
        BdApi.Logger.info(this.meta.name, "accent cycling started");
    }

    colours() {
        var raw = String(BdApi.Settings.get("palette", "#7C5CFC,#22D3EE"));
        return raw.split(",")
            .map(function (value) { return value.trim(); })
            .filter(function (value) { return /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(value); });
    }

    restart() {
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
        if (!BdApi.Settings.get("enabled", true)) {
            BdApi.Themes.setAccent(null);
            return;
        }
        var self = this;
        var interval = Math.max(1000, Number(BdApi.Settings.get("interval", 8000)) || 8000);
        this.timer = setInterval(function () { self.step(); }, interval);
        this.step();
    }

    step() {
        var list = this.colours();
        if (!list.length) {
            BdApi.Logger.warn(this.meta.name, "no valid colours configured");
            return;
        }
        var colour = list[this.index % list.length];
        this.index += 1;
        BdApi.Themes.setAccent(colour);
        BdApi.Events.emit("accentcycle:changed", colour);
    }

    stop() {
        if (this.timer) clearInterval(this.timer);
        if (this.unsubscribe) this.unsubscribe();
        BdApi.Themes.setAccent(null);
        BdApi.Logger.info(this.meta.name, "accent cycling stopped");
    }
};
