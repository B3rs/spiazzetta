# 05 - BLE local-control protocol (Navel 2.0 / MyPiazzetta 1.9.91)

Source of truth (read-only): `docs/protocol/source-snapshot` (MyPiazzetta 1.9.91 Cordova app,
`applicationVersion` at `js/it.omniaweb.config.js:39`).

Paths below are relative to that root. Every claim cites `<file>:<line>`.

**Verdict up front:** the BLE link is *not* a binary register protocol. It is a JSON-text RPC tunnel
(named commands + named arguments) carried over one Nordic-UART-style characteristic, prefixed by one
8-byte binary "JSON" announcement and terminated by the ASCII word `Navel`. There is no CRC, no
checksum, no command-id byte and no fixed header inside the JSON payload. See `NOT FOUND` list in §8.

---

## 1. UUID table

| Role | UUID | Evidence |
|---|---|---|
| GATT service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `js/it.omniaweb.config.js:88` |
| GATT characteristic (write, read **and** notify on the same handle) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | `js/it.omniaweb.config.js:89` |
| Applied to the device object on first use | service + characteristic both set | `js/agua/Device.js:2214`, `js/agua/Device.js:2215` |
| Store of the UUID pair | `it.omniaweb.config.serviceUUID` / `it.omniaweb.config.characteristicUUID` | `js/it.omniaweb.config.js:88-89` |

Facts about the single characteristic:

- Writes go to it: `window.ble.write( deviceId, service, characteristic, … )` — `js/plugins/it.omniaweb.plugins.ble.central.js:619`.
- Reads come from the same handle: `window.ble.read( deviceId, service, characteristic, … )` — `js/plugins/it.omniaweb.plugins.ble.central.js:570`.
- Notifications are subscribed on the same handle right after connect — `js/it.omniaweb.bluetoothdevice.js:63-71`.
- The characteristic UUID `…0002` is used for reading too, i.e. the app does not use the NUS TX characteristic `…0003`;
  every response is polled with explicit GATT reads (`js/plugins/it.omniaweb.plugins.ble.central.js:801`). No read/write
  split, no CCCD is handled by the app itself.
- MTU negotiation exists as a helper (`requestMtu`, `js/plugins/it.omniaweb.plugins.ble.central.js:358-380`) but is
  never called from app code (grep: only the definition and the plugin shim
  `plugins/cordova-plugin-ble-central/www/ble.js:158` match). `writeWithoutResponse` is likewise defined
  (`js/plugins/it.omniaweb.plugins.ble.central.js:657`) and never used.

Advertising name is matched, not the service UUID: the app scans unfiltered and later resolves the peripheral
by advertised name (see §5).

---

## 2. Frame layout, with byte offsets

A logical exchange is three phases on the *same* characteristic:

### Phase A - 8-byte announcement frame ("JSON ack")

Written first, before any payload byte, as one 8-byte GATT write (`js/plugins/it.omniaweb.plugins.ble.central.js:853-867`).
Built by `getWriteAckMessage` (`js/plugins/it.omniaweb.plugins.ble.central.js:165-180`):

```
offset  size  value                                source
------  ----  -----------------------------------  --------------------------------------------
 0       1    0x4A  'J'                            js/plugins/it.omniaweb.plugins.ble.central.js:170
 1       1    0x53  'S'                            js/plugins/it.omniaweb.plugins.ble.central.js:171
 2       1    0x4F  'O'                            js/plugins/it.omniaweb.plugins.ble.central.js:172
 3       1    0x4E  'N'                            js/plugins/it.omniaweb.plugins.ble.central.js:173
 4       1    total body length, low byte         js/plugins/it.omniaweb.plugins.ble.central.js:174   (bytesLength & 0xFF)
 5       1    total body length, high byte        js/plugins/it.omniaweb.plugins.ble.central.js:175   ((bytesLength >> 8) & 0xFF)
 6       1    chunk size, low byte  (0x14 = 20)   js/plugins/it.omniaweb.plugins.ble.central.js:176   (bleMessageChunkSize & 0xFF)
 7       1    chunk size, high byte (0x00)        js/plugins/it.omniaweb.plugins.ble.central.js:177
```

- `bytesLength` is the UTF-8 byte length of the JSON body, not a character count: `length = message.byteLength` where
  `message` is the `TextEncoder` output (`js/plugins/it.omniaweb.plugins.ble.central.js:794-795`, encoder at
  `js/plugins/it.omniaweb.plugins.ble.central.js:143-146`).
- Length field is little-endian 16-bit (max 65535 bytes); chunk size field is little-endian 16-bit and is the
  configured `bleMessageChunkSize` = 20 (`js/it.omniaweb.config.js:90`).
- The 8-byte announcement carries no command, no type and no auth token; all of that is inside the JSON body.

### Phase B - JSON body, chunked

The body is UTF-8 JSON. It is sliced into `bleMessageChunkSize`-byte pieces and written sequentially, each piece
with its own GATT write, in stream order (`js/plugins/it.omniaweb.plugins.ble.central.js:822-846`):

```
stream offset   content
-------------   ----------------------------------------------------------
 0 .. 19        chunk 0   = body bytes [0, 20)         js/plugins/it.omniaweb.plugins.ble.central.js:827-828, 836
20 .. 39        chunk 1   = body bytes [20, 40)        js/plugins/it.omniaweb.plugins.ble.central.js:827-828, 836
...
n*20 .. end     last chunk = body bytes [n*20, length) js/plugins/it.omniaweb.plugins.ble.central.js:796 (Math.ceil), js/plugins/it.omniaweb.plugins.ble.central.js:827-828
```

- `chunks = Math.ceil( length / bleMessageChunkSize )` — `js/plugins/it.omniaweb.plugins.ble.central.js:796`.
- The next chunk is issued only from the write success callback of the previous one — no timer, no delay, no
  `writeWithoutResponse` (`js/plugins/it.omniaweb.plugins.ble.central.js:838-845`). See §6 and §8.
- Body length is variable (it depends on UUID strings, ids and item counts); there is no padding and no
  per-chunk header/sequence number.

### Phase C - request envelope inside the JSON body

Every request body is produced by `JSON.stringify` in `BluetoothDevice.prototype.invokeMethod`
(`js/it.omniaweb.bluetoothdevice.js:124-165`), with this fixed key order:

```
{
  "mt": <"Q" (default) | "C" (only SetConfigBuffer)>,      js/it.omniaweb.bluetoothdevice.js:155 (Q), js/it.omniaweb.bluetoothdevice.js:339 (C)
  "s":  { "ss": "Apk", "sj": <app/user UUID> },            js/it.omniaweb.bluetoothdevice.js:157-158
  "r":  { "rj": "Navel" },                                 js/it.omniaweb.bluetoothdevice.js:161
  "pl": { "Cmd": <command name>, ...command arguments }    js/it.omniaweb.bluetoothdevice.js:163
}
```

- `Cmd` is the *cloud* method name with the substring `device` removed: `action.replace( /device/g, '' )`
  (`js/agua/agua.js:499`, used by `invokeDeviceMethod`, `js/agua/agua.js:492-504`). So cloud
  `deviceRequestWriting` becomes `RequestWriting`, `deviceGetBufferReading` becomes `GetBufferReading`,
  `deviceJobStatus` becomes `JobStatus`.
- `mt` is the message type. Only three values appear in source: `"Q"` (default query, `js/it.omniaweb.bluetoothdevice.js:155`),
  `"C"` (write/configure, passed for `SetConfigBuffer`, `js/it.omniaweb.bluetoothdevice.js:339`) and `"N"` which is
  the *negative* ack the device sends back (`data.mt === 'N'` → error, `js/it.omniaweb.bluetoothdevice.js:275`;
  same check for writes, `js/agua/Device.js:2401`).
- Devices are addressed by role name, not by id: `r.rj = "Navel"` (`js/it.omniaweb.bluetoothdevice.js:161`).
- The app's own identity is `s.ss = "Apk"` plus the logged-in app UUID `s.sj`
  (`js/it.omniaweb.bluetoothdevice.js:157-158`).

### Phase D - response readout and terminator

After the last chunk is written, the app *polls reads* on the same characteristic and concatenates what it gets
(`js/plugins/it.omniaweb.plugins.ble.central.js:799-821`):

```
read #0  -> returns up to 20 bytes of response  -> response += data, read again
read #1  -> returns next block                  -> response += data, read again
...
read #k  -> decodes to exactly "Navel" (trimmed)  -> stop; JSON.parse(response)
```

- Terminator test: `it.omniaweb.utils.trim( readData ) === 'Navel'` — `js/plugins/it.omniaweb.plugins.ble.central.js:810`.
- Accumulation: `response += readData` — `js/plugins/it.omniaweb.plugins.ble.central.js:815`.
- Completion: `JSON.parse( response )` (or `{}` when empty) — `js/plugins/it.omniaweb.plugins.ble.central.js:813`.
- Response bytes are decoded as UTF-8 (`decodeMessage`, `js/plugins/it.omniaweb.plugins.ble.central.js:154-157`,
  applied at `js/plugins/it.omniaweb.plugins.ble.central.js:806-810`), so the terminator is the 5 ASCII bytes
  `4E 61 76 65 6C` in its own read.
- No length field, no checksum and no sequence number is used to delimit the response; the sentinel is the only
  delimiter (see §8).

### Summary diagram

```
                 one GATT characteristic  6e400002-b5a3-f393-e0a9-e50e24dcca9e
 WRITE 1:  [4A 53 4F 4E][len_lo][len_hi][20][00]          8 bytes, offsets 0..7
 WRITE 2:  ["{\"mt\":\"Q\",\"s\":{"]                       body bytes 0..19
 WRITE 3:  [...]                                          body bytes 20..39
 ...
 WRITE n:  [...]                                          body bytes (n-1)*20 .. end
 READ  *:  JSON text, concatenated, ended by a read equal to "Navel"
```

---

## 3. Session sequence (connect → usable)

`BluetoothDevice.prototype.connect` (`js/it.omniaweb.bluetoothdevice.js:46-112`) chains exactly this, and
`invokeMethod` re-enters `connect()` on every call, so the handshake is idempotent (`js/it.omniaweb.bluetoothdevice.js:133-135`):

1. `ble.connect( deviceId )` and store as last connected device — `js/plugins/it.omniaweb.plugins.ble.central.js:389-410`
   (`lastConnectedDevice`, `js/plugins/it.omniaweb.plugins.ble.central.js:402`), called from
   `js/it.omniaweb.bluetoothdevice.js:57`.
2. `startNotification` on the same service/characteristic; the handler only logs
   (`console.warn("NOTIFICATION: " + notification)`, `js/it.omniaweb.bluetoothdevice.js:70`). Additionally
   `js/plugins/it.omniaweb.plugins.ble.central.js:717` tests `typeof failure === 'success'`, a string comparison that is always false, so the
   notification success callback is never invoked at all (`js/plugins/it.omniaweb.plugins.ble.central.js:701-725`).
   Notifications are therefore effectively unused; all data is fetched by polling reads.
3. `Identity` command, arguments `{Id: <wifi MAC>, Security: <security_code>}`
   (`js/it.omniaweb.bluetoothdevice.js:80-87`); accessors `js/agua/Device.js:1849-1852` (MAC `_data.mac`) and
   `js/agua/Device.js:1965-1968` (`_data.security_code`). `mt` defaults to `Q`.
4. `checkConfigBufferId`: `GetBufferId` (`js/it.omniaweb.bluetoothdevice.js:270`). Accepted when
   `mt !== 'N'` (`js/it.omniaweb.bluetoothdevice.js:275`), `pl.BufferCnt > 0`
   (`js/it.omniaweb.bluetoothdevice.js:279`) and `config.defaultBufferId` (= 1, `js/it.omniaweb.config.js:87`)
   is present in `pl.Indexes` (`js/it.omniaweb.bluetoothdevice.js:283`).
5. On any of those failures, `setConfigBuffer` is sent with `mt = "C"` and payload
   `{BufferId: 1, Freq: 3, Protocol: "RWMSmaster", Items: [...], BitData: [...], Endianess: [...]}`
   (`js/it.omniaweb.bluetoothdevice.js:309-341`). `Items` are the unique register offsets of the device's
   register map, with their bit widths (8/16) and endianess letters, deduplicated
   (`js/it.omniaweb.bluetoothdevice.js:319-327`).
6. `BLUETOOTH_DEVICE_CONNECTED` is dispatched (`js/it.omniaweb.bluetoothdevice.js:101`).
7. Disconnect: `stopNotification` then `ble.disconnect` then `disconnected()`
   (`js/it.omniaweb.bluetoothdevice.js:191-226`, plugin side `js/plugins/it.omniaweb.plugins.ble.central.js:456`).

---

## 4. Register read and register write over BLE

Registers are never addressed by a BLE command of their own. They are arrays of *offset/value* pairs inside the
JSON command `GetBufferReading` (read) and `RequestWriting` (write). The offsets themselves come from the
server-side register map (`offset`, `mask`, `is_word`, `big_endian` — `js/agua/NativeRegister.js:157-160`,
`js/agua/NativeRegister.js:255-258`, `js/agua/NativeRegister.js:269-272`, `js/agua/NativeRegister.js:283-286`).

### 4.1 Read (poll everything in the configured buffer)

`Device.prototype._getReadBufferId` (`js/agua/Device.js:2557-2600`) issues cloud method
`deviceGetBufferReading` with `{id_product, id_device, BufferId: 1, registration_number}`
(`js/agua/Device.js:2568-2573`); over BLE that becomes `Cmd: "GetBufferReading"` (`js/agua/agua.js:499`).

Response handling (`js/agua/Device.js:2581-2592`): if `response.Success` → it is a cloud job, poll
`JobStatus`; otherwise `response.pl` is parsed directly. Parsing (`js/agua/Device.js:2684-2717`) requires two
arrays, `pl.Items` (offsets) and `pl.Values` (raw values), and applies `register.setOffsetValue(parseInt(Items[i]), Values[i])`
(`js/agua/Device.js:2709`). If every value equals the brand inactivity code 32768
(`js/it.omniaweb.config.js:58`, check at `js/agua/Device.js:2726-2740`) the read is treated as inactivity error.

Refresh path: `Device.refresh` → `_getReadBufferId` → parse → `signals.updated`
(`js/agua/Device.js:288-345`). In BLE mode the automatic refresh timer is disabled unless forced
(`js/agua/Device.js:208-212`), and after every write a forced refresh is run (`js/agua/Device.js:179-185`), i.e.
**one write = one write frame + one read frame**.

### 4.2 Write (one request carrying several registers)

`Register.update` → `Device.update( this.getRegisterData().getData() )` (`js/agua/Register.js:542-563`,
data object shape at `js/agua/RegisterData.js:180-189`):

```
{ Items: [offset…], Masks: [mask…], Values: [raw…], Endianess: ["L"|"B"…], BitData: [8|16…] }
```

`Device._update` (`js/agua/Device.js:2363-2413`) sends cloud method `deviceRequestWriting` with
`{id_product, id_device, Protocol: "RWMSmaster", BitData: 8, Endianess: "L", registration_number}` extended by the
data object above (`js/agua/Device.js:2374-2387`). Over BLE: `Cmd: "RequestWriting"`, `mt` = `Q`.

Because the payload is merged with `$.extend( {}, {…}, data )` (`js/agua/Device.js:2375-2387`), the scalar
`"BitData": 8` and `"Endianess": "L"` are **overwritten** by the arrays `BitData:[8|16…]` and
`Endianess:["L"|"B"…]` when a register data object is present. A real write frame therefore carries the arrays,
not the scalar defaults; keep `Protocol: "RWMSmaster"` as sent.

Response: over BLE there is no job id — `response.Success` is absent, so the code takes the
`response.mt && response.mt !== 'N'` branch and resolves with `response.pl` (`js/agua/Device.js:2396-2407`),
which is then parsed by the same `Items`/`Values` parser (`js/agua/Device.js:2684-2717`). Any `mt === "N"` is a
rejection (`js/agua/Device.js:2405`).

Register values are raw integers; display conversion is app-side only (`formula`/`formula_inverse`, masks —
`js/agua/NativeRegister.js:85-146`). A write must send the raw value for the register's bit width
(`is_word ? 16 : 8`, `js/agua/NativeRegister.js:285`) and endianess letter `big_endian ? 'B' : 'L'`
(`js/agua/NativeRegister.js:271`).

---

## 5. Scan / pairing flow and the `T009_` name

1. User switches the app to Bluetooth mode; it is a *local* flag, `localStorage` key `bluetooth`
   (`js/it.omniaweb.logic.js:1643-1646` read, `js/it.omniaweb.logic.js:1703-1708` write). It is a settings-level
   "connection type" toggle (`js/it.omniaweb.logic.js:2335-2351`), enabled for this brand by
   `showWifiBleSwitch: true` (`js/it.omniaweb.config.js:64`).
2. Bluetooth may be used only if permissions allow scanning **and** location is on:
   `canScanBluetoothDevices()` returns `bluetoothAvailable && bluetoothEnabled && locationAvailable && locationEnabled`
   (`js/plugins/it.omniaweb.plugins.diagnostics.js:558-561`; gate at `js/it.omniaweb.logic.js:1653-1656`).
3. The BLE commissioning screen turns Bluetooth mode on and starts an **unfiltered** scan — no service UUID is
   passed, so `services` becomes `[]` and every peripheral is reported
   (`js/it.omniaweb.views.js:2548-2570`; `js/plugins/it.omniaweb.plugins.ble.central.js:257-272`;
   `js/plugins/it.omniaweb.plugins.ble.central.js:276-291` with `{reportDuplicates:false}` at
   `js/plugins/it.omniaweb.plugins.ble.central.js:264`). Default scan window is 20 s
   (`js/plugins/it.omniaweb.plugins.ble.central.js:14`); the screen re-checks every 5 s while scanning
   (`js/it.omniaweb.views.js:2555-2557`).
4. Discovery is keyed on the **advertised name**: `getDeviceByName( this.getBroadcastName() )`
   (`js/agua/Device.js:2203-2219`, helper `js/plugins/it.omniaweb.plugins.ble.central.js:99`).
5. `T009_` naming: the broadcast name is `wifiPrefix + last <ssidDigits> characters of the WiFi MAC`
   (`js/agua/Device.js:1895-1898`), with `wifiPrefix = "T009_"` (`js/it.omniaweb.config.js:56`) and
   `ssidDigits = 6` (`js/it.omniaweb.config.js:57`). The same value is the device's WiFi SSID
   (`getWifiSSID`, `js/agua/Device.js:1881-1884`) and is what the app shows to the user
   (`js/it.omniaweb.views.js:1873`, `js/it.omniaweb.views.js:1928-1929`). So the BLE advertised name and the
   stove's own `T009_xxxxxx` access-point SSID are constructed identically; only the trailing 6 MAC nibbles are
   used.
   A separate helper computes the Bluetooth MAC as WiFi MAC + 2 (`js/agua/Device.js:1863-1870`) but is not used
   for matching — matching is by name only.
6. Wi-Fi provisioning over BLE (this is the real commissioning work):
   - `StatusWifiStation` with no arguments (`js/it.omniaweb.bluetoothdevice.js:352-364`); response fields used:
     `pl.ConnSta` in `Disconnected|Connected|Connecting|Disconnecting|Connecting_Fail` and `pl.Aps[]`
     (`js/it.omniaweb.views.js:2587-2604`).
   - `SetWifiStation {Activation:"Connect", Id:1, ssid, password}` with a **10 s** app-side timeout
     (`js/it.omniaweb.bluetoothdevice.js:394-400`; timeout mechanics `js/it.omniaweb.bluetoothdevice.js:136-145`).
   - `SetWifiStation {Activation:"Disconnect"}` (`js/it.omniaweb.bluetoothdevice.js:372-383`).
   - On completion the app leaves Bluetooth mode (`js/it.omniaweb.views.js:2655-2660`).

So the "pairing" the user sees is: switch to BLE, app scans, app matches `T009_` name, `Identity` handshake,
buffer config, then `StatusWifiStation`/`SetWifiStation` to attach the stove to the home Wi-Fi. There is no BLE
bonding/pairing key exchange in the app and no LESC/passkey code anywhere in these files.

---

## 6. Chunking rules

- Chunk size is the constant 20 bytes (`"bleMessageChunkSize": 20`, `js/it.omniaweb.config.js:90`), also
  announced in the 8-byte prelude at offsets 6..7 (`js/plugins/it.omniaweb.plugins.ble.central.js:176-177`).
- Number of chunks: `Math.ceil( byteLength / 20 )` (`js/plugins/it.omniaweb.plugins.ble.central.js:796`).
- Chunk boundaries: `start = counter * 20`, `until = start + 20`, `message.slice(start, until)`
  (`js/plugins/it.omniaweb.plugins.ble.central.js:827-836`).
- Each chunk is a separate confirmed GATT write (`window.ble.write`, `js/plugins/it.omniaweb.plugins.ble.central.js:619`).
- **No inter-chunk delay exists in the app.** The next chunk is dispatched from the previous write's success
  callback with no `setTimeout` and no sleep (`js/plugins/it.omniaweb.plugins.ble.central.js:838-846`). Ordering is
  therefore guaranteed by the GATT write-with-response handshake plus JS async ordering only.
- Each chunk is sent as raw UTF-8 bytes (`data.buffer` when `convert === false`,
  `js/plugins/it.omniaweb.plugins.ble.central.js:624`, called with `false` at
  `js/plugins/it.omniaweb.plugins.ble.central.js:837`). Multi-byte characters may therefore be split across
  chunks; the receiver must concatenate before decoding.
- Response readout is not chunked by the app: reads are repeated until the `Navel` sentinel (§2 phase D).

---

## 7. Example frames

Values in angle brackets are placeholders (ids/UUID/offsets are runtime data). Field names, order and
structure are exactly as the app builds them. Byte counts and chunk splits are derived arithmetic from
`js/plugins/it.omniaweb.plugins.ble.central.js:165-180` and `js/plugins/it.omniaweb.plugins.ble.central.js:796`.

### 7.1 Read-all (`GetBufferReading`)

Request direction (app → stove). Body, 209 UTF-8 bytes:

```json
{"mt":"Q","s":{"ss":"Apk","sj":"a1b2c3d4-0000-0000-0000-000000000000"},"r":{"rj":"Navel"},"pl":{"Cmd":"GetBufferReading","id_product":123,"id_device":456,"BufferId":1,"registration_number":"0000000000000000"}}
```

Prelude (8 bytes, written first):

```
4A 53 4F 4E  D1 00  14 00
'J' 'S' 'O' 'N'   len=0x00D1=209    chunk=0x0014=20
```

Body stream: 11 chunks (`ceil(209/20)`): ten chunks of 20 bytes plus a 9-byte tail, e.g.

```
chunk 0  {"mt":"Q","s":{"ss":          bytes  0..19
chunk 1  "Apk","sj":"a1b2c3d4          bytes 20..39
chunk 2  -0000-0000-0000-0000          bytes 40..59
...
chunk 10 000000"}}                     bytes 200..208   (9 bytes)
```

Response (stove → app): JSON text read on the same characteristic, terminated by a read equal to `Navel`; parsed
into `pl.Items` / `pl.Values` (4.1). Field names are the ones consumed at `js/agua/Device.js:2709`.

### 7.2 Write a register (`RequestWriting`)

There is no write-by-register-id command: you read the current values, change the one you want, and send the
whole item set (or at minimum one item). Single-item body, 292 UTF-8 bytes:

```json
{"mt":"Q","s":{"ss":"Apk","sj":"a1b2c3d4-0000-0000-0000-000000000000"},"r":{"rj":"Navel"},"pl":{"Cmd":"RequestWriting","id_product":123,"id_device":456,"Protocol":"RWMSmaster","registration_number":"0000000000000000","Items":[<offset>],"Masks":[<mask>],"Values":[<raw>],"Endianess":["L"],"BitData":[8]}}
```

Prelude:

```
4A 53 4F 4E  24 01  14 00
'J' 'S' 'O' 'N'   len=0x0124=292    chunk=0x0014=20
```

Body stream: 15 chunks (`ceil(292/20)`): fourteen chunks of 20 bytes plus a 12-byte tail.

Note the placeholder-only parts: `<offset>` (from register map, `reg.offset`), `<mask>`, `<raw>` (the value after
the register's inverse formula), `"Endianess":["L"]` or `["B"]`, `"BitData":[8]` or `[16]`
(`js/agua/RegisterData.js:180-189`).

### 7.3 Write fan speed — **not derivable from source**

There is no fan/ventilation register in the app bundle: no register key containing `fan`, `vent`, `velocit` or
`aria` exists anywhere under `js/` (grep over `js/` returns nothing), and the register catalogue is fetched from
the cloud (`deviceGetRegistersMap`, `js/agua/Device.js:2496-2545`) and cached per device
(`js/agua/Device.js:2462-2490`). So the fan-speed register key, its offset, bit width and allowed range are
**NOT FOUND** in this source.

What *is* derivable is the shape: a fan-speed write is a `RequestWriting` frame as in §7.2 whose
`Items`/`Masks`/`Values`/`Endianess`/`BitData` correspond to the fan-speed register entry of the cached register
map. To produce it, read the register map JSON cached at
`<dataDirectory>/<MAC without colons>.json` (`js/agua/Device.js:2473`, saved at `js/agua/Device.js:2759`) and pick
the fan-speed entry from there. Do not invent an offset.

---

## 8. NOT FOUND

Items the brief asked for that provably do not exist in this source tree:

1. **CRC16 / checksum / parity** — no `crc`, `CRC`, `checksum` or `Checksum` occurrence anywhere under `js/`
   (grep returns nothing). Integrity relies on GATT write-with-response plus UTF-8 JSON validity
   (`js/plugins/it.omniaweb.plugins.ble.central.js:813`).
2. **Command-id bytes / opcode field** — commands are the string `pl.Cmd` (`js/it.omniaweb.bluetoothdevice.js:163`)
   with no numeric id, and no id is ever read on responses.
3. **Fixed binary register frame with header bytes** — no such builder exists; the only binary bytes in the whole
   protocol are the 8 prelude bytes (§2 phase A) and the ASCII terminator `Navel`
   (`js/plugins/it.omniaweb.plugins.ble.central.js:810`).
4. **Inter-chunk delay** — none; sequential write callbacks only (`js/plugins/it.omniaweb.plugins.ble.central.js:838-846`).
5. **Chunk size other than 20 / MTU negotiation** — 20 is the only value (`js/it.omniaweb.config.js:90`);
   `requestMtu` exists but is never called (§1).
6. **Notify-characteristic stream for responses** — notifications are subscribed
   (`js/it.omniaweb.bluetoothdevice.js:63-71`) but the success callback is dead code
   (`js/plugins/it.omniaweb.plugins.ble.central.js:717`); responses are polled reads.
7. **Fan-speed register** (key, offset, range) — see §7.3.
8. **Meaning/units of `Freq: 3`** in `SetConfigBuffer` (`js/it.omniaweb.bluetoothdevice.js:333`) — not documented in code.
9. **Semantics of `Identity` failure modes / security code format** — only the field names are visible
   (`js/it.omniaweb.bluetoothdevice.js:80-87`, `js/agua/Device.js:1965-1968`).
10. **Stove-side mode requirement to be connectable** — the app never sets a stove mode before BLE work; it only
    requires the local Bluetooth toggle, scan+location permissions and a matching advertised name (§5). Any
    physical/standby prerequisite lives in the firmware or in remote language labels (e.g.
    `device.connection.bluetooth.pairing.*`, referenced at `js/it.omniaweb.widgets.js:2493-2521`) which are not
    bundled in `www`.
11. **BLE bonding/pairing keys, LESC, passkey** — absent (§5).
12. **Read/write split characteristic (NUS TX `…0003`)** — not used; all traffic on `…0002` (§1).

---

## 9. Practical notes for an offline control path

- You only need one GATT link: write `6e400002-…`, then read `6e400002-…` repeatedly until `Navel`.
- Minimum viable session: prelude+`Identity` → `GetBufferId` (or `SetConfigBuffer` with `mt:"C"`) →
  `GetBufferReading` / `RequestWriting`.
- Offline caveats found in the code:
  - The register map is *not* fetched when Bluetooth mode is on: `_getRemoteRegistersMap` returns immediately
    (`js/agua/Device.js:2502-2506`). Without a cached `<MAC>.json` map (`js/agua/Device.js:2473`) there are no
    offsets to read or write.
  - `Identity` needs `_data.mac` and `_data.security_code`, which come from the cloud device record
    (`js/agua/Device.js:1849-1852`, `js/agua/Device.js:1965-1968`); they must be cached too.
  - In BLE mode automatic polling is suppressed (`js/agua/Device.js:208-212`), and every write triggers a forced
    refresh (`js/agua/Device.js:179-185`) — an offline client should expect one read frame per write frame.
  - `mt === "N"` is the only error signal (`js/it.omniaweb.bluetoothdevice.js:275`, `js/agua/Device.js:2401`);
    all values equal to 32768 mean "inactivity" (`js/it.omniaweb.config.js:58`,
    `js/agua/Device.js:2726-2740`).
