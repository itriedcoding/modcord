/*
 * Nebula loader - injected into the React Native bundle of the patched Discord build.
 *
 * This runs inside Discord's own JavaScript context, so it can reach Discord's module
 * registry and patch the modules that render the app. Everything it cannot do is
 * reported as an error with a reason; nothing is stubbed to look like it worked.
 */
var __nebula_layer = (function () {
  var payload = (typeof globalThis !== 'undefined' && globalThis.__nebula_payload) || global.__nebula_payload || null;
  var errors = [];

  function fail(message) {
    errors.push(message);
    try {
      console.error('[Nebula] ' + message);
    } catch (ignored) {}
  }

  function makeLogger(scope) {
    function emit(kind, args) {
      var parts = ['[Nebula/' + scope + ']'];
      for (var i = 0; i < args.length; i++) parts.push(args[i]);
      try {
        (console[kind] || console.log).apply(console, parts);
      } catch (ignored) {}
    }
    return {
      info: function () { emit('log', arguments); },
      warn: function () { emit('warn', arguments); },
      error: function () { emit('error', arguments); },
      debug: function () { emit('debug', arguments); }
    };
  }

  var logger = makeLogger('loader');

  if (!payload || !payload.addons) {
    fail('No __nebula_payload present; the bundle was not patched by Nebula.');
    return { errors: errors, patched: false };
  }

  /* ---------------------------------------------------------------- */
  /* Discord module registry (metro)                                    */
  /* ---------------------------------------------------------------- */

  var request = typeof global.__r === 'function' ? global.__r : null;
  var modules = [];

  function collect() {
    if (!request) {
      fail('global.__r is missing, so Discord\'s module registry cannot be read. Addon APIs that need Discord internals will throw.');
      return;
    }
    var registry = null;
    try {
      if (typeof request.getModules === 'function') registry = request.getModules();
      else if (request.modules) registry = request.modules;
    } catch (e) {
      fail('Reading the module registry failed: ' + e);
      return;
    }
    if (!registry) {
      fail('The module registry is empty; Discord may have changed its bundle layout.');
      return;
    }
    registry.forEach(function (record, id) {
      var exports = null;
      try {
        if (record) {
          if (record.publicModule) exports = record.publicModule.exports;
          else if ('exports' in record) exports = record.exports;
          else exports = record;
        }
      } catch (e) {
        exports = null;
      }
      modules.push({
        id: id,
        exports: exports,
        name: record && (record.verboseName || record.name) ? (record.verboseName || record.name) : null
      });
    });
    logger.info('Registered ' + modules.length + ' Discord modules');
  }

  function usable(entry) {
    return entry && entry.exports !== undefined && entry.exports !== null;
  }

  function firstWhere(predicate) {
    for (var i = 0; i < modules.length; i++) {
      if (!usable(modules[i])) continue;
      var value = null;
      try {
        if (predicate(modules[i].exports, modules[i])) value = modules[i].exports;
      } catch (ignored) {}
      if (value) return value;
    }
    return null;
  }

  function findByProps() {
    var props = Array.prototype.slice.call(arguments);
    return firstWhere(function (exports) {
      for (var i = 0; i < props.length; i++) {
        if (exports == null || !(props[i] in Object(exports))) return false;
      }
      return true;
    });
  }

  function findByDisplayName(name) {
    return firstWhere(function (exports) {
      return exports && (exports.displayName === name || exports.name === name);
    });
  }

  function findAll(filter) {
    var found = [];
    for (var i = 0; i < modules.length; i++) {
      if (!usable(modules[i])) continue;
      try {
        if (filter(modules[i].exports, modules[i])) found.push(modules[i].exports);
      } catch (ignored) {}
    }
    return found;
  }

  /* ---------------------------------------------------------------- */
  /* Patcher - real before/after/instead patching                        */
  /* ---------------------------------------------------------------- */

  function Patcher() {
    var patches = [];

    function makePatch(target, key, replacement) {
      var had = Object.prototype.hasOwnProperty.call(target, key);
      var original = target[key];
      target[key] = replacement;
      var restored = false;
      return function restore() {
        if (restored) return;
        restored = true;
        if (had) target[key] = original;
        else delete target[key];
      };
    }

    function patchFunction(target, key, factory) {
      var original = target[key];
      if (typeof original !== 'function') {
        throw new Error('Patcher: ' + key + ' is not a function');
      }
      var replacement = factory(original);
      if (typeof replacement !== 'function') {
        throw new Error('Patcher: patcher for ' + key + ' did not return a function');
      }
      patches.push(makePatch(target, key, replacement));
    }

    return {
      before: function (target, key, callback) {
        patchFunction(target, key, function (original) {
          return function () {
            callback(this, arguments);
            return original.apply(this, arguments);
          };
        });
      },
      after: function (target, key, callback) {
        patchFunction(target, key, function (original) {
          return function () {
            var result = original.apply(this, arguments);
            callback(this, arguments, result);
            return result;
          };
        });
      },
      instead: function (target, key, callback) {
        patchFunction(target, key, function (original) {
          return function () {
            return callback(this, arguments, original);
          };
        });
      },
      patch: function (target, key, value) {
        patches.push(makePatch(target, key, value));
      },
      unpatchAll: function () {
        while (patches.length) patches.pop()();
      }
    };
  }

  /* ---------------------------------------------------------------- */
  /* Addon API                                                          */
  /* ---------------------------------------------------------------- */

  function unsupported(what, reason) {
    return function () {
      throw new Error('[Nebula] ' + what + ' is not available inside the patched Discord app: ' + reason);
    };
  }

  function dataFor(addon) {
    var key = '__nebula_data_' + addon.id;
    return {
      load: function (name) {
        if (name === undefined) return global[key] || {};
        var all = global[key] || {};
        return all[name];
      },
      save: function (name, value) {
        var all = global[key] || (global[key] = {});
        all[name] = value;
      },
      delete: function (name) {
        var all = global[key] || {};
        delete all[name];
      }
    };
  }

  function toastApi() {
    var toasts = findByProps('showToast') || findByProps('open');
    if (!toasts || typeof toasts.showToast !== 'function') {
      return unsupported('UI.showToast', 'Discord\'s toast module was not found in this build');
    }
    return function (message) {
      try {
        toasts.showToast({ content: String(message) });
      } catch (e) {
        fail('showToast failed: ' + e);
      }
    };
  }

  function apiFor(addon) {
    var log = makeLogger(addon.id);
    return {
      version: payload.version,
      Logger: log,
      Patcher: Patcher(),
      Data: dataFor(addon),
      Webpack: {
        get: function (filter) { return find(filter); },
        getAll: function (filter) { return findAll(filter); },
        findByProps: findByProps,
        findByDisplayName: findByDisplayName,
        getStore: function (name) {
          return findByProps('getName') && firstWhere(function (exports) {
            return exports && exports.getName && exports.getName() === name;
          });
        }
      },
      UI: {
        showToast: toastApi(),
        showConfirmationModal: unsupported('UI.showConfirmationModal', 'native dialogs are not reachable from the bundle'),
        showNotice: unsupported('UI.showNotice', 'native notices are not reachable from the bundle')
      },
      DOM: {
        addStyle: unsupported('DOM.addStyle', 'Discord Android renders native views, not DOM nodes'),
        createElement: unsupported('DOM.createElement', 'Discord Android renders native views, not DOM nodes')
      },
      React: unsupported('BdApi.React', 'React is bundled privately by this build and is not exported'),
      Components: unsupported('BdApi.Components', 'Discord\'s component library is not exported by this build'),
      ContextMenu: unsupported('BdApi.ContextMenu', 'the context menu module is not exported by this build'),
      Hooks: unsupported('BdApi.Hooks', 'React hooks are not reachable from the bundle')
    };
  }

  function find(filter) {
    return firstWhere(function (exports) {
      return filter(exports);
    });
  }

  var started = [];

  function runAddon(addon) {
    if (addon.kind !== 'plugin') {
      applyTheme(addon);
      return;
    }
    var module = { exports: {} };
    try {
      var factory = new Function(
        'module', 'exports', 'BdApi',
        addon.source + '\n//# sourceURL=nebula-' + addon.id + '.js'
      );
      factory(module, module.exports, apiFor(addon));
    } catch (e) {
      fail('Plugin ' + addon.name + ' failed to evaluate: ' + e);
      return;
    }
    var plugin = module.exports;
    if (!plugin || typeof plugin.start !== 'function') {
      fail('Plugin ' + addon.name + ' does not export start(); nothing to run');
      return;
    }
    try {
      plugin.start();
      started.push({ addon: addon, plugin: plugin });
      logger.info('Started ' + addon.name + ' ' + addon.version);
    } catch (e) {
      fail('Plugin ' + addon.name + ' threw in start(): ' + e);
    }
  }

  function applyTheme(theme) {
    if (typeof document !== 'undefined' && document && document.head) {
      try {
        var style = document.createElement('style');
        style.textContent = theme.source;
        document.head.appendChild(style);
        logger.info('Applied theme ' + theme.name);
        return;
      } catch (e) {
        fail('Theme ' + theme.name + ' could not be inserted: ' + e);
        return;
      }
    }
    fail(
      'Theme ' + theme.name + ' was not applied: this Discord build renders native React Native ' +
      'views with no DOM, so CSS cannot target them. Use the accent override in Nebula instead.'
    );
  }

  collect();
  for (var i = 0; i < payload.addons.length; i++) {
    runAddon(payload.addons[i]);
  }

  var layer = {
    patched: true,
    version: payload.version,
    addons: payload.addons.length,
    started: started.length,
    errors: errors,
    modules: modules.length,
    api: {
      find: find,
      findAll: findAll,
      findByProps: findByProps,
      findByDisplayName: findByDisplayName
    },
    stopAll: function () {
      for (var i = 0; i < started.length; i++) {
        try {
          if (typeof started[i].plugin.stop === 'function') started[i].plugin.stop();
        } catch (e) {
          fail('Stopping ' + started[i].addon.name + ' failed: ' + e);
        }
      }
      started = [];
    }
  };

  global.__nebula = layer;
  logger.info('Loaded ' + layer.addons + ' addon(s); ' + layer.started + ' started, ' + errors.length + ' problem(s)');
  return layer;
})();
