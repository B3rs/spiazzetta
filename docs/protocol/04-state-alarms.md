# 04 - Stove state machine, alarm codes, temperature fields

Source of truth: MyPiazzetta **1.9.91** Cordova bundle, `docs/protocol/source-snapshot` (read-only).
All paths below are relative to that directory. Every row cites `file:line`.

Cloud endpoint used by the app: `https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/`, `clientId` `mypiazzetta`, brand code `458632` (`js/it.omniaweb.config.js:45-48`), app version `1.9.91` (`js/it.omniaweb.config.js:39`).

## Verdict up front (read before using sections 1-2)

**There is no numeric stove state table and no numeric alarm code table anywhere in the bundle.**
The bundle is a generic Omniaweb "agua" client: the code -> label mapping is delivered by the cloud as part of each register definition, and the app stores it at runtime.

- `NativeRegister._parseValuesMap()` builds `this._map[value][lang] = description` from the register's `enc_val` array (`js/agua/NativeRegister.js:497-519`).
- `getFormattedValue()` then renders `_map[value][currentLanguageCode]` (`js/agua/NativeRegister.js:46-57`).
- `getEnabledValues()` reads the register's `enable_val[].value` array (`js/agua/NativeRegister.js:359-380`).
- `getValueByLanguageKey('ON'|'OFF')` searches `_map` for the entry whose text equals `ON`/`OFF` in the current language and returns that numeric value (`js/agua/NativeRegister.js:326-336`).

So the wire shape is fixed and known (see 1.4), but the actual integer list (0 = off, 1 = ... ) is **NOT FOUND** in the bundle. Any concrete numeric list must be captured from a live `status`/`alarms` register definition; it must not be invented.

---

## 1. State machine

### 1.1 App-level power states (the only state enum in the bundle)

Constants (`js/agua/Device.js:38-40`):

| Code (string) | Constant | IT label | EN label | Source |
|---|---|---|---|---|
| `"off"` | `Device.POWER_STATUS_OFF` | "La stufa è spenta" | "OFF" | `js/agua/Device.js:39`; `lang/it.json:1439`; `lang/en.json:1439` |
| `"on"` | `Device.POWER_STATUS_ON` | "La stufa è accesa" | "ON" | `js/agua/Device.js:38`; `lang/it.json:1440`; `lang/en.json:1440` |
| `"undefined"` | `Device.POWER_STATUS_UNDEFINED` | "" (no label rendered) | "" (no label rendered) | `js/agua/Device.js:40`; label `undefined` case `js/brands/mypiazzetta/it.omniaweb.widgets.js:872-877` |

Power-button labels (the button performs the opposite action, so the keys are crossed):

| Key | IT | EN | Source |
|---|---|---|---|
| `device.status.button.off` | "Accendi" | "ON" | `lang/it.json:1430`; `lang/en.json:1430` |
| `device.status.button.on` | "Spegni" | "OFF" | `lang/it.json:1431`; `lang/en.json:1431` |

Status-bar render switches on those three codes and maps them to style/icon: `device-power-on`/`check`, `device-power-off`/`minus`, `device-power-idle`/`warning` (`js/brands/mypiazzetta/it.omniaweb.widgets.js:859-877`). If `hasAlarm()` is true the bar is forced to `device-alarm` and the text becomes `getAlarmLabel()` (`js/brands/mypiazzetta/it.omniaweb.widgets.js:878-881`).

### 1.2 Register keys that drive the state machine

| Register key | Type used | Role | Source |
|---|---|---|---|
| `status` | GET | Current numeric state code (`getValue()`), rendered through `enc_val` | `js/agua/Device.js:1060`; `js/agua/Device.js:1022`; `js/agua/Device.js:1130` |
| `status_managed` | GET (write) | Target state; the app writes the value whose `enc_val` text equals `ON` or `OFF` | `js/agua/Device.js:990-1006` |
| `status_managed_on` | ENABLE | Enable-values list of state codes for which the button powers the stove **ON** (see inversion note) | `js/agua/Device.js:1020`, `1024-1027` |
| `status_managed_off` | ENABLE | Enable-values list of state codes for which the button powers the stove **OFF** | `js/agua/Device.js:1021`, `1028-1031` |
| `alarms` | ENABLE + GET | Enable-values = the state codes that are alarms; GET = alarm text | `js/agua/Device.js:1131`, `1149` |

Label keys: `status_get` -> IT "Stato" / EN "Status" (`lang/it.json:1168-1171`, `lang/en.json:1168-1171`); `status_managed_get` -> IT "Stato Gestito" / EN "Managed Status" (`lang/it.json:1172-1175`, `lang/en.json:1172-1175`); the enable registers are labelled "ON"/"OFF" in both languages (`lang/it.json:1176-1183`, `lang/en.json:1176-1183`).

**Inversion trap (literal source behaviour, do not "fix" it blindly):** `getPowerStatus()` returns `POWER_STATUS_OFF` when the current `status` value is in `status_managed_on`'s enable list, and `POWER_STATUS_ON` when it is in `status_managed_off`'s list (`js/agua/Device.js:1024-1031`). The names describe the *button action* available from that state, not the current state name; the UI pairs `POWER_STATUS_OFF` with the button text "Accendi"/"ON" (`lang/it.json:1430`).

### 1.3 Status text for display

`Device.getStatusLabel()` returns `status` register's `getFormattedValue()` when the code has a translation, otherwise `global.not_available` (`js/agua/Device.js:1058-1062`). `getFormattedValue()` applies the register's `format_string` around the translated text (`js/agua/NativeRegister.js:46-57`).

### 1.4 Numeric state codes - NOT FOUND; payload shape to read instead

No numeric state list exists in `lang/*.json`, `js/`, or `data/`. What a client can rely on:

```jsonc
// register definition returned by the bridge endpoint (shape proven by the parser)
{
  "enc_val":    [ { "value": <int>, "lang": "it_IT", "description": "..." },
                  { "value": <int>, "lang": "en_US", "description": "..." } ],  // js/agua/NativeRegister.js:502-512
  "enable_val": [ { "value": <int> }, ... ]                                    // js/agua/NativeRegister.js:366-378
}
```

Language codes used as `enc_val.lang` keys: `it_IT`, `en_US`, ... (`js/it.omniaweb.language.js:48-67`).
State semantics a Kotlin client must derive from those payloads: current code = `status.enc_val[].value`; on/off groups = `status_managed_on|off.enable_val[].value`; alarm set = `alarms.enable_val[].value`; alarm/state text = `enc_val[].description` per language.

Names such as "off / accensione / working / modulation / cleaning / standby / eco" appear **nowhere** in the bundle as state labels: `grep -rniE 'accensione|pulizia|modulaz|stand.by' js/` returns nothing, and the only Italian occurrence of `Accensione` in `lang/it.json` is the start/chronos widget label (e.g. `lang/it.json:196`, `441`, `469`).

---

## 2. Alarms

### 2.1 Detection and text

| Aspect | Rule | Source |
|---|---|---|
| Is an alarm active? | current `status` value is contained in `alarms` ENABLE register's `enable_val` values | `js/agua/Device.js:1127-1134` |
| Alarm text | `alarms` GET register `getFormattedValue()` (server `enc_val`), else `global.not_available` | `js/agua/Device.js:1145-1155` |
| Alarm label | `alarms_get`: IT "Allarmi" / EN "Alarms" | `lang/it.json:504-507`; `lang/en.json:504-507` |
| UI treatment | status bar forced to `device-alarm` style, verbose text replaced by alarm label | `js/brands/mypiazzetta/it.omniaweb.widgets.js:878-881` |

Alarm code list (numbers + IT/EN text): **NOT FOUND** in the bundle - it comes from `alarms.enc_val`, exactly as in 1.4.

### 2.2 Warning/diagnostic codes that ARE in the bundle

`Device.getWarnings()` (`js/agua/Device.js:1087-1116`) produces these warning objects; their texts are local:

| Warning key | Trigger (numeric condition) | IT text | EN text | Source |
|---|---|---|---|---|
| `voltage_current` | `mancata_tensione` GET == `0` | "La stufa sta segnalando una attuale mancanza di tensione. Verifica i cavi e i collegamenti." | "The stove is signaling a current lack of voltage. Check the cables and connections." | `js/agua/Device.js:1092-1097`; `lang/it.json:1467`; `lang/en.json:1467` |
| `voltage_restart` | `mancata_tensione_riavvio` GET == `0` | "La stufa ha segnalato una mancanza di tensione durante la fase di riavvio." | "The stove has reported a lack of voltage during the restart phase." | `js/agua/Device.js:1100-1105`; `lang/it.json:1468`; `lang/en.json:1468` |
| `auger_load` | `coclea_load` GET == `1` | "Per ripristino condizioni di avvio svuotare il braciere." | "To restore start-up conditions, empty the brazier." | `js/agua/Device.js:1108-1113`; `lang/it.json:1465`; `lang/en.json:1465` |
| `worktime` | `ore_service` >= `ore_service_soglia` (placeholders `{max}`, `{value}`) | "La stufa ha superato le {max} ore di utilizzo (circa {value}). È richiesto l'intervento del tecnico per le procedure di controllo." | "The stove has exceeded {max} hours of use (approximately {value}). The intervention of the technician is required for the control procedures." | `js/agua/Device.js:1271-1278`; `lang/it.json:1469`; `lang/en.json:1469` |
| `pellet` | any register whose group matches `^ris_pellet_([a-z]*)` has a truthy GET value; value = the regex capture (e.g. `ok`, `ris`, `empty`) | pellet-stock block | pellet-stock block | `js/agua/Device.js:1259-1266` |
| warning container title | - | "Attenzione" | "Caution" | `lang/it.json:1466`; `lang/en.json:1466` |

Pellet stock constants: `ok` / `ris` / `empty` (`js/agua/Device.js:41-43`), returned as-is when the only warning is "ok" (`js/agua/Device.js:1282-1291`). Pellet-stock message texts (keys `empty` / `ok` / `ris`): `lang/it.json:454-467`.

### 2.3 Other numeric flags surfaced in the UI

| Register | Value -> meaning | Source |
|---|---|---|
| `coclea_load` | `255` -> show the power button | `js/brands/mypiazzetta/it.omniaweb.widgets.js:893` |
| `cicalino` (buzzer) | `0` -> on images, `1` -> off images | `js/brands/mypiazzetta/it.omniaweb.widgets.js:1547`, `2393-2397` |
| `multicomfort` | `0` / `1` / `2` select different UI blocks; value `2` requires the extra "visible" flag | `js/brands/mypiazzetta/it.omniaweb.widgets.js:2520-2528` |
| `multicomfort` + room probe | probe set `1` with multicomfort value `0` and list entry `2` labelled `WIRELESS`; probe set `0` with value > `1` -> mismatch branch | `js/brands/mypiazzetta/it.omniaweb.widgets.js:2502-2504` |
| `sonda_canale` | `< 33` -> DIP switch label `OFF`, else `ON` | `js/agua/TemperatureProbes.js:201-204` |

---

## 3. Temperature fields

Register keys and their localized labels (both language files are line-aligned for these keys). Probe registers are discovered by regex `^sonda_(\d)_(.+)`, sub-key `temp` = temperature, `adr` = address, with channel register `sonda_canale` (`js/agua/Device.js:935-978`).

| Register key | Role | IT full / short | EN full / short | Source |
|---|---|---|---|---|
| `temp_air_get` | Room temperature (read) | "Temperatura Ambiente" / "Temp. ambiente" | "Room Temperature" / "Room Temp" | `lang/it.json:1184-1187`; `lang/en.json:1184-1187` |
| `temp_air_set` | Room temperature setpoint | "Set Temperatura Ambiente" / "Temp. ambiente" | "Set Room Temperature" / "Room Temp." | `lang/it.json:1196-1199`; `lang/en.json:1196-1199` |
| `temp_air_min` | Room temperature min | "Temperatura Ambiente Min" / "Temp. Aria min" | "Min Room Temperature" / "Min Air Temp." | `lang/it.json:1192-1195`; `lang/en.json:1192-1195` |
| `temp_air_max` | Room temperature max | "Temperatura Ambiente Max" / "Temp. Aria max" | "Max room Temperature" / "Max Air Temp." | `lang/it.json:1188-1191`; `lang/en.json:1188-1191` |
| `temp_gas_flue_get` | Flue/smoke temperature | "Temperatura Fumi" / "Temp. fumi" | "Smoke Temperature" / "Smoke Temp." | `lang/it.json:1200-1203`; `lang/en.json:1200-1203` |
| `temp_probe_k_get` | Flue probe type K | "Sonda Fumi tipo K" / "Temp. fumi K" | "Smoke Probe Type K" / "Smoke Temp. K" | `lang/it.json:1204-1207`; `lang/en.json:1204-1207` |
| `temp_water_get` | Water temperature | "Temperatura Acqua" / "Temp. acqua" | "Water Temperature" / "Water Temp." | `lang/it.json:1232-1235`; `lang/en.json:1232-1235` |
| `temp_water_set` | Water setpoint | "Set Temperatura Acqua" / "Temp. acqua" | "Set Water Temperature" / "Water Temp." | `lang/it.json:1268-1271`; `lang/en.json:1268-1271` |
| `temp_water_min` / `temp_water_max` | Water min / max | "Temperatura Acqua (Caldaia) Min" / "Max" | "(Boiler) Water Temperature Min" / "Max" | `lang/it.json:1236-1243`; `lang/en.json:1236-1243` |
| `temp_water_boiler_get` / `_set` / `_min` / `_max` / `_enable` | Boiler temperature block | "Temperatura Boiler" etc. | "Boiler Temperature" etc. | `lang/it.json:1208-1227`; `lang/en.json:1208-1227` |
| `temp_water_puffer_get` / `_set` / `_min` / `_max` / `_enable` | Puffer/buffer temperature block | "Temperatura Puffer" etc. | "Buffer Temperature" etc. | `lang/it.json:1244-1263`; `lang/en.json:1244-1263` |
| `temp_water_san_set` | Sanitary water setpoint | "Set Temperatura Acqua Sanitaria" / "Temp. acqua san." | "Set Sanitary Water Temperature" / "San. Water Temp." | `lang/it.json:1264-1267`; `lang/en.json:1264-1267` |
| `canalization_1_temp_air_get` / `_set` / `_min` / `_max` / `_enable` | Duct 1 air temperature block | "Canalizzazione 1 Temperatura aria ..." | "Duct 1 air temp. ..." | `lang/it.json:540-559`; `lang/en.json:540-559` |
| `canalization_2_temp_air_get` / `_set` / `_min` / `_max` / `_enable` | Duct 2 air temperature block | "Canalizzazione 2 Temperatura aria ..." | as above, Duct 2 | `lang/it.json:576-595`; `lang/en.json:576-595` |
| `sonda_<n>_temp` | Wireless probe temperature, `n` = 1-based index | labels resolved from server | labels resolved from server | `js/agua/Device.js:939`, `952-971` |
| `unit_measure` | Temperature unit selector (SET value = index 0/1) | - | - | `js/agua/agua.js:51-61`, `75-92` |

Temperature units and conversion formulas (applied client-side to any register whose `is_temperature` flag is set, `js/agua/NativeRegister.js:101-120`):

| Key | index | formula | IT label | EN label | symbol | Source |
|---|---|---|---|---|---|---|
| `celsius` | 0 | `#` | "Gradi Celsius" | "Degrees Celsius" | `°C` | `lang/it.json:1557-1563`; `lang/en.json:1557-1563` |
| `fahrenheit` | 1 | `int(#*9/5)+32` | "Gradi Fahrenheit" | "Degrees Fahrenheit" | `°F` | `lang/it.json:1564-1570`; `lang/en.json:1564-1570` |

Default when no `unit_measure` register is available: local param, falling back to `celsius` (`js/agua/agua.js:59`).

---

## 4. Localization and resource/id mapping sources

| Source | What it holds | Evidence |
|---|---|---|
| `lang/it.json` | Italian bundle (58,807 bytes): UI strings, `device.registers.<key>.{full,short}`, `device.status.*`, `device.warnings.*`, `global.temperature_units_of_measure` | file exists; keys verified e.g. `lang/it.json:503`, `1428-1442`, `1464-1470`, `1556-1571` |
| `lang/en.json` | English bundle (54,806 bytes), same key tree | `lang/en.json:503`, `1428-1441`, `1464-1470`, `1556-1571` |
| `lang/{cs,da,de,es,fr,hr,nl,pl,pt}.json` | 9 further locales shipped in the APK | not line-citable: `ls lang/` lists these 9 files plus `it.json`/`en.json` (11 total) |
| `js/it.omniaweb.language.js` | bundle loader and language code map | `:48-67` (code map), `:98-118` (`loadLanguageBundle("lang/<lang>.json")`), `:75-90` (`setCurrentLanguage` guard) |
| `js/it.omniaweb.config.js` | `defaultLanguage: "it"`; brand + endpoint config; language display names | `:38`, `:41-68`, `:69-81` |
| `js/brands/mypiazzetta/it.omniaweb.widgets.js` | status-bar component that joins state codes to `device.status.*` labels | `:851-889`, `:916-937` |
| `js/agua/NativeRegister.js` | runtime code -> localized text map from server `enc_val` / `enable_val` | `:46-57`, `:326-336`, `:359-380`, `:497-519` |
| `js/it.omniaweb.components.js` | register label lookup `device.registers.<key>.<short\|full>` and register icon lookup | `:81-86`, `:88-91` |
| `js/agua/Register.js` | `getLabelKey()` selects the native register key used for the label path | `:333-352` |
| `img/stoves/*.svg` (85 files) | per-register icon assets; the name comes from server field `app_image_name`, not from a local table | `js/agua/NativeRegister.js:290-300`; `js/it.omniaweb.logic.js:1764-1776`; files include `status_set.svg`, `status_managed_on_enable.svg`, `temp_air_get.svg`, `temp_gas_flue_get.svg`, `alarms_get.svg` |
| `data/countries.json` | only static data file besides the language bundles (country list), 5,295 bytes | not line-citable: `ls data/` shows this single file |

NOT FOUND: any register-catalog file (e.g. a dumped `registers.json`) in the bundle; any mapping file that pairs numeric state/alarm codes with labels; locale files for `bs`, `ru`, `sr`, `uk` despite the language-code map listing them (`js/it.omniaweb.language.js:51-65`).

### How the two label paths work (for a Kotlin port)

1. **Register label** (what a field is called): `device.registers.<registerKey>.<full|short>` (`js/it.omniaweb.components.js:81-86` + `js/agua/Register.js:333-352`), resolved from `lang/<lang>.json`.
2. **Register value** (what the current value means): server `enc_val` entries `{value, lang, description}` -> `_map[value][lang]` (`js/agua/NativeRegister.js:497-519`, `46-57`). This is why a Kotlin UI must cache the state/alarm `enc_val` from the device definition rather than hardcode the strings.
