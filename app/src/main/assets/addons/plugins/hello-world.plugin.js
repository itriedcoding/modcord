/**
 * @name HelloWorld
 * @author Nebula
 * @description The smallest possible BetterDiscord plugin, using only Logger, Events and UI.
 * @version 1.0.0
 * @source https://github.com/nebula-android/nebula/tree/main/app/src/main/assets/addons/plugins
 */

module.exports = class HelloWorld {
    constructor(meta) {
        this.meta = meta;
    }

    start() {
        BdApi.Logger.info(this.meta.name, "Hello from " + this.meta.name + " v" + this.meta.version);
        BdApi.Events.emit("helloworld:started", this.meta.name);
        BdApi.UI.showToast("Hello from " + this.meta.name);
    }

    stop() {
        BdApi.Logger.info(this.meta.name, "Goodbye");
    }
};
