var exec = require('cordova/exec');
var SERVICE = 'ChainwayRfid';

function dispatchTriggerEvent(event) {
  if (event.type === 'window_key') {
    window.dispatchEvent(new CustomEvent('chainwayrfidwindowkey', { detail: event }));
    window.dispatchEvent(new CustomEvent('chainwayrfidkey', {
      detail: {
        type: 'window_key',
        action: 'ventana:' + event.action,
        keyCode: event.keyCode,
        scanCode: event.scanCode,
        isTrigger: event.isTrigger
      }
    }));
    return;
  }
  if (event.type !== 'trigger') return;
  window.dispatchEvent(new CustomEvent('chainwayrfidkey', { detail: event }));
  if (event.isTrigger) {
    window.dispatchEvent(new CustomEvent('chainwayrfidtrigger', { detail: event }));
  }
}

module.exports = {
  pluginVersion: '0.3.2-DIAG',

  /** Devuelve el estado interno real de Java: { version, uhfInitialized, uhfInventorying, barcodeInitialized, watchingTrigger } */
  getDiagInfo: function (ok, err) { exec(ok, err, SERVICE, 'getDiagInfo', []); },

  /** Inicializa el módulo UHF (tarda 1-3 s). */
  init: function (ok, err) { exec(ok, err, SERVICE, 'init', []); },

  /** Libera el módulo UHF. */
  free: function (ok, err) { exec(ok, err, SERVICE, 'free', []); },

  /** Inventario continuo UHF. */
  startInventory: function (opts, onEvent, err) {
    exec(onEvent, err, SERVICE, 'startInventory', [opts || {}]);
  },

  /** Detiene el inventario UHF. */
  stopInventory: function (ok, err) { exec(ok, err, SERVICE, 'stopInventory', []); },

  /** Lee un solo tag UHF. */
  readSingle: function (ok, err) { exec(ok, err, SERVICE, 'readSingle', []); },

  /** Potencia en dBm. */
  setPower: function (dbm, ok, err) { exec(ok, err, SERVICE, 'setPower', [dbm]); },
  getPower: function (ok, err) { exec(ok, err, SERVICE, 'getPower', []); },

  /** Inicializa el módulo 2D de Código de Barras / QR. */
  initBarcode: function (ok, err) { exec(ok, err, SERVICE, 'initBarcode', []); },

  /** Libera el módulo 2D de Código de Barras / QR. */
  freeBarcode: function (ok, err) { exec(ok, err, SERVICE, 'freeBarcode', []); },

  /** Dispara el láser 2D. */
  startBarcodeScan: function (onEvent, err) {
    exec(onEvent, err, SERVICE, 'startBarcodeScan', []);
  },

  /** Detiene el disparo del láser 2D. */
  stopBarcodeScan: function (ok, err) { exec(ok, err, SERVICE, 'stopBarcodeScan', []); },

  /** Escucha el gatillo físico. */
  watchTrigger: function (opts, onEvent, err) {
    exec(onEvent, err, SERVICE, 'watchTrigger', [opts || {}]);
  },
  unwatchTrigger: function (ok, err) { exec(ok, err, SERVICE, 'unwatchTrigger', []); },
  /** Publica los eventos del gatillo como eventos DOM para OutSystems. */
  startTriggerEvents: function (opts, ok, err) {
    exec(function (event) {
      if (event.type === 'watching') {
        if (ok) ok(event);
      } else {
        dispatchTriggerEvent(event);
      }
    }, err, SERVICE, 'watchTrigger', [opts || {}]);
  },

  /** Detiene la publicación de eventos del gatillo. */
  stopTriggerEvents: function (ok, err) { exec(ok, err, SERVICE, 'unwatchTrigger', []); }
};
