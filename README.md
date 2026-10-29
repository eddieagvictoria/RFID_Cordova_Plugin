# cordova-plugin-chainway-rfid

Plugin Cordova para usar el lector UHF RFID del Chainway C72 desde una app móvil de OutSystems.
Envuelve el SDK `DeviceAPI_ver20251103_release.aar` (clase `RFIDWithUHFUART`).

## API JavaScript (`window.ChainwayRfid`)

| Método | Qué hace |
|---|---|
| `init(ok, err)` | Inicializa el módulo UHF (1-3 s). Una vez al arrancar. |
| `free(ok, err)` | Libera el módulo. |
| `startInventory({unique:true}, onEvent, err)` | Lectura continua. `onEvent` recibe `{type:'started'}`, `{type:'tag', epc, tid, rssi}` y `{type:'stopped', total}`. |
| `stopInventory(ok, err)` | Detiene la lectura. `ok` recibe `{total}`. |
| `readSingle(ok, err)` | Lee un solo tag: `{epc, tid, rssi}`. |
| `setPower(dbm, ok, err)` / `getPower(ok, err)` | Potencia de lectura. Solo con el inventario detenido. |
| `watchTrigger({keyCodes?, logAll?}, onEvent, err)` | Gatillo físico: `{type:'trigger', action:'down'/'up', keyCode}`. |
| `unwatchTrigger(ok, err)` | Deja de escuchar el gatillo. |

## Integración en OutSystems 11

1. Subí esta carpeta a un repo Git (o comprimila en `chainway-rfid.zip`).
2. Creá un módulo **Mobile Plugin** (ej. `ChainwayRfidPlugin`).
3. En *Extensibility Configurations* del módulo:
   - Desde Git: `{ "plugin": { "url": "https://github.com/<org>/cordova-plugin-chainway-rfid.git#v0.1.0" } }`
   - Desde zip: agregá el zip como Resource (*Deploy Action: Deploy to Target Directory*) y usá
     `{ "plugin": { "resource": "chainway-rfid.zip" } }`
4. Creá client actions públicas que envuelvan cada método (ver ejemplos abajo) y una `CheckPluginAvailable`
   que devuelva `typeof ChainwayRfid !== 'undefined'`.
5. Referenciá el módulo desde la app y generá el APK con MABS. **No funciona en preview ni en navegador.**

En ODC el flujo es equivalente, pero el plugin se declara en una **Library** (sección de configuración de plugins).

## Ejemplos de nodos JavaScript

**Init** (en `OnApplicationReady` o en el `OnReady` de la pantalla; output `Error` Text):
```js
ChainwayRfid.init(
  function () { $resolve(); },
  function (e) { $parameters.Error = e; $resolve(); }
);
```

**Iniciar lectura** (llama a una client action `OnTagRead(Epc, Rssi)` de la pantalla):
```js
ChainwayRfid.startInventory({ unique: true }, function (ev) {
  if (ev.type === 'tag') $actions.OnTagRead(ev.epc, ev.rssi);
}, function (e) { $actions.OnRfidError(e); });
```

**Gatillo**: leer mientras se mantiene apretado:
```js
ChainwayRfid.watchTrigger({}, function (ev) {
  if (ev.type !== 'trigger') return;
  if (ev.action === 'down') $actions.StartReading();
  else $actions.StopReading();
});
```

Para descubrir el keycode real del gatillo de tu unidad: `watchTrigger({ logAll: true }, ev => console.log(ev))`
y revisá el log (Chrome `chrome://inspect` con depuración USB). Después fijalo con `{ keyCodes: [293] }` (por ejemplo).

## Notas importantes

- **Una sola app puede usar el módulo UHF.** Si el C72 tiene activa la app *Keyboardemulator* (o la demo *UHF*),
  `init()` falla o el gatillo lo captura esa app. Desactivala o cerrala antes de probar.
- Mientras el inventario está activo, el módulo **solo responde a `stopInventory()`**. Cambiá la potencia antes de leer.
- El plugin detiene la lectura automáticamente cuando la app pasa a background y libera el módulo al cerrarse.
- El `.aar` trae librerías nativas para `armeabi-v7a` y `arm64-v8a`.
