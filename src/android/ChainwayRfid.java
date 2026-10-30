package com.poc.chainwayrfid;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;

import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.BarcodeEntity;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

public class ChainwayRfid extends CordovaPlugin {

    public static final String PLUGIN_VERSION = "0.3.2-DIAG";

    // Keycodes conocidos de Chainway C72 para el gatillo y botones laterales
    private static final Integer[] DEFAULT_TRIGGER_KEYS = {139, 280, 291, 293, 294, 311, 312, 313, 315};

    // --- Estado UHF ---
    private RFIDWithUHFUART reader;
    private volatile boolean initialized = false;
    private volatile CallbackContext inventoryCallback;
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean uniqueOnly = true;

    // --- Estado Barcode 2D / QR ---
    private BarcodeDecoder barcodeDecoder;
    private volatile boolean barcodeInitialized = false;
    private volatile CallbackContext barcodeCallback;

    // --- Estado Gatillo Fisico ---
    private volatile CallbackContext triggerCallback;
    private final Set<Integer> triggerKeys = new HashSet<>(Arrays.asList(DEFAULT_TRIGGER_KEYS));
    private volatile boolean logAllKeys = true;
    private Window.Callback previousWindowCallback;
    private Window.Callback diagnosticWindowCallback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) throws JSONException {
        switch (action) {
            case "getDiagInfo":      getDiagInfo(cb); return true;
            // Acciones UHF
            case "init":             init(cb); return true;
            case "free":             free(cb); return true;
            case "startInventory":   startInventory(args.optJSONObject(0), cb); return true;
            case "stopInventory":    stopInventory(cb); return true;
            case "readSingle":       readSingle(cb); return true;
            case "setPower":         setPower(args.getInt(0), cb); return true;
            case "getPower":         getPower(cb); return true;
            // Acciones Barcode 2D / QR
            case "initBarcode":      initBarcode(cb); return true;
            case "freeBarcode":      freeBarcode(cb); return true;
            case "startBarcodeScan": startBarcodeScan(cb); return true;
            case "stopBarcodeScan":  stopBarcodeScan(cb); return true;
            // Acciones Gatillo Fisico
            case "watchTrigger":     watchTrigger(args.optJSONObject(0), cb); return true;
            case "unwatchTrigger":   unwatchTrigger(cb); return true;
            default:                 return false;
        }
    }

    /**
     * Envia Broadcast Intents al sistema operativo del Chainway para apagar
     * el escaneo automatico del KeyboardEmulator (evita que salga el laser rojo en UHF).
     */
    private void disableSystemKeyboardEmulator() {
        try {
            Context ctx = cordova.getActivity().getApplicationContext();

            Intent i1 = new Intent("com.rscja.scanner.action.SWITCH_SCANNER");
            i1.putExtra("enable", false);
            ctx.sendBroadcast(i1);

            Intent i2 = new Intent("com.android.scanner.service_settings");
            i2.putExtra("action_barcode_broadcast", false);
            ctx.sendBroadcast(i2);
        } catch (Exception ignored) { }
    }

    private void getDiagInfo(final CallbackContext cb) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", PLUGIN_VERSION);
            o.put("uhfInitialized", initialized);
            o.put("uhfInventorying", reader != null && reader.isInventorying());
            o.put("barcodeInitialized", barcodeInitialized);
            o.put("watchingTrigger", triggerCallback != null);
            cb.success(o);
        } catch (JSONException e) {
            cb.error(e.getMessage());
        }
    }

    // ---------- Ciclo de vida del modulo UHF ----------

    private void init(final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                disableSystemKeyboardEmulator();
                // Si el lector 2D estaba abierto en nuestra app, lo cerramos para evitar conflicto de bus
                releaseBarcode();

                if (initialized && reader != null) {
                    cb.success("UHF_ALREADY_INIT");
                    return;
                }
                if (reader == null) reader = RFIDWithUHFUART.getInstance();
                boolean ok = reader.init(cordova.getActivity().getApplicationContext());
                initialized = ok;
                if (ok) cb.success("UHF_INIT_OK");
                else cb.error("[ERR-JAVA-UHF-INIT] reader.init() devolvio false. El puerto UART esta ocupado por KeyboardEmulator o AppCenter.");
            } catch (Exception e) {
                cb.error("[ERR-JAVA-UHF-EX] Excepcion en init: " + e.getMessage());
            }
        });
    }

    private void free(final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            releaseReader();
            cb.success("UHF_FREE_OK");
        });
    }

    private synchronized void releaseReader() {
        try {
            if (reader != null) {
                if (reader.isInventorying()) reader.stopInventory();
                reader.free();
            }
        } catch (Exception ignored) { }
        initialized = false;
        closeInventoryStream();
    }

    private boolean ensureReady(CallbackContext cb) {
        if (reader == null || !initialized) {
            cb.error("[ERR-JAVA-NOT-READY] UHF no inicializado (initialized=" + initialized + ").");
            return false;
        }
        return true;
    }

    // ---------- Inventario continuo UHF ----------

    private void startInventory(JSONObject opts, final CallbackContext cb) {
        if (!ensureReady(cb)) return;
        if (reader.isInventorying()) {
            cb.error("[ERR-JAVA-ALREADY-INV] El inventario UHF ya estaba corriendo.");
            return;
        }

        uniqueOnly = opts == null || opts.optBoolean("unique", true);
        seen.clear();
        inventoryCallback = cb;

        reader.setInventoryCallback(new IUHFInventoryCallback() {
            @Override
            public void callback(UHFTAGInfo tag) { emitTag(tag); }
        });

        if (!reader.startInventoryTag()) {
            inventoryCallback = null;
            cb.error("[ERR-JAVA-START-FAIL] reader.startInventoryTag() devolvio false.");
            return;
        }
        sendEvent(cb, event("started"), true);
    }

    private void emitTag(UHFTAGInfo tag) {
        CallbackContext cb = inventoryCallback;
        if (cb == null || tag == null || tag.getEPC() == null) return;
        String epc = tag.getEPC();
        if (uniqueOnly && !seen.add(epc)) return;
        if (!uniqueOnly) seen.add(epc);
        try {
            JSONObject o = tagToJson(tag);
            o.put("type", "tag");
            sendEvent(cb, o, true);
        } catch (JSONException ignored) { }
    }

    private void stopInventory(final CallbackContext cb) {
        boolean wasRunning = false;
        if (reader != null && reader.isInventorying()) {
            wasRunning = true;
            reader.stopInventory();
        }
        int total = seen.size();
        closeInventoryStream();
        try {
            JSONObject o = new JSONObject();
            o.put("total", total);
            o.put("wasRunning", wasRunning);
            cb.success(o);
        } catch (JSONException e) { cb.success(); }
    }

    private void closeInventoryStream() {
        CallbackContext inv = inventoryCallback;
        inventoryCallback = null;
        if (inv == null) return;
        try {
            JSONObject o = event("stopped");
            o.put("total", seen.size());
            sendEvent(inv, o, false);
        } catch (JSONException ignored) { }
    }

    // ---------- Lectura simple y configuracion UHF ----------

    private void readSingle(final CallbackContext cb) {
        if (!ensureReady(cb)) return;
        cordova.getThreadPool().execute(() -> {
            try {
                UHFTAGInfo tag = reader.inventorySingleTag();
                if (tag == null || tag.getEPC() == null) { cb.error("No se detecto ningun tag"); return; }
                cb.success(tagToJson(tag));
            } catch (Exception e) {
                cb.error("Error en readSingle: " + e.getMessage());
            }
        });
    }

    private void setPower(final int dbm, final CallbackContext cb) {
        if (!ensureReady(cb)) return;
        if (reader.isInventorying()) { cb.error("Detene el inventario antes de cambiar la potencia"); return; }
        cordova.getThreadPool().execute(() -> {
            if (reader.setPower(dbm)) cb.success(); else cb.error("setPower(" + dbm + ") fallo");
        });
    }

    private void getPower(final CallbackContext cb) {
        if (!ensureReady(cb)) return;
        if (reader.isInventorying()) { cb.error("Detene el inventario antes de leer la potencia"); return; }
        cordova.getThreadPool().execute(() -> cb.success(reader.getPower()));
    }

    // ---------- Lector 2D Barcode / QR ----------

    private void initBarcode(final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                disableSystemKeyboardEmulator();
                // Si el UHF estaba abierto, lo liberamos antes de abrir el lector 2D
                releaseReader();

                if (barcodeInitialized && barcodeDecoder != null) {
                    cb.success("BARCODE_ALREADY_INIT");
                    return;
                }
                if (barcodeDecoder == null) {
                    barcodeDecoder = BarcodeFactory.getInstance().getBarcodeDecoder();
                }
                boolean ok = barcodeDecoder.open(cordova.getActivity());
                barcodeInitialized = ok;
                if (ok) {
                    barcodeDecoder.setDecodeCallback(new BarcodeDecoder.DecodeCallback() {
                        @Override
                        public void onDecodeComplete(BarcodeEntity barcodeEntity) {
                            CallbackContext bcb = barcodeCallback;
                            if (bcb == null || barcodeEntity == null) return;
                            try {
                                if (barcodeEntity.getResultCode() == BarcodeDecoder.DECODE_SUCCESS) {
                                    JSONObject o = event("barcode");
                                    o.put("data", barcodeEntity.getBarcodeData() != null ? barcodeEntity.getBarcodeData() : "");
                                    o.put("resultCode", barcodeEntity.getResultCode());
                                    sendEvent(bcb, o, true);
                                } else {
                                    JSONObject o = event("barcode_status");
                                    o.put("resultCode", barcodeEntity.getResultCode());
                                    sendEvent(bcb, o, true);
                                }
                            } catch (JSONException ignored) { }
                        }
                    });
                    cb.success("BARCODE_INIT_OK");
                } else {
                    cb.error("[ERR-JAVA-BC-OPEN] barcodeDecoder.open() devolvio false. KeyboardEmulator tiene tomado el escaner 2D.");
                }
            } catch (Exception e) {
                cb.error("[ERR-JAVA-BC-EX] Excepcion en initBarcode: " + e.getMessage());
            }
        });
    }

    private void startBarcodeScan(final CallbackContext cb) {
        if (barcodeDecoder == null || !barcodeInitialized) {
            cb.error("[ERR-JAVA-BC-NOT-INIT] Lector 2D no inicializado (barcodeInitialized=" + barcodeInitialized + ").");
            return;
        }
        closeBarcodeStream();
        barcodeCallback = cb;
        cordova.getThreadPool().execute(() -> {
            try {
                barcodeDecoder.startScan();
                sendEvent(cb, event("scan_started"), true);
            } catch (Exception e) {
                if (barcodeCallback == cb) barcodeCallback = null;
                cb.error("[ERR-JAVA-BC-START] " + e.getMessage());
            }
        });
    }

    private void stopBarcodeScan(final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                if (barcodeDecoder != null && barcodeInitialized) {
                    barcodeDecoder.stopScan();
                }
                closeBarcodeStream();
                cb.success("BARCODE_STOP_OK");
            } catch (Exception e) {
                cb.error("[ERR-JAVA-BC-STOP] " + e.getMessage());
            }
        });
    }

    private void freeBarcode(final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            releaseBarcode();
            cb.success("BARCODE_FREE_OK");
        });
    }

    private synchronized void releaseBarcode() {
        try {
            if (barcodeDecoder != null) {
                if (barcodeInitialized) {
                    barcodeDecoder.stopScan();
                }
                barcodeDecoder.close();
            }
        } catch (Exception ignored) { }
        barcodeInitialized = false;
        closeBarcodeStream();
    }

    private synchronized void closeBarcodeStream() {
        CallbackContext callback = barcodeCallback;
        barcodeCallback = null;
        closeCallback(callback);
    }

    // ---------- Gatillo fisico ----------

    private void watchTrigger(JSONObject opts, final CallbackContext cb) {
        if (opts != null) {
            logAllKeys = opts.optBoolean("logAll", true);
            JSONArray codes = opts.optJSONArray("keyCodes");
            if (codes != null) {
                triggerKeys.clear();
                for (int i = 0; i < codes.length(); i++) triggerKeys.add(codes.optInt(i));
            }
        }
        closeTriggerStream();
        triggerCallback = cb;
        cordova.getActivity().runOnUiThread(() -> {
            if (triggerCallback != cb) return;
            try {
                installWindowKeyDiagnostics();
            } catch (Exception ignored) {
                diagnosticWindowCallback = null;
                previousWindowCallback = null;
            }
            View view = webView.getView();
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.requestFocus();
            view.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent ev) {
                    boolean isTrigger = triggerKeys.contains(keyCode);
                    if (!isTrigger && !logAllKeys) return false;
                    if (ev.getRepeatCount() > 0) return isTrigger;
                    CallbackContext tcb = triggerCallback;
                    if (tcb != null) {
                        try {
                            JSONObject o = event("trigger");
                            o.put("action", ev.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up");
                            o.put("keyCode", keyCode);
                            o.put("isTrigger", isTrigger);
                            sendEvent(tcb, o, true);
                        } catch (JSONException ignored) { }
                    }
                    return isTrigger;
                }
            });
            if (triggerCallback == cb) {
                JSONObject watching = event("watching");
                try {
                    watching.put("hasFocus", view.hasFocus());
                    watching.put("logAll", logAllKeys);
                    watching.put("keyCodes", new JSONArray(triggerKeys));
                    watching.put("windowDiagnostics", diagnosticWindowCallback != null);
                } catch (JSONException ignored) { }
                sendEvent(cb, watching, true);
            }
        });
    }

    private void unwatchTrigger(final CallbackContext cb) {
        closeTriggerStream();
        cordova.getActivity().runOnUiThread(() -> {
            webView.getView().setOnKeyListener(null);
            removeWindowKeyDiagnostics();
            cb.success();
        });
    }

    // Observa eventos enviados a la ventana sin consumirlos ni alterar su destino.
    // Solo reporta códigos y acciones; no registra caracteres escritos.
    private void installWindowKeyDiagnostics() {
        Window window = cordova.getActivity().getWindow();
        if (diagnosticWindowCallback != null && window.getCallback() == diagnosticWindowCallback) return;
        previousWindowCallback = window.getCallback();
        final Window.Callback delegate = previousWindowCallback;
        if (delegate == null) return;
        diagnosticWindowCallback = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> {
                    if ("dispatchKeyEvent".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof KeyEvent && logAllKeys) {
                        KeyEvent key = (KeyEvent) args[0];
                        if (key.getRepeatCount() == 0 && (key.getAction() == KeyEvent.ACTION_DOWN
                                || key.getAction() == KeyEvent.ACTION_UP)) {
                            CallbackContext callback = triggerCallback;
                            if (callback != null) {
                                try {
                                    JSONObject o = event("window_key");
                                    o.put("action", key.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up");
                                    o.put("keyCode", key.getKeyCode());
                                    o.put("scanCode", key.getScanCode());
                                    o.put("isTrigger", triggerKeys.contains(key.getKeyCode()));
                                    sendEvent(callback, o, true);
                                } catch (JSONException ignored) { }
                            }
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        window.setCallback(diagnosticWindowCallback);
    }

    private void removeWindowKeyDiagnostics() {
        Window window = cordova.getActivity().getWindow();
        if (diagnosticWindowCallback != null && window.getCallback() == diagnosticWindowCallback) {
            window.setCallback(previousWindowCallback);
        }
        diagnosticWindowCallback = null;
        previousWindowCallback = null;
    }

    // ---------- Lifecycle de la Activity ----------

    @Override
    public void onPause(boolean multitasking) {
        if (reader != null && reader.isInventorying()) {
            reader.stopInventory();
            closeInventoryStream();
        }
        if (barcodeDecoder != null && barcodeInitialized) {
            try { barcodeDecoder.stopScan(); } catch (Exception ignored) { }
            closeBarcodeStream();
        }
    }

    @Override
    public void onDestroy() {
        releaseReader();
        releaseBarcode();
        closeTriggerStream();
        cordova.getActivity().runOnUiThread(this::removeWindowKeyDiagnostics);
    }

    // ---------- Helpers ----------

    private static JSONObject tagToJson(UHFTAGInfo tag) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("epc", tag.getEPC());
        o.put("tid", tag.getTid() == null ? "" : tag.getTid());
        o.put("rssi", tag.getRssi() == null ? "" : tag.getRssi());
        return o;
    }

    private static JSONObject event(String type) {
        JSONObject o = new JSONObject();
        try { o.put("type", type); } catch (JSONException ignored) { }
        return o;
    }

    private static void sendEvent(CallbackContext cb, JSONObject payload, boolean keep) {
        PluginResult r = new PluginResult(PluginResult.Status.OK, payload);
        r.setKeepCallback(keep);
        cb.sendPluginResult(r);
    }

    private synchronized void closeTriggerStream() {
        CallbackContext callback = triggerCallback;
        triggerCallback = null;
        closeCallback(callback);
    }

    private static void closeCallback(CallbackContext callback) {
        if (callback == null) return;
        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(false);
        callback.sendPluginResult(result);
    }
}
