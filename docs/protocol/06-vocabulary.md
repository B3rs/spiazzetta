# 06 - User-facing vocabulary (IT / EN)

Source of truth: `docs/protocol/source-snapshot` (MyPiazzetta 1.9.91 Cordova build, read-only).
The app ships one flat string table per locale in `lang/<code>.json` - this build contains 11 (cs, da, de, en, es, fr, hr, it, nl, pl, pt) - loaded at runtime by `js/it.omniaweb.language.js`. The product is Italian, so `lang/it.json` is the reference wording; `lang/en.json` is its English counterpart. Both files are structurally identical (986 leaf keys each), so the two line numbers in each row coincide.

Every row below was read from those two files; "file:line" gives the physical line of the value in each. Where the app has no such string the row is not invented - see **NOT FOUND** at the end.

The app's identifiers keep the manufacturer's (Italian) register names: `temp_air` = room air temperature, `temp_water` = water, `vent_main` = ventilation, `power` = power level, `chrono` = weekly programmer, `coclea` = auger, `braciere` = brazier, `fumi` = flue gas, `Navel` = the Wi-Fi module.

## Vocabulary table

### Power on/off and stove status

| key | IT | EN | file:line |
|---|---|---|---|
| `device.status.button.off` | Accendi | ON | lang/it.json:1430 / lang/en.json:1430 |
| `device.status.button.on` | Spegni | OFF | lang/it.json:1431 / lang/en.json:1431 |
| `device.status.on` | La stufa è accesa | ON | lang/it.json:1440 / lang/en.json:1440 |
| `device.status.off` | La stufa è spenta | OFF | lang/it.json:1439 / lang/en.json:1439 |
| `device.status.status` | Stato | State | lang/it.json:1441 / lang/en.json:1441 |
| `device.status.confirm.title` | Conferma spegnimento prodotto | Confirm Product Shutdown | lang/it.json:1437 / lang/en.json:1437 |
| `device.status.confirm.description` | Sei sicuro di voler spegnere il prodotto? | Are you sure you want to shut down the product? | lang/it.json:1436 / lang/en.json:1436 |
| `device.registers.status_managed_get.full` | Stato Gestito | Managed Status | lang/it.json:1173 / lang/en.json:1173 |
| `device.registers.status_managed_on_enable.full` | ON | ON | lang/it.json:1181 / lang/en.json:1181 |
| `device.registers.status_managed_off_enable.full` | OFF | OFF | lang/it.json:1177 / lang/en.json:1177 |
| `device.registers.status_get.full` | Stato | Status | lang/it.json:1169 / lang/en.json:1169 |
| `devices_list.review` | Stato stufa | Stove status | lang/it.json:1491 / lang/en.json:1491 |

### Temperature (setpoint / room)

| key | IT | EN | file:line |
|---|---|---|---|
| `device.registers.temp_air_set.full` | Set Temperatura Ambiente | Set Room Temperature | lang/it.json:1197 / lang/en.json:1197 |
| `device.registers.temp_air_set.short` | Temp. ambiente | Room Temp. | lang/it.json:1198 / lang/en.json:1198 |
| `device.registers.temp_air_get.full` | Temperatura Ambiente | Room Temperature | lang/it.json:1185 / lang/en.json:1185 |
| `device.registers.temp_air_get.short` | Temp. ambiente | Room Temp | lang/it.json:1186 / lang/en.json:1186 |
| `device.registers.temp_air_max.full` | Temperatura Ambiente Max | Max room Temperature | lang/it.json:1189 / lang/en.json:1189 |
| `device.registers.temp_air_min.full` | Temperatura Ambiente Min | Min Room Temperature | lang/it.json:1193 / lang/en.json:1193 |
| `device.registers.temp_water_set.full` | Set Temperatura Acqua | Set Water Temperature | lang/it.json:1269 / lang/en.json:1269 |
| `device.registers.temp_water_get.full` | Temperatura Acqua | Water Temperature | lang/it.json:1233 / lang/en.json:1233 |
| `device.registers.temp_water_boiler_set.full` | Set Temperatura Boiler | Set Boiler Temperature | lang/it.json:1225 / lang/en.json:1225 |
| `device.registers.temp_water_boiler_get.full` | Temperatura Boiler | Boiler Temperature | lang/it.json:1213 / lang/en.json:1213 |
| `device.registers.temp_water_puffer_set.full` | Set Temperatura Puffer | Set Buffer Temperature | lang/it.json:1261 / lang/en.json:1261 |
| `device.registers.temp_water_puffer_get.full` | Temperatura Puffer | Buffer Temperature | lang/it.json:1249 / lang/en.json:1249 |
| `device.registers.temp_water_san_set.full` | Set Temperatura Acqua Sanitaria | Set Sanitary Water Temperature | lang/it.json:1265 / lang/en.json:1265 |
| `device.registers.temp_gas_flue_get.full` | Temperatura Fumi | Smoke Temperature | lang/it.json:1201 / lang/en.json:1201 |
| `device.registers.ext_therm_get.full` | Contatto termostato esterno | External thermostat | lang/it.json:1005 / lang/en.json:1005 |
| `device.registers.temp_probe_k_get.full` | Sonda Fumi tipo K | Smoke Probe Type K | lang/it.json:1205 / lang/en.json:1205 |

### Fan / ventilation

| key | IT | EN | file:line |
|---|---|---|---|
| `device.registers.vent_main_set.full` | Set Vent. Ambiente | Set Ambient Ventilation | lang/it.json:1273 / lang/en.json:1273 |
| `device.registers.vent_main_set.short` | Vent. Ambiente | Ambient Vent. | lang/it.json:1274 / lang/en.json:1274 |

### Power level

| key | IT | EN | file:line |
|---|---|---|---|
| `device.registers.power_set.full` | Set Potenza | Power Set | lang/it.json:1145 / lang/en.json:1145 |
| `device.registers.power_set.short` | Potenza | Power | lang/it.json:1146 / lang/en.json:1146 |
| `device.registers.power_enable.full` | Abilitazione Set Potenza | Set Power Enable | lang/it.json:1117 / lang/en.json:1117 |
| `device.registers.power_min.full` | Set Potenza Min | Set Power Min | lang/it.json:1125 / lang/en.json:1125 |
| `device.registers.power_max.full` | Set Potenza Max | Set Power Max | lang/it.json:1121 / lang/en.json:1121 |
| `device.registers.power_auto_set.full` | Set regolazione potenza in modalità auto | Power auto set | lang/it.json:1113 / lang/en.json:1113 |
| `device.registers.power_night_set.full` | Set Potenza Notte | Night Power Set | lang/it.json:1141 / lang/en.json:1141 |
| `device.registers.real_power_get.full` | Potenza Reale | Real Power | lang/it.json:1165 / lang/en.json:1165 |

### Eco / comfort

| key | IT | EN | file:line |
|---|---|---|---|
| `device.registers.eco_stop_set.full` | Set ECO_STOP | Set ECO_STOP | lang/it.json:1001 / lang/en.json:1001 |
| `device.registers.eco_stop_set.short` | ECO STOP | ECO STOP | lang/it.json:1002 / lang/en.json:1002 |
| `device.settings.air_energy_saving.title` | Energy saving AIR | Energy saving AIR | lang/it.json:1370 / lang/en.json:1370 |
| `device.settings.air_energy_saving.list` | Energy saving AIR | Energy saving AIR | lang/it.json:1367 / lang/en.json:1367 |
| `device.settings.air_energy_saving.start` | Accensione | Power ON | lang/it.json:1368 / lang/en.json:1368 |
| `device.settings.air_energy_saving.stop` | Spegnimento | Shutdown | lang/it.json:1369 / lang/en.json:1369 |
| `device.settings.hydro_energy_saving.title` | Energy saving HYDRO | Energy saving HYDRO | lang/it.json:1398 / lang/en.json:1398 |
| `device.settings.multicomfort.title` | Multicomfort | Multicomfort | lang/it.json:1402 / lang/en.json:1402 |
| `device.settings.multicomfort.list` | Multicomfort | Multicomfort | lang/it.json:1401 / lang/en.json:1401 |

### Chrono / weekly programmer

| key | IT | EN | file:line |
|---|---|---|---|
| `device.registers.chrono_p1_fan_set.full` | Set Ventilazione Chrono P1 | Set Ventilation Chrono P1 | lang/it.json:633 / lang/en.json:633 |
| `device.registers.chrono_p1_fan_set.short` | Imposta ventilazione P1 | Set Ventilation P1 | lang/it.json:634 / lang/en.json:634 |
| `chronos.program.title` | Cronotermostato | Thermostatic Timer Program | lang/it.json:197 / lang/en.json:197 |
| `chronos.general_enable.title` | Attiva o disattiva il cronotermostato generale | Activate or deactivate the general chronothermostat | lang/it.json:173 / lang/en.json:173 |
| `chronos.weekday_enable.title` | Attiva o disattiva il cronotermostato settimanale | Activate or deactivate the weekly timer thermostat | lang/it.json:210 / lang/en.json:210 |
| `chronos.program.start` | Accensione | Power ON | lang/it.json:196 / lang/en.json:196 |
| `chronos.program.until` | Spegnimento | Power OFF | lang/it.json:198 / lang/en.json:198 |
| `chronos.program.days.days_title` | Giorni della settimana | Weekdays | lang/it.json:183 / lang/en.json:183 |
| `chronos.program.days.days_7` | Tutti i giorni | Everyday | lang/it.json:181 / lang/en.json:181 |
| `chronos.program.days.days_select` | Scegli uno o più giorni | Choose one or more days | lang/it.json:182 / lang/en.json:182 |
| `chronos.program.set_days_and_times` | Imposta giorni e orari | Days and timetable settings | lang/it.json:195 / lang/en.json:195 |
| `chronos.program.hours` | Dalle {0} alle {1} | From {0} to {1} | lang/it.json:190 / lang/en.json:190 |
| `chronos.program.save` | Salva programma | Save program | lang/it.json:194 / lang/en.json:194 |
| `chronos.program.not_set` | Non impostato | Not set | lang/it.json:192 / lang/en.json:192 |
| `chronos.program.enable` | Abilita | Enable | lang/it.json:189 / lang/en.json:189 |
| `device.registers.chrono_general_enable_set.full` | Abilitazione Chrono Generale | General Chrono Enable | lang/it.json:597 / lang/en.json:597 |
| `device.registers.chrono_week_enable_set.full` | Abilitazione Chrono Settimanale | Weekly Chrono Enable | lang/it.json:985 / lang/en.json:985 |
| `devices_list.cronos` | Programmi | Programs | lang/it.json:1481 / lang/en.json:1481 |
| `device.properties.chronos` | Programming | Programming | lang/it.json:475 / lang/en.json:475 |

### Alarms and warnings

| key | IT | EN | file:line |
|---|---|---|---|
| `device.registers.alarms_get.full` | Allarmi | Alarms | lang/it.json:505 / lang/en.json:505 |
| `device.registers.alarms_get.short` | Allarmi | Alarms | lang/it.json:506 / lang/en.json:506 |
| `device.warnings.title` | Attenzione | Caution | lang/it.json:1466 / lang/en.json:1466 |
| `device.warnings.voltage_current` | La stufa sta segnalando una attuale mancanza di tensione. Verifica i cavi e i collegamenti. | The stove is signaling a current lack of voltage. Check the cables and connections. | lang/it.json:1467 / lang/en.json:1467 |
| `device.warnings.worktime` | La stufa ha superato le {max} ore di utilizzo (circa {value}). È richiesto l'intervento del tecnico per le procedure di controllo. | The stove has exceeded {max} hours of use (approximately {value}). The intervention of the technician is required for the control procedures. | lang/it.json:1469 / lang/en.json:1469 |
| `device.warnings.auger_load` | Per ripristino condizioni di avvio svuotare il braciere. | To restore start-up conditions, empty the brazier. | lang/it.json:1465 / lang/en.json:1465 |

### Connection errors / offline

| key | IT | EN | file:line |
|---|---|---|---|
| `common.network-offline` | Devi essere collegato ad internet per poter utilizzare l'applicazione.<br />Controlla la connessione e prova di nuovo. | You must be connected to internet to use this application.<br />Please check your connection and try again. | lang/it.json:215 / lang/en.json:215 |
| `common.error-message` | Si è verificato un errore e non è stato possibile completare la richiesta. | An error occurred and your request could not be fulfilled. | lang/it.json:214 / lang/en.json:214 |
| `common.retry` | Riprova | Retry | lang/it.json:216 / lang/en.json:216 |
| `global.messages.inactivity.content` | Non è stato possibile stabilire la comunicazione tra stufa e modulo. | Communication between the stove and the module could not be established. | lang/it.json:1536 / lang/en.json:1536 |
| `global.messages.error.title` | Si è verificato un errore | An error occurred | lang/it.json:1533 / lang/en.json:1533 |
| `global.messages.ble_device_unavailable.content` | Non è stato possibile collegarsi alla stufa tramite ble. Verifica che la stufa sia accesa, di aver attivato il bluetooth sul tuo dispositivo, e di NON aver associato/accoppiato il ble della stufa al tuo dispositivo. | It was not possible to connect to the stove via ble. Check that the stove is on, that you have activated the bluetooth on your device, and that you have NOT associated / paired the stove ble with your device. | lang/it.json:1528 / lang/en.json:1528 |
| `device.connection.bluetooth.connectionfail.title` | Errore connessione | Connection error | lang/it.json:310 / lang/en.json:310 |
| `device.connection.bluetooth.connectionfail.message` | Non è stato possibile connettersi alla rete Wi-Fi selezionata.<br />Verifica la password e prova di nuovo in un secondo momento. | It was not possible to connect to the selected Wi-Fi network.<br />Check the password and try again later. | lang/it.json:308 / lang/en.json:308 |
| `device.connection.wifi.title` | Verifica collegamento | Check the connection | lang/it.json:372 / lang/en.json:372 |

### Firmware

| key | IT | EN | file:line |
|---|---|---|---|
| `device.properties.firmware` | Firmware | Firmware | lang/it.json:489 / lang/en.json:489 |
| `device.registers.hw_navel_fw_revision_get.full` | Revisione FW Scheda Navel | FW rev. Wifi card | lang/it.json:1013 / lang/en.json:1013 |
| `device.registers.hw_navel_fw_type_get.full` | FW Scheda Navel | FW Wifi card | lang/it.json:1017 / lang/en.json:1017 |
| `device.registers.hw_power_fw_revision_get.full` | Revisione FW Scheda potenza | FW rev. Rwms | lang/it.json:1025 / lang/en.json:1025 |
| `global.messages.upgrade_app.title` | Aggiornamento app necessario | App update required | lang/it.json:1542 / lang/en.json:1542 |
| `global.messages.upgrade_app.open_store` | Aggiorna | Update | lang/it.json:1541 / lang/en.json:1541 |

### Login fields

| key | IT | EN | file:line |
|---|---|---|---|
| `login.title` | Login | Login | lang/it.json:1616 / lang/en.json:1616 |
| `login.username` | E-mail | E-mail | lang/it.json:1617 / lang/en.json:1617 |
| `login.password` | Password | Password | lang/it.json:1605 / lang/en.json:1605 |
| `login.button` | Accedi | Login | lang/it.json:1599 / lang/en.json:1599 |
| `login.reset` | Recupera password | Reset your password | lang/it.json:1606 / lang/en.json:1606 |
| `login.subscribe` | Registrati | Activate | lang/it.json:1601 / lang/en.json:1601 |
| `login.activate` | Attiva account | Activate your account | lang/it.json:1598 / lang/en.json:1598 |
| `login.subscription` | Non sei registrato? | Do you have an account? | lang/it.json:1612 / lang/en.json:1612 |
| `login.reset_password.text` | Hai dimenticato la password? | Did you forget your password? | lang/it.json:1609 / lang/en.json:1609 |
| `login.reset_password.button` | Reimpostala | Reset it | lang/it.json:1608 / lang/en.json:1608 |
| `login.success.title` | Login effettuato | You are logged in | lang/it.json:1614 / lang/en.json:1614 |
| `login.failure.text` | I dati di accesso che hai inserito non sono corretti. | Your credentials are not valid. | lang/it.json:1602 / lang/en.json:1602 |
| `login.failure.title` | Login non riuscito | Login failed | lang/it.json:1603 / lang/en.json:1603 |

### Natural mode

| key | IT | EN | file:line |
|---|---|---|---|
| `device.natural_mode.title` | Natural mode | Natural mode | lang/it.json:443 / lang/en.json:443 |
| `device.natural_mode.start` | Accensione | Power ON | lang/it.json:441 / lang/en.json:441 |
| `device.natural_mode.until` | Spegnimento | Shutdown | lang/it.json:444 / lang/en.json:444 |
| `device.natural_mode.on` | On | On | lang/it.json:440 / lang/en.json:440 |
| `device.natural_mode.off` | Off | Off | lang/it.json:439 / lang/en.json:439 |

### Other device vocabulary

| key | IT | EN | file:line |
|---|---|---|---|
| `dialogs.device_status.shutdown.title` | Spegnimento stufa | Stove shutdown | lang/it.json:1507 / lang/en.json:1507 |
| `dialogs.device_status.shutdown.message` | Confermare lo spegnimento? | Confirm shutdown? | lang/it.json:1506 / lang/en.json:1506 |
| `global.not_available` | Non disponibile | Not available | lang/it.json:1547 / lang/en.json:1547 |
| `dialogs.device_status.not_connected.message` | Non è possibile connettersi alla stufa (la stufa non è connessa in modalità wifi). | It is not possible to connect to the stove (the stove is not connected in wifi mode) | lang/it.json:1500 / lang/en.json:1500 |
| `dialogs.device_status.not_connected.title` | Attenzione | Caution | lang/it.json:1501 / lang/en.json:1501 |
| `device.properties.device_info` | Informazioni stufa | Stove info | lang/it.json:487 / lang/en.json:487 |
| `devices_list.config` | Configura Wi-Fi | Configure Navel | lang/it.json:1480 / lang/en.json:1480 |
| `device.properties.model` | Modello articolo | Article model | lang/it.json:493 / lang/en.json:493 |
| `device.properties.serial_number` | Numero di serie | Serial number | lang/it.json:497 / lang/en.json:497 |
| `device.properties.mac_address` | Indirizzo MAC | MAC address | lang/it.json:491 / lang/en.json:491 |

## Conventions worth copying

- Actions are Italian verb-imperative, not nouns: **Accendi / Spegni** (`device.status.button.*`) where EN uses the bare **ON / OFF** - the English build is much terser than the Italian throughout.
- The device is called **stufa** ("stove") in Italian, **stove** in English; the user's collection is **Le mie stufe** / **My stoves** (`devices_list.title`).
- "Set ..." is kept as a literal Italian prefix for writable registers: *Set Temperatura Ambiente*, *Set Potenza*, *Set ECO_STOP* - not *Imposta* (that verb only appears in `chronos.program.do_set_program` = "Imposta regolazioni" and `chronos.program.set_days_and_times`).
- Short vs full register labels: `full` is the settings/label text, `short` is the tile caption (`Temp. ambiente`, `Potenza`, `Vent. Ambiente`).
- Abbreviations are Italian-flavoured: **Temp.**, **Vent.**, **FW**, **Chrono**.
- Days/time use **Dalle {0} alle {1}** and **Da {0} a {1}** (`{0}`/`{1}` placeholders).
- Notable EN typos shipped in the product (reproduce only if aiming for parity): "Max room Temperature", "Night Power Set Max", "Pellets depleated." (`device.pellet_stock.empty.title`), "Allarmi" is correctly spelled but "FW rev. Rwms" is not a natural English string.

## NOT FOUND (in this bundle)

These concepts were searched for and have **no literal string** in `apk/assets/www`; do not invent them:

- **Stove state names** (accensione / spegnimento / pulizia / standby / modulazione ...): no such strings exist in the bundle. `Device.prototype.getStatusLabel` returns the register's *server-translated* value, falling back to `global.not_available` - `js/agua/Device.js:1058` (return at `js/agua/Device.js:1061`), using `register.hasTranslatedValue()` / `getFormattedValue()` from `js/agua/NativeRegister.js:46,69`. The state vocabulary comes from the cloud register payload, not from the app.
- **Alarm messages** (text of each alarm code): same mechanism. `Device.prototype.getAlarmLabel` = translated value of the `alarms` register or `global.not_available` - `js/agua/Device.js:1145` (return at `js/agua/Device.js:1150-1152`); `hasAlarm()` compares the `status` value against the enabled `alarms` values - `js/agua/Device.js:1127`. Only the register caption **Allarmi / Alarms** is local (`lang/it.json:505`).
- **Firmware update flow** (an OTA "Aggiorna firmware" screen/button): absent. "Aggiorna" in the bundle refers to the **app** update (`global.messages.upgrade_app.*`) or the **clock** (`devices_list.uclock` = "Aggiorna orologio"); only plain firmware *labels* exist (`device.properties.firmware`, `hw_*_fw_*` registers).
- **Offline device**: no dedicated "offline" device string beyond `dialogs.device_status.not_connected.*` and `common.network-offline` (the latter is about the phone's internet). `js/it.omniaweb.eventsmanager.js:29` defines the internal event `NETWORK_OFFLINE: "offline"` (identifier, not user-facing text).
- **"comfort"**: the product term is **Multicomfort** (a brand feature name, identical in IT and EN); no plain "Comfort" string.
- **"eco mode"**: only `Set ECO_STOP` / `ECO STOP` and **Energy saving AIR / HYDRO**; no string "Eco" or "Modalita Eco".
