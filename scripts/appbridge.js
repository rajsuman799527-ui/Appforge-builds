(function () {
  if (window.AppBridge) return;
  var n = window.AppBridgeNative;
  if (!n) return;

  function unwrap(name, raw) {
    var res;
    try { res = JSON.parse(raw); } catch (e) { throw new Error('AppBridge.' + name + ': invalid response from the app'); }
    if (!res || res.ok !== true) {
      var code = (res && res.error) || 'unknown';
      var err = new Error('AppBridge.' + name + ' failed: ' + code);
      err.code = code;
      throw err;
    }
    return res.data;
  }
  function text(v) { return typeof v === 'string' ? v : JSON.stringify(v); }
  function ms(v) { var x = Number(v); return isFinite(x) && x > 0 ? Math.floor(x) : 0; }

  window.AppBridge = {
    isAvailable: true,
    version: 1,
    getCapabilities: function () { return unwrap('getCapabilities', n.getCapabilities()); },

    hasUsageAccess: function () { return unwrap('hasUsageAccess', n.hasUsageAccess()) === true; },
    openUsageSettings: function () { return unwrap('openUsageSettings', n.openUsageSettings()) === true; },
    getUsageStats: function (startMs, endMs) { return unwrap('getUsageStats', n.getUsageStats(ms(startMs), ms(endMs))); },

    openSettings: function (screen) { return unwrap('openSettings', n.openSettings(String(screen || ''))) === true; },
    openIntent: function (spec) { return unwrap('openIntent', n.openIntent(JSON.stringify(spec || {}))) === true; },

    showNotification: function (title, message) { return unwrap('showNotification', n.showNotification(String(title || ''), String(message || ''))) === true; },

    saveData: function (key, value) { return unwrap('saveData', n.saveData(String(key), text(value))) === true; },
    getData: function (key) { return unwrap('getData', n.getData(String(key))); },
    getJSON: function (key) {
      var v = unwrap('getData', n.getData(String(key)));
      if (v === null || v === undefined) return null;
      try { return JSON.parse(v); } catch (e) { return null; }
    },
    removeData: function (key) { return unwrap('removeData', n.removeData(String(key))) === true; },
    listDataKeys: function () { return unwrap('listDataKeys', n.listDataKeys()); },
    clearData: function () { return unwrap('clearData', n.clearData()) === true; },

    startBackgroundCollection: function (minutes) { return unwrap('startBackgroundCollection', n.startBackgroundCollection(ms(minutes))) === true; },
    stopBackgroundCollection: function () { return unwrap('stopBackgroundCollection', n.stopBackgroundCollection()) === true; },
    getBackgroundStatus: function () { return unwrap('getBackgroundStatus', n.getBackgroundStatus()); },
    getLatestUsageSnapshot: function () { return unwrap('getLatestUsageSnapshot', n.getLatestUsageSnapshot()); },

    callPlugin: function (name, method, args) {
      return unwrap('callPlugin', n.callPlugin(String(name), String(method || ''), JSON.stringify(args === undefined ? {} : args)));
    }
  };
  try { window.dispatchEvent(new Event('appbridgeready')); } catch (e) {}
})();
