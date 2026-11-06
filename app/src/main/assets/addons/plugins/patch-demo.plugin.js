/**
 * @name PatchDemo
 * @author Nebula
 * @description Demonstrates that BdApi.Patcher really wraps and restores functions, including on shared objects.
 * @version 1.0.0
 * @source https://github.com/nebula-android/nebula/tree/main/app/src/main/assets/addons/plugins
 */

module.exports = class PatchDemo {
    constructor(meta) {
        this.meta = meta;
    }

    start() {
        this.greeter = {
            greet: function (name) {
                return "hello " + name;
            }
        };

        BdApi.Patcher.after(this.meta.name, this.greeter, "greet", function (self, args, result) {
            return result.toUpperCase();
        });

        BdApi.Patcher.before(this.meta.name, this.greeter, "greet", function (self, args) {
            args[0] = String(args[0]) + " (patched)";
        });

        this.patchedGreeting = this.greeter.greet("nebula");

        BdApi.Patcher.instead(this.meta.name, Math, "max", function () {
            return 4242;
        });
        this.patchedMax = Math.max(1, 2);

        BdApi.Logger.info(
            this.meta.name,
            "greet => " + this.patchedGreeting + " | Math.max => " + this.patchedMax
        );
        BdApi.UI.showToast(this.patchedGreeting);
    }

    stop() {
        BdApi.Patcher.unpatchAll(this.meta.name);
        BdApi.Logger.info(
            this.meta.name,
            "patches restored | Math.max(1, 2) => " + Math.max(1, 2)
        );
    }
};
