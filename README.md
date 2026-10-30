# cordova-plugin-chainway-rfid

Plugin Cordova para Android que conecta una aplicación móvil con el lector RFID UHF, el escáner de códigos de barras/QR y el gatillo físico de un Chainway C72 mediante el SDK `DeviceAPI.aar` incluido. También puede integrarse en OutSystems. No funciona en navegador ni en la vista previa de OutSystems.

Versión del paquete: **0.3.3**; identificador de diagnóstico de JavaScript y Java: **0.3.3-DIAG**. El identificador del plugin es `cordova-plugin-chainway-rfid`.

## Instalación

En un proyecto Cordova Android, desde la carpeta del proyecto:

```sh
cordova plugin add /ruta/a/RFID_Cordova_Plugin
cordova build android
```

Para OutSystems 11, publica esta carpeta en un repositorio Git o empaquétala en un ZIP. Crea un módulo Mobile Plugin y configura el plugin en **Extensibility Configurations**, por ejemplo:

```json
{ "plugin": { "url": "https://github.com/organizacion/repositorio.git" } }
```

Alternativamente, agrega el ZIP como recurso con *Deploy to Target Directory* y usa `{ "plugin": { "resource": "chainway-rfid.zip" } }`. Crea client actions que envuelvan los métodos necesarios, referencia el módulo desde la app y genera una aplicación Android con MABS. En ODC, configura el plugin en una Library. La URL es ilustrativa y debe apuntar al repositorio real.

Espera al evento `deviceready` de Cordova o a `OnApplicationReady` en OutSystems antes de usar el plugin. Puedes comprobar su disponibilidad con `typeof ChainwayRfid !== 'undefined'`.

## API JavaScript

Todos los métodos utilizan callbacks de Cordova. `ok` recibe el resultado y `err` recibe un mensaje de error. Los métodos de escucha mantienen abierto su callback de eventos hasta detener o liberar la función correspondiente.

| Método | Resultado o comportamiento |
|---|---|
| `getDiagInfo(ok, err)` | Objeto con `version`, `uhfInitialized`, `uhfInventorying`, `barcodeInitialized` y `watchingTrigger`. |
| `init(ok, err)` | Inicializa UHF; devuelve `UHF_INIT_OK` o `UHF_ALREADY_INIT`. Libera el lector 2D previamente. |
| `free(ok, err)` | Detiene el inventario, libera UHF y devuelve `UHF_FREE_OK`. |
| `startInventory(opts, onEvent, err)` | Inicia inventario continuo. `opts.unique` por defecto es `true`: emite una sola vez cada EPC. Con `false` emite lecturas repetidas. |
| `stopInventory(ok, err)` | Detiene el inventario y devuelve `{ total, wasRunning }`. `total` cuenta EPC distintos, incluso con `unique: false`. |
| `readSingle(ok, err)` | Lee una etiqueta y devuelve `{ epc, tid, rssi }`; informa error si no detecta ninguna. |
| `setPower(dbm, ok, err)` / `getPower(ok, err)` | Configura o consulta la potencia UHF en dBm. Detén el inventario primero. El rango válido depende del dispositivo y SDK. |
| `initBarcode(ok, err)` | Inicializa el escáner 2D; devuelve `BARCODE_INIT_OK` o `BARCODE_ALREADY_INIT`. Libera UHF previamente. |
| `startBarcodeScan(onEvent, err)` | Inicia el escáner y entrega eventos de lectura o estado. Requiere `initBarcode`. |
| `stopBarcodeScan(ok, err)` | Detiene el escaneo y devuelve `BARCODE_STOP_OK`. |
| `freeBarcode(ok, err)` | Detiene y libera el escáner 2D; devuelve `BARCODE_FREE_OK`. |
| `watchTrigger(opts, onEvent, err)` | Escucha teclas en la vista web. `keyCodes` reemplaza la lista predeterminada; `logAll` es `true` por defecto. |
| `unwatchTrigger(ok, err)` | Deja de escuchar teclas y cierra el callback de eventos. |
| `startTriggerEvents(opts, ok, err)` | Activa la escucha y publica eventos DOM `chainwayrfidtrigger` en `window` para las teclas configuradas como gatillo. `ok` confirma la suscripción. |
| `stopTriggerEvents(ok, err)` | Detiene la escucha del gatillo. Comparte la misma escucha con `watchTrigger` y `unwatchTrigger`. |

Con `startTriggerEvents`, el evento DOM `chainwayrfidkey` entrega cada tecla admitida por la opción `logAll`; `chainwayrfidtrigger` sigue entregando solo las teclas reconocidas como gatillo. El callback de inicio recibe el evento `watching`, con `hasFocus`, `logAll` y `keyCodes`. Estos datos indican el estado al registrar el listener; no prueban que el dispositivo le entregue las teclas.

La ventana Android captura las teclas configuradas como gatillo, emite sus eventos `down` / `up` y las consume; las demás teclas se delegan al callback original. Con `logAll: true` se publican diagnósticos en `chainwayrfidwindowkey` y en `chainwayrfidkey` con una acción prefijada por `ventana:`. `watching.windowDiagnostics` indica si se instaló el observador; si falla, se usa la escucha de la vista web. El callback original se restaura al cerrar la escucha si sigue siendo el callback instalado por el plugin. Si otra aplicación o Android intercepta la tecla antes de que llegue a la ventana, no aparecerá. Usa `logAll` solo para diagnosticar: se registran códigos de otras teclas mientras esté activo.

### Eventos

- Inventario: `{ type: 'started' }`, `{ type: 'tag', epc, tid, rssi }` y `{ type: 'stopped', total }`. `tid` y `rssi` pueden ser cadenas vacías si el SDK no los proporciona. No se configura una lectura adicional de TID.
- Escáner 2D: `{ type: 'scan_started' }`, `{ type: 'barcode', data, resultCode }` si la decodificación tiene éxito y `{ type: 'barcode_status', resultCode }` para otros resultados del SDK. Detener o liberar el escáner cierra la escucha sin enviar un evento final.
- Gatillo: `{ type: 'watching' }` y `{ type: 'trigger', action: 'down' | 'up', keyCode, isTrigger }`. Con `logAll: true` también se notifican otras teclas; usa `isTrigger` para distinguirlas. Las teclas reconocidas son consumidas por el listener.

## Ejemplos

Inicializar RFID y leer etiquetas:

```js
ChainwayRfid.init(function () {
  ChainwayRfid.startInventory({ unique: true }, function (event) {
    if (event.type === 'tag') console.log(event.epc, event.tid, event.rssi);
    if (event.type === 'stopped') console.log('EPC distintos:', event.total);
  }, console.error);
}, console.error);

// Cuando corresponda:
ChainwayRfid.stopInventory(function (result) {
  console.log(result.total, result.wasRunning);
}, console.error);
```

Escanear un código de barras o QR:

```js
ChainwayRfid.initBarcode(function () {
  ChainwayRfid.startBarcodeScan(function (event) {
    if (event.type === 'barcode') console.log('Código:', event.data);
    if (event.type === 'barcode_status') console.log('Estado:', event.resultCode);
  }, console.error);
}, console.error);

// Al terminar:
ChainwayRfid.stopBarcodeScan(function () {
  ChainwayRfid.freeBarcode(function () {}, console.error);
}, console.error);
```

Escuchar el gatillo con un código conocido:

```js
ChainwayRfid.watchTrigger({ keyCodes: [293], logAll: false }, function (event) {
  if (event.type !== 'trigger' || !event.isTrigger) return;
  if (event.action === 'down') console.log('Presionado');
  if (event.action === 'up') console.log('Soltado');
}, console.error);
```

El código `293` se observó en el gatillo del C72 probado y es el único reconocido por defecto. En otra unidad verifica el código con `watchTrigger({ logAll: true }, ...)` y configúralo mediante `keyCodes`. El cambio de la lista predeterminada afecta a quienes dependían de los códigos 139, 280, 291, 294, 311, 312, 313 o 315: deben configurarlos explícitamente. Para volver a escuchar con otra configuración, llama primero a `unwatchTrigger`.

En un nodo JavaScript de OutSystems, usa los callbacks para resolver la acción y transmitir errores; para eventos continuos llama a una client action, por ejemplo `$actions.OnTagRead(event.epc, event.rssi)`. No resuelvas la acción de inventario antes de haber establecido cómo gestionarás sus eventos.

### Inicialización, cierre y eventos en OutSystems

Los lectores tienen inicializadores y cierres independientes: llama a `init` / `free` para RFID y a `initBarcode` / `freeBarcode` para códigos de barras. Espera el callback de éxito antes de iniciar una lectura, cambiar de lector o cerrar la pantalla. Al cambiar de lector, el plugin libera el anterior automáticamente. El gatillo se inicia con `startTriggerEvents` y se cierra con `stopTriggerEvents`; no inicia lecturas por sí mismo.

Para recibir el gatillo como un evento en OutSystems, crea una client action de pantalla `OnTrigger` con entradas `Action` (Text) y `KeyCode` (Integer), y otra `OnTriggerError` con entrada `Error` (Text). Añádelas como dependencias del nodo JavaScript. En el evento `OnReady` de la pantalla, instala el listener e inicia la escucha:

```js
// Evita conservar un listener anterior si se vuelve a entrar en la pantalla.
if (window.chainwayTriggerHandler) {
  window.removeEventListener('chainwayrfidtrigger', window.chainwayTriggerHandler);
}
window.chainwayTriggerHandler = function (event) {
  $actions.OnTrigger(event.detail.action, event.detail.keyCode);
};
window.addEventListener('chainwayrfidtrigger', window.chainwayTriggerHandler);

ChainwayRfid.startTriggerEvents({ logAll: false }, function () {
  console.log('Escucha del gatillo iniciada');
}, function (error) {
  window.removeEventListener('chainwayrfidtrigger', window.chainwayTriggerHandler);
  window.chainwayTriggerHandler = null;
  $actions.OnTriggerError(String(error));
});
```

En `OnTrigger`, decide qué hacer con `Action`: por ejemplo, iniciar inventario en `down` y detenerlo en `up`, o ejecutar cualquier otra lógica. Configura `keyCodes` si el dispositivo usa un código diferente de los predeterminados. Si necesitas descubrirlo, usa `watchTrigger({ logAll: true }, ...)` como se indica arriba. Solo debe haber una pantalla gestionando esta escucha a la vez.

En `OnDestroy` de la pantalla, retira el listener y detén la escucha:

```js
if (window.chainwayTriggerHandler) {
  window.removeEventListener('chainwayrfidtrigger', window.chainwayTriggerHandler);
  window.chainwayTriggerHandler = null;
}
ChainwayRfid.stopTriggerEvents(function () {}, function (error) {
  console.error(error);
});
```

Detén también el inventario o escaneo y libera el lector que inicializaste cuando la aplicación termine de usarlo. Si el hardware se comparte entre pantallas, administra su cierre desde el punto común que controla su ciclo de vida.

## Operación y limitaciones

- UHF y el escáner 2D se tratan como recursos excluyentes: inicializar uno libera el otro. Espera el callback de cada operación antes de iniciar otra; evita operaciones concurrentes.
- El plugin intenta desactivar KeyboardEmulator mediante broadcasts, pero su efecto depende del dispositivo. Cierra KeyboardEmulator, la demo UHF u otras apps que ocupen el hardware si falla la inicialización.
- Al pasar a segundo plano se detienen el inventario y el escaneo 2D. El inventario emite `stopped`; para volver a leer, inicia otra lectura al regresar. Al destruirse la actividad se liberan los lectores.
- Se incluyen bibliotecas nativas `armeabi-v7a` y `arm64-v8a` en el SDK. El funcionamiento requiere un dispositivo y una versión del SDK compatibles.
- La API permite leer etiquetas; no incluye escritura, bloqueo ni borrado de memoria RFID.

## Verificación en dispositivo

Compila una app anfitriona e instala el APK en un Chainway C72. Con las apps competidoras cerradas, comprueba inicialización y liberación; lectura individual y continua, duplicados y total; potencia con inventario detenido; eventos del gatillo; lectura 2D; cambio entre UHF y 2D; y comportamiento al pasar a segundo plano. Registra modelo, versión de Android, versión del plugin, compilación y resultados. No hay una suite automatizada ni una configuración de compilación independiente en este repositorio.
