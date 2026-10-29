# MyPiazzetta 1.9.91: protocol spec for a Kotlin client

This document is the single implementation reference for talking to a MyPiazzetta stove through the
Piazzetta Agua cloud, plus the optional Bluetooth LE path. It consolidates reports 01 to 06, and every
claim in those reports that failed the adversarial review in `VERIFY.md` has been dropped or replaced
with the corrected statement.

Everything below comes from the shipped, unminified production JavaScript of the MyPiazzetta 1.9.91
Cordova app. Nothing was tested against the live cloud or against a stove. The bundle is the only
source, and where it is silent this document says `NOT FOUND` instead of filling the gap.

**Citation root (read only):** `/tmp/ulw-piazzetta-aVhwh3/apk/assets/www`
Every citation is `<path>:<line>` relative to that root. App version `1.9.91`
(`js/it.omniaweb.config.js:39`), brand block `js/it.omniaweb.config.js:41-68`.

## Markers

| Marker | Meaning |
|---|---|
| `NOT FOUND` | The bundle does not contain the fact. It lives on the server, in firmware, or nowhere. Do not guess it. |
| `UNVERIFIED` | Stated but not established by the source (live-server behaviour, inferred language semantics, or a claim that failed review). |
| `RECOMMENDED` | A design choice for the Kotlin client. This is not app behaviour and has no source citation. |

## Corrections carried over from VERIFY.md

These are the twelve claims that failed review. Each row gives what the input reports said and what this
spec says instead, with the corrected source line. Nothing in the rows below is repeated as fact.

| # | Report claim (not used here) | What this spec says instead | Citation |
|---|---|---|---|
| F1 | `deviceJobStatus` is a POST with plain `url: deviceJobStatus` | It is a GET, and the `url` header is `deviceJobStatus/<idRequest>` | `js/agua/Device.js:2625-2632` |
| F2 | The `affix` argument is used in exactly one place (`productList`) | Two places: `productList` sends `'/' + code`, `deviceJobStatus` sends `'/' + idRequest` | `js/agua/agua.js:333`; `js/agua/Device.js:2631` |
| F3 | The reachability probe GETs `.../api/bridge/endpoint//https://.../firmware/agua.html` | A brand override replaces the endpoint with the bare firmware URL, so the probe is `https://piazzetta-iot.app2cloud.it/firmware/agua.html?t=<random>`; the earlier `host + '/' + endpoint` line is dead | `js/plugins/it.omniaweb.plugins.network.information.js:163-166`, `:60`, dead line `:121`; `js/it.omniaweb.config.js:85` |
| F4 | Aggregate helpers add both endpoints "unconditionally" | Each endpoint is added only after a null check | `js/agua/registers/AirEnergySavingRegister.js:208-217` |
| F5 | `power_auto`, `power_night`, `eco_stop` behaviour is evidenced at `js/agua/Device.js:1362-1383` | That range is `natural_mode_new` only. The three keys occur in no `.js` file (`grep` returns 0 hits); they exist only as label keys, so their behaviour is `NOT FOUND` | `js/agua/Device.js:1362-1383`; labels at `lang/it.json:1000-1002`, `1112-1114`, `1140-1142` |
| F6 | Quality range raw `0..10` maps to display `-5..+5` | No raw bound exists in the bundle. The class defines only `MIDDLE_VALUE = 5` and shifts by it; `set_min` / `set_max` come from the server map. Any numeric range is `UNVERIFIED` | `js/agua/registers/QualityRangeRegister.js:29`, `:49`, `:63`, `:77` |
| F7 | "41 hits" for base64/atob/btoa outside `js/` | Measured: 63 hits in 23 files under `www`, 0 under `js/` | measurement: `grep -rn -i -e base64 -e atob -e btoa .` at the www root; example `plugins/cordova-plugin-ble-central/www/ble.js:27-33` |
| F8 | `js/agua/registers/QualityRangeRegister.js:136` is where `raw = display + 5` | The `value + MIDDLE_VALUE` write is line 135; 136 is the closing `return this;` | `js/agua/registers/QualityRangeRegister.js:133-137` |
| F9 | `getWarnings()` produces `worktime` and `pellet` | It produces only `voltage_current`, `voltage_restart`, `auger_load`. `pellet` and `worktime` come from `getPelletStockStatus()` | `js/agua/Device.js:1087-1116` versus `:1248-1298` |
| F10 | A `RequestWriting` body is 292 bytes but the printed example measures 303 | Body length is whatever the UTF-8 encoding of the actual JSON measures. The worked example in section 8 is computed and states 292 bytes for that exact string | `js/plugins/it.omniaweb.plugins.ble.central.js:794-796` |
| F11 | `login.subscribe` is at `lang/it.json:1601` | It is at 1611. Line 1601 is `login.failure.subscribe`, which carries the same Italian text | `lang/it.json:1601`, `:1611` |
| F12 | `login.subscribe` is at `lang/en.json:1601` | It is at 1611 | `lang/en.json:1601`, `:1611` |

---

## 1. Overview and cloud architecture

### 1.1 The shape of the system

```
Android app (Kotlin client)        Cloud bridge                  Stove
  HTTPS POST/GET  -------------->  piazzetta-iot.app2cloud.it  -->  Wi-Fi module ("Navel")
  one URL, action in headers        /api/bridge/endpoint/            --> register bus ("RWMSmaster")
```

The cloud host is `https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/`
(`js/it.omniaweb.config.js:48`). Brand values that every request carries: `slug` `mypiazzetta`, `brand`
`piazzetta`, `clientId` `mypiazzetta`, `id` `1`, `code` `458632`
(`js/it.omniaweb.config.js:43-47`). `clientId` is not an auth credential: it appears only in consent and
announcement payloads (`js/it.omniaweb.logic.js:607`, `:3684`, `:4132`).

Two protocol tokens travel in payloads and are never interpreted by the app: `Protocol: "RWMSmaster"` on
writes (`js/agua/Device.js:2381`) and `BufferId: 1` on reads
(`js/agua/Device.js:2572`, `js/it.omniaweb.config.js:87`).

### 1.2 One URL, action in headers

The whole cloud API is one HTTP endpoint. The operation name is not in the path; it rides in a request
header, and the HTTP verb rides in another:

```js
url: it.omniaweb.config.currentBrand.host          // js/agua/agua.js:941
...
jqXHR.setRequestHeader( 'method', method );        // js/agua/agua.js:955
jqXHR.setRequestHeader( 'url', action + urlaffix ) // js/agua/agua.js:956
```

An older implementation in the same file built `url + action + affix` as the request URL
(`js/agua/agua.js:517-546`). It is dead: a brand override reassigns `instance.invokeRemoteMethod`
(`js/agua/agua.js:915`) before the singleton is published (`js/agua/agua.js:1079`). Reproduce the
override, not the dead code.

Only two actions carry a path-like affix, and both are visible in source: `productList` sends
`'/' + code`, giving `url: productList/458632` (`js/agua/agua.js:333`), and `deviceJobStatus` sends
`'/' + idRequest` (`js/agua/Device.js:2631`).

### 1.3 Transport rules

| Rule | Value | Citation |
|---|---|---|
| Verbs | literal `GET`, `POST`, `PUT`, `DELETE` | `js/agua/agua.js:32` |
| Content type | `application/json; charset=utf-8` | `js/agua/agua.js:945` |
| Body | `JSON.stringify(payload)` for non-GET, `null` for GET | `js/agua/agua.js:946` |
| Response type | `json`, `crossDomain: true`, async | `js/agua/agua.js:943`, `:947` |
| Client timeout | none. No `timeout:` option exists in the ajax block | `js/agua/agua.js:938-962` |
| Language | `language` is merged into every non-GET payload | `js/agua/agua.js:922-925` |
| Port | unused in the live path | `js/agua/agua.js:523-524` |

### 1.4 Request headers

Built by the brand override of `getRemoteMethodInvocationHeaders` (`js/agua/agua.js:891-903`) and applied
in `beforeSend` (`js/agua/agua.js:948-956`):

| Header | Value | Citation |
|---|---|---|
| `CustomerCode` | `458632` | `js/agua/agua.js:894` |
| `IdBrand` | `1` | `js/agua/agua.js:895` |
| `ApplicationVersion` | `1.9.91` | `js/agua/agua.js:896` |
| `authorization` | access token, or the installation UUID when no token exists | `js/agua/agua.js:897`, `js/agua/User.js:125-135` |
| `local` | `true` when no token exists, else `false` | `js/agua/agua.js:898`, `js/agua/User.js:77-83` |
| `brand` | `piazzetta` | `js/agua/agua.js:899` |
| `aguaid` | `User.id` or `null` | `js/agua/agua.js:900`, `js/agua/User.js:878-885` |
| `userid` | `User.agua_id` or `null` | `js/agua/agua.js:901`, `js/agua/User.js:866-871` |
| `method` | the HTTP verb | `js/agua/agua.js:955` |
| `url` | action name, optionally with affix | `js/agua/agua.js:956` |

The `authorization` value is the raw token with no scheme prefix: `getAuthorizationCode()` returns
`this.token` directly, or the installation UUID when local (`js/agua/User.js:125-135`). Pre-login, `aguaid`
and `userid` are `null` (`js/agua/User.js:870`, `:884`).

### 1.5 Response envelope

| Case | Client behaviour | Citation |
|---|---|---|
| HTTP 2xx and `data.Success` truthy | resolve with the whole body | `js/agua/agua.js:973-975` |
| HTTP 2xx and `Success` falsy | reject with `Error`, else `Info`, else `false` | `js/agua/agua.js:983` |
| Response header `Blocked-Content` | read on every success, comma-split into a list | `js/agua/agua.js:971-972` |
| Transport error with `responseJSON.Error` | `1116` clears session and restarts, `1106` refreshes the token and replays, `1144` re-activates and replays, anything else rejects with the raw code | `js/agua/agua.js:994-1060` |
| Transport error without a JSON `Error` | reject `"0000"` | `js/agua/agua.js:1062-1065` |

Two more codes exist only in the UI layer: `1134` means "logged in but the account is not activated", which
shows the activation form (`js/it.omniaweb.logic.js:3297-3299`), and `INVALID_APP_ERROR` from
`getAppConfiguration` means the client is too old (`js/it.omniaweb.logic.js:221-224`). The server's mapping
of codes to HTTP statuses is `NOT FOUND`, because the client never reads `jqXHR.status`
(`js/agua/agua.js:986-1066`).

### 1.6 Connectivity probe (not part of the API)

The app pings the internet with a plain GET. A brand override sets the probe endpoint to the bare firmware
URL (`js/plugins/it.omniaweb.plugins.network.information.js:163-166`), which is
`https://piazzetta-iot.app2cloud.it/firmware/agua.html` (`js/it.omniaweb.config.js:85`), and appends a
random `?t=` cache buster (`js/plugins/it.omniaweb.plugins.network.information.js:60`). The earlier line
that concatenated `host + '/' + endpoint` (`:121`) is dead because the override runs later. Treat this as a
reachability check only; it carries no protocol meaning.

### 1.7 Cloud versus BLE dispatch

`invokeDeviceMethod` (`js/agua/agua.js:492-504`) routes to Bluetooth when the local Bluetooth mode flag is
on, and to `invokeRemoteMethod` otherwise. In BLE mode the cloud method name loses its `device` prefix:
`action.replace( /device/g, '' )` (`js/agua/agua.js:499`). Sections 2 to 7 describe the cloud path. Section
8 describes BLE.

---

## 2. Authentication and session

### 2.1 Call sequence

| Step | Action | Details | Citation |
|---|---|---|---|
| 1 | app start | `checkBleStatus`, `activateApplication`, config | `js/it.omniaweb.logic.js:174-199` |
| 2 | `appSignup` (once) | registers the installation, stores the returned `id_app` as `agua.uuid` | `js/it.omniaweb.logic.js:274-282`, `js/agua/agua.js:729-733` |
| 3 | login gate | `agua.logged` flag, else a `userDetail` probe | `js/agua/User.js:146-160` |
| 4 | `userLogin` | email and password, plaintext | `js/agua/User.js:440-448` |
| 5 | persist | `agua.token`, `agua.refreshToken`, `agua.logged` | `js/agua/User.js:454-458` |
| 6 | `userDetail` (GET) | loads `User.id`, `User.agua_id`, `User.email` | `js/agua/User.js:171-203`, `:866-885` |
| 7 | `deviceList` | see section 3 | `js/agua/User.js:230-241` |

### 2.2 Login

Request, with a full header set:

```
POST https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/
Content-Type: application/json; charset=utf-8
CustomerCode: 458632
IdBrand: 1
ApplicationVersion: 1.9.91
brand: piazzetta
authorization: <installation uuid>
local: true
aguaid: null
userid: null
method: POST
url: userLogin

{"email":"user@example.com","password":"plaintext-password","language":"it"}
```

Body source `js/agua/User.js:445-446`, injected `language` `js/agua/agua.js:922-925`, headers
`js/agua/agua.js:891-903` and `:955-956`.

Response: the client reads `token` and `refresh_token` beyond `Success`
(`js/agua/User.js:454-458`). If `token` is absent the promise resolves with the raw response and no
session starts (`js/agua/User.js:462`).

### 2.3 User detail

`GET`, `url: userDetail`, no body (`js/agua/User.js:182-193`). The client reads only `User.id`
(`js/agua/User.js:882-885`), `User.agua_id` (`js/agua/User.js:866-871`) and `User.email`
(`js/agua/User.js:796`). Those two ids feed the next request's `aguaid` and `userid` headers, so the first
`userDetail` after login still sends `null` for both.

### 2.4 Session storage

There is no cookie and no client-side crypto. Session state lives in `localStorage` under `agua.token`,
`agua.refreshToken`, `agua.logged`, `agua.user` (`js/agua/User.js:456-458`, `:698-710`), plus `agua.uuid`
(`js/agua/User.js:112`) and `agua.activated`. Reads and writes go through `JSON.parse` /
`JSON.stringify` (`js/it.omniaweb.utils.js:66-88`).

The password is sent as typed: no hashing, signing, HMAC or API key exists anywhere in `js/`
(`js/agua/User.js:446`, source of the value `js/it.omniaweb.logic.js:3288`). Transport protection is HTTPS
only (`js/it.omniaweb.config.js:48`). A grep for `cookie|withCredentials` over `js/` returns nothing.

### 2.5 Token refresh

```json
{"refresh_token":"<refresh token>","language":"it"}
```

`js/agua/User.js:556-561` plus the injected `language` (`js/agua/agua.js:922-925`). The response is read
for `token` only (`js/agua/User.js:566-569`); the refresh token is never updated from the response, so
whether the server rotates it is `NOT FOUND`.

Any `1106` from a remote session triggers `refreshUserToken()` and a replay of the original call
(`js/agua/agua.js:1002-1030`). The replay is recursive with no attempt counter, so a permanent `1106` loops
as long as refresh keeps succeeding (`js/agua/agua.js:1013-1019`). If refresh fails, the session is cleared
and the original error rejects.

### 2.6 Logout

`userLogout` with `{refresh_token, language}` (`js/agua/User.js:596-602`). Local state is cleared on both
the success and the failure path (`js/agua/User.js:609-619`).

### 2.7 Installation signup

```json
{
  "language": "it",
  "id_app": "<uuid>",
  "push_notification_token": "",
  "push_notification_active": true,
  "phone_id": "<device uuid>",
  "phone_type": "android",
  "phone_version": "<os version>"
}
```

`js/agua/agua.js:704-719`. The response's `id_app` becomes `agua.uuid` (`js/agua/agua.js:729-733`). It runs
at startup when `agua.activated` is empty (`js/it.omniaweb.logic.js:274-282`) and again on error `1144`
(`js/agua/agua.js:1032-1056`).

### 2.8 Session notes for Kotlin

- Token format and expiry are `NOT FOUND`: the token is never decoded. Expiry is discovered only by getting
  `1106` (`js/agua/agua.js:1002`).
- `local: true` plus `authorization: <uuid>` is what the app sends before login. Whether the server accepts
  that state for device calls is `UNVERIFIED`.
- Keep header names exactly as listed. HTTP is case-insensitive, but parity costs nothing.

---

## 3. Device listing and status read

### 3.1 `deviceList`

`POST`, `url: deviceList`, `authorization: <token>`, `local: false`, `aguaid` and `userid` set
(`js/agua/User.js:230-235`). The call passes a `null` payload and the transport merges `language`
(`js/agua/User.js:230-234`, `js/agua/agua.js:922-925`). The exact body that jQuery's `$.extend(null, {language})`
produces is `UNVERIFIED`; send `{"language":"it"}`.

Response: `response.device` is an array (`js/agua/User.js:241`). Fields the client actually reads:

| JSON field | Reader | Note |
|---|---|---|
| `id` | `js/agua/Device.js:1778` | compared with `===` against a DOM attribute, so it is a string in practice (`js/agua/User.js:328`) |
| `id_client` | `js/agua/Device.js:1792` | |
| `id_device` | `js/agua/Device.js:1806` | required by every device call |
| `id_product` | `js/agua/Device.js:1820` | required by every device call |
| `mac` | `js/agua/Device.js:1851` | used for the register-map cache key and the BLE name |
| `product_serial` | `js/agua/Device.js:1911` | equals `registration_number` |
| `reg_code` | `js/agua/Device.js:1925` | |
| `name` | `js/agua/Device.js:1981` | list is sorted by it client-side (`js/agua/User.js:241-254`) |
| `is_managed` | `js/agua/Device.js:2843` | |
| `security_code` | `js/agua/Device.js:1967` | needed for the BLE `Identity` handshake |
| `product_info.registration_number` | `js/it.omniaweb.logic.js:3897` | |
| `product_info.is_warranty_covered` | `js/agua/Device.js:105` | |

Other device fields exist on the model but aren't needed for control: `description`
(`js/agua/Device.js:1995`), `name_product` (`:1953`), `cod_art` (`:1939`), `country` (`:2009`), `state`
(`:2023`), `city` (`:2037`), `creation_date` (`:2065`), `update_date` (`:2079`), `autorization_it` (`:2093`),
`is_online` (`:2160`). `registration_number` is literally the serial number (`js/agua/Device.js:2827-2830`).

Firmware is not a device field. It is rebuilt from registers `fw_1..fw_6` as `FW-xV-y-z-w`
(`js/agua/Device.js:1166-1188`).

### 3.2 Register map

```
POST  url: deviceGetRegistersMap
{"id_product":"42","id_device":"abcd","last_update":null,"registration_number":"P123456","language":"it"}
```

Payload `js/agua/Device.js:2513-2521`, injected language `js/agua/agua.js:922-925`. Response shape:
`{Success:true, device_registers_map:{registers_map:[...]}}` (`js/agua/Device.js:2530-2535`).

`device_registers_map: null` means "unchanged since `last_update`": keep the cached map
(`js/agua/Device.js:2532-2535`). The app caches the map per device as `<dataDirectory>/<MAC without
colons>.json` (`js/agua/Device.js:2462-2490`, `:2753-2774`). A Kotlin client should persist it the same way:
it is the only source of offsets and masks.

`last_update` is passed by the caller; the client sends whatever it holds, `null` on first fetch
(`js/agua/Device.js:2516-2521`).

### 3.3 Buffer read

```
POST  url: deviceGetBufferReading
{"id_product":"42","id_device":"abcd","BufferId":1,"registration_number":"P123456","language":"it"}
```

Payload `js/agua/Device.js:2565-2578`, `BufferId` from `js/it.omniaweb.config.js:87`.

Two response shapes, both handled at `js/agua/Device.js:2581-2592`:

- `Success` with `idRequest`: a job ticket. Poll `deviceJobStatus/<idRequest>` (section 6).
- `mt` present and not `'N'`: the answer is immediate, in `pl`.
- `mt === 'N'`: NACK, reject (`js/agua/Device.js:2592`; NACK semantics at `js/it.omniaweb.bluetoothdevice.js:275`).

The answer is `{Items:[offset...], Values:[raw...]}`. `_parseRegistersValues` applies it by offset into the
cached map (`js/agua/Device.js:2684-2714`), and every register whose `offset` matches receives the raw value
(`js/agua/Register.js:182-193`).

If every value equals the brand `inactivityCode` `32768` (`js/it.omniaweb.config.js:58`), the read is an
inactivity error (`js/agua/Device.js:2699-2701`, check at `:2726-2741`). The UI turns that into the
"communication between stove and module could not be established" view
(`js/it.omniaweb.logic.js:2089-2091`, `:2128-2131`). Empty `Values` is a plain `false`
(`js/agua/Device.js:2695-2698`).

### 3.4 Refresh cadence

| Constant | Value | Citation |
|---|---|---|
| `refreshDeviceInterval` | 3000 ms (default, server-overridable) | `js/it.omniaweb.widgets.js:518`, `js/it.omniaweb.logic.js:1852` |
| `statusPollingFrequency` | 300 ms (default, server-overridable) | `js/agua/Device.js:2649` |
| `checkInternetConnection.dataInterval` | 10000 ms, connectivity only | `js/it.omniaweb.config.js:83`, `js/plugins/it.omniaweb.plugins.network.information.js:109` |

Closing the settings dialog restarts auto-refresh with `refreshDeviceInterval`
(`js/it.omniaweb.logic.js:1849-1853`). In Bluetooth mode the automatic refresh timer is suppressed
(`js/agua/Device.js:208-212`).

---

## 4. Register model and encoding

### 4.1 The one structural fact

**The APK contains no register addresses.** `offset`, `mask`, `big_endian`, `is_word`, `formula`,
`formula_inverse`, `set_min`, `set_max`, `step`, `enc_val` and `enable_val` are server data delivered by
`deviceGetRegistersMap` and cached per device. The app is a generic engine over that map
(`js/agua/NativeRegister.js:157-160`, `:213-244`, `:255-286`, `:366-378`, `:497-519`).

Consequence for the Kotlin client: a hardcoded table of offsets will diverge on every firmware or model
variant. Load the map, persist it, drive everything from it.

### 4.2 Map record fields

| Server field | Accessor | Meaning | Citation |
|---|---|---|---|
| `offset` | `getOffset()` | address sent in `Items[]` | `js/agua/NativeRegister.js:157-160` |
| `mask` | `getMask()` | bit mask, applied on read only | `js/agua/NativeRegister.js:255-258` |
| `big_endian` | `getEndianess()` | `'B'` or `'L'` token | `js/agua/NativeRegister.js:269-272` |
| `is_word` | `getBitData()` | `16` or `8` | `js/agua/NativeRegister.js:283-286` |
| `set_min` | `getMinimumValue()` | slider minimum | `js/agua/NativeRegister.js:213-216` |
| `set_max` | `getMaximumValue()` | slider maximum | `js/agua/NativeRegister.js:227-230` |
| `step` | `getStep()` | slider step | `js/agua/NativeRegister.js:241-244` |
| `is_hex` | `isHex()` | value formatted in hex | `js/agua/NativeRegister.js:464-467` |
| `is_temperature` | `isTemperature()` | apply the display-unit formula | `js/agua/NativeRegister.js:311-314` |
| `formula` | `getEvaluatedValue()` | raw to engineering value | `js/agua/NativeRegister.js:85-88` |
| `formula_inverse` | `setValue()` | engineering value to raw | `js/agua/NativeRegister.js:135-146` |
| `reg_key` | `getKey()` | textual key, e.g. `temp_air` | `js/agua/NativeRegister.js:171-174` |
| `reg_key_group` | `getGroup()` | group key used for control grouping | `js/agua/NativeRegister.js:185-188` |
| `reg_type` | `getType()` | `GET`, `SET`, `MIN`, `MAX`, `ENABLE` | `js/agua/NativeRegister.js:199-202` |
| `enc_val[]` | `_parseValuesMap()` | enum entries `{value, lang, description}` | `js/agua/NativeRegister.js:497-519` |
| `enable_val[]` | `getEnabledValues()` | values for which the register is enabled | `js/agua/NativeRegister.js:366-378` |
| `app_image_name` | `getImageName()` | UI icon | `js/agua/NativeRegister.js:297-300` |
| `display_*` / `display_order` | UI placement | | `js/agua/NativeRegister.js:422-481` |

`#` in both formulas is replaced by the numeric value, then evaluated with the bundled
`math-expression-evaluator` (`index.html:62`). A Kotlin port needs an equivalent expression evaluator, or a
parser for the subset the server actually sends. The subset is `NOT FOUND`; treat formulas as data.

### 4.3 Groups and types

One user-facing control is a group (`reg_key_group`) holding at most one record per type: `GET`, `SET`,
`MIN`, `MAX`, `ENABLE` (`js/agua/Register.js:16-20`, `:32-43`).

- A control is a switch when `min === 0 && max === 1` (`js/agua/Register.js:93-96`), a slider otherwise
  (`js/agua/Register.js:121-124`).
- The slider is built from `set_min`, `set_max`, `step` and the SET value (`js/it.omniaweb.widgets.js:1106-1112`).
  No stove range is hardcoded in the UI.
- Several registers may share one offset and differ only by mask; `getRegistersByOffset()` returns the list
  (`js/agua/Device.js:547-562`).

### 4.4 Wire encoding of a write

A write is one request with five index-aligned arrays, always arrays even for a single register
(`js/agua/RegisterData.js:38-47`, `:59-70`, `:180-189`):

```json
{"Items":[offset...],"Masks":[mask...],"Values":[raw...],"Endianess":["L"|"B"...],"BitData":[8|16...]}
```

| Array | Source | Note |
|---|---|---|
| `Items` | `offset` | |
| `Masks` | `mask` | accompanies the write so the device knows which bits to replace; not applied client-side |
| `Values` | `getValue(true)` | stored raw value, `preventEvaluation === true` (`js/agua/NativeRegister.js:101-109`) |
| `Endianess` | `big_endian ? "B" : "L"` | |
| `BitData` | `is_word ? 16 : 8` | |

`Values[i]` can be `null`: the stored value starts as `null` (`js/agua/NativeRegister.js:26`) and
`setValue` only assigns when called (`:135-146`). Aggregate helpers guard the presence of each endpoint
register before adding it (`js/agua/registers/AirEnergySavingRegister.js:208-217`). So a null entry means
the register exists but its value was never set, never that the app adds blindly.

### 4.5 Value algebra

Read direction (`js/agua/NativeRegister.js:85-88`):

```js
return mexp.eval( this._data.formula.replace( '#', ( this.isHex() ? value.toString( 16 ) : value ) & this.getMask() ) );
```

Write direction (`js/agua/NativeRegister.js:135-146`):

```js
value = mexp.eval( this._data.formula_inverse.replace( '#', value ) );
this.value = this.isHex() ? parseInt( value, 16 ) : value;
```

So `raw = is_hex ? parseInt(eval(formula_inverse with '#':engineering), 16) : eval(formula_inverse with '#':engineering)`.
Masks are never applied on write. `mask` filters on read. `Endianess` and `BitData` travel as metadata only.

Temperature registers get a second transformation for display (`js/agua/NativeRegister.js:116-118`), using
the unit table at `lang/it.json:1556-1570`: celsius `#`, fahrenheit `int(#*9/5)+32`. The active unit is the
`unit_measure` register when present (`js/agua/agua.js:51-61`); with no such register the default is
celsius (`js/agua/agua.js:59`).

### 4.6 Class overrides that change behaviour

Group dispatch happens in `setRegistersMap` (`js/agua/Device.js:2262-2294`):

| Group | Class | Behaviour | Citation |
|---|---|---|---|
| `p_quality_p12`, `c_quality_p12` | `QualityRangeRegister` | display = raw - 5, write = display + 5, min and max also shifted | `js/agua/registers/QualityRangeRegister.js:29`, `:49`, `:63`, `:77`, `:133-137` |
| `multifire_1` | `Multifire1Register`, which extends `MultifireRegister` | minimum forced to 1 while new natural mode is enabled and `power > 3` | `js/agua/Device.js:2273-2280`; `js/agua/registers/Multifire1Register.js:23-28`; `js/agua/registers/MultifireRegister.js:45-62` |
| `multifire_2` | `Multifire2Register` | manageable only when `multifire_modality` ENABLE is `1` | `js/agua/registers/Multifire2Register.js:37-49` |
| everything else, including `vent_main`, `temp_air`, `power`, `status_managed` | plain `Register` | | `js/agua/Device.js:2292-2293` |

The quality-range raw bounds are `NOT FOUND`; `set_min` and `set_max` come from the server and are shifted
by 5 for display. Any specific range such as 0..10 is `UNVERIFIED`.

### 4.7 Defined numeric encodings

These are the encodings the app itself applies. Everything else is server data.

| Encoding | Rule | Citation |
|---|---|---|
| Boolean / enable flag | `1` enabled, `0` disabled | `js/agua/Chronos.js:126`, `:204`; `js/agua/registers/NaturalModeRegister.js:265` |
| Clock value (natural mode, chrono) | `hours * 6 + minutes / 10`, so 10-minute resolution, 0..143 | `js/agua/registers/NaturalModeRegister.js:424` |
| Clock decode | `hours = floor(value * 10 / 60)`, `minutes = (value * 10) % 60` | `js/agua/registers/NaturalModeRegister.js:360-362`; `js/agua/ChronosProgram.js:252-254` |
| Time disabled sentinel | `144` = `--:--`, no start or stop | `js/agua/registers/NaturalModeRegister.js:34-35`; `js/agua/ChronosProgram.js:35-36` |
| "Has a disabled state" test | start or stop register is disable-capable when `set_max === 144` | `js/agua/ChronosProgram.js:428-445` |
| Chrono day flags | one `SET` per weekday, `1` active, order Monday to Sunday | `js/agua/Device.js:869`, `:908-912`; `js/agua/ChronosProgram.js:349-357` |
| Natural mode type | `0` manual, `1` timed | `js/agua/registers/NaturalModeRegister.js:137-156`, `:222-231` |
| Inactivity | all read values `32768` means the stove did not answer | `js/it.omniaweb.config.js:58`; `js/agua/Device.js:2726-2741` |
| Enable list semantics | a group is enabled when its ENABLE value is in `enable_val`; an empty list implicitly means `1` | `js/agua/Register.js:699-711` |
| Temperature display | celsius `#`, fahrenheit `int(#*9/5)+32` | `lang/it.json:1557-1570` |

### 4.8 Read path decoding

Transport is JSON over HTTPS. Values are plain JSON numbers, one per requested offset. There is no base64 or
hex frame in the cloud path: a grep for `base64|atob|btoa` over `js/` returns 0 hits, and the only hex
handling is the per-register `is_hex` flag (`js/agua/NativeRegister.js:85`, `:142`).

Decode order per register: take the raw number, store it verbatim, then apply `raw & mask` inside `formula`
and finally the temperature-unit step if `is_temperature`.

---

## 5. Control registers and exact values

Every `offset`, `mask` and numeric range below is `NOT FOUND` in the bundle. They arrive in the register map
at runtime (section 3.2). The table states what is certain: register keys, the value algebra, and the value
domains the app itself constructs.

### 5.1 The five controls

| Control | Register key(s) and type | Value domain | How the app writes it | Citation |
|---|---|---|---|---|
| Fan speed | `vent_main`, `SET` only. No `vent_main_get`/`_min`/`_max`/`_enable` key exists | integer, range `set_min`..`set_max` with `step` from the map. Speed count `NOT FOUND` | engineering value to raw via `formula_inverse`, then a single-register array set | label `lang/it.json:1272-1274`; group-write path `js/agua/Register.js:299-306`; algebra `js/agua/NativeRegister.js:135-146`; range `js/agua/NativeRegister.js:213-244` |
| Room setpoint | `temp_air`, with `SET`, `GET`, `MIN`, `MAX` | engineering value in `set_min`..`set_max`. Range and resolution `NOT FOUND` (resolution is `formula_inverse`, e.g. `#`, `#*10`) | SET record through the same algebra; display in celsius or fahrenheit when `is_temperature` | labels `lang/it.json:1184-1199`; algebra `js/agua/NativeRegister.js:85-88`, `:135-146`; range `:213-230` |
| Power level | `power`, with `SET`, `MIN`, `MAX`, `ENABLE` | integer level. Bounds come from `power_min` / `power_max` registers. At least 4 levels exist because `power > 3` is a branch condition | SET record, same algebra | labels `lang/it.json:1116-1126`, `:1144-1146`; bound evidence `js/agua/registers/MultifireRegister.js:45-62` |
| Power on/off | `status_managed`, written through its `GET` record; `status` for readback; `status_managed_on` / `status_managed_off` ENABLE records for the button polarity | raw value = the `enc_val[].value` whose description equals `ON` or `OFF`. Concrete numbers `NOT FOUND` | `setPowered()` looks up the enum text and writes the group | `js/agua/Device.js:990-1006`; `js/agua/NativeRegister.js:326-336`; `js/agua/Register.js:488-509` |
| Mode | no single mode register exists. Feature flags instead: `natural_mode_type` (0 manual, 1 timed), `natural_mode_manual` (0/1), `natural_mode_new` (0/1), `multifire_modality` (ENABLE == 1), `multiconfort` (0, 1 or 2), plus label-only `power_auto`, `power_night`, `eco_stop` | 0/1 flags, and 0/1/2 for `multiconfort` | one or more group writes in a single request, e.g. natural mode sends type, manual, start and until together | `js/agua/registers/NaturalModeRegister.js:137-156`, `:380-400`; `js/agua/registers/Multifire2Register.js:37-49`; `js/agua/Device.js:1632`, `:1658`; `js/brands/mypiazzetta/it.omniaweb.widgets.js:2520-2528` |

About the mode row: `power_auto`, `power_night` and `eco_stop` appear in no `.js` file (`grep` returns 0
hits). Only their labels exist (`lang/it.json:1000-1002`, `1112-1114`, `1140-1142`), so their semantics and
ranges are `NOT FOUND`. `natural_mode_new` is the only one of this family that app code reads
(`js/agua/Device.js:1362-1383`).

### 5.2 On/off, exactly

```js
Device.prototype.setPowered = function( powered )
{
	var
		  register = this.getRegisterByKey( 'status_managed' )
		, native = register.getRegisterByType( it.omniaweb.agua.Register.TYPE_GET )
		, key = powered ? 'ON' : 'OFF'
		, value = native.getValueByLanguageKey( key )
	;
	native.setValue( value );
	return register.update();
};
```

`js/agua/Device.js:990-1006`. `getValueByLanguageKey()` scans `enc_val` for an entry whose description in
the current language equals the literal `ON` or `OFF` (`js/agua/NativeRegister.js:326-336`), so the server's
enum descriptions are literally `ON` and `OFF` in every language.

`register.update()` serializes the group through `getRegisterData()`, which falls back `SET` then `ENABLE`
then `GET` when no SET record exists (`js/agua/Register.js:488-509`). That is why the write lands on
`status_managed`'s GET record.

Readback polarity is inverted in source, and a port must keep it as data. Restated as logic (not verbatim):

```text
value in status_managed_on.enable_val   -> "off"
value in status_managed_off.enable_val  -> "on"
otherwise                               -> "undefined"
```

`js/agua/Device.js:1017-1033`, constants at `js/agua/Device.js:38-40`. The names describe the action the
button offers from that state, not the current state. Do not "fix" the polarity.

### 5.3 Chrono enables

| Register | Values | Citation |
|---|---|---|
| `chrono_general_enable` (`SET`) | `1` enabled, `0` disabled | `js/agua/Chronos.js:122-129` |
| `chrono_week_enable` (`SET`) | `1` / `0` | `js/agua/Chronos.js:200-207` |
| `chrono_pN_enable` (`SET`) | `1` / `0` | `js/agua/ChronosProgram.js:100-110` |
| `chrono_pN_fan` (`SET`) | integer level | label `lang/it.json:632-634` |

### 5.4 Worked write: fan speed 2 and setpoint 22

Inputs from the map: `O_fan`, `M_fan`, `O_set`, `M_set`, and the formulas. Values shown are placeholders
for server data. With identity formulas (`#` both ways) and 8-bit little-endian entries:

```
Items     = [ O_fan, O_set ]
Masks     = [ M_fan, M_set ]
Values    = [ 2,     22    ]
Endianess = [ "L",   "L"   ]
BitData   = [ 8,     8     ]
```

`RegisterData` builds that order from `addRegisterData` calls (`js/agua/RegisterData.js:59-70`). `Device._update`
merges it over the defaults `Protocol`, `BitData: 8`, `Endianess: "L"`, so the arrays replace the scalars
and the POST body is (`js/agua/Device.js:2371-2387`, plus injected `language`):

```json
{"id_product":<int>,"id_device":<int>,"Protocol":"RWMSmaster","BitData":[8,8],"Endianess":["L","L"],"registration_number":"<serial>","Items":[<O_fan>,<O_set>],"Masks":[<M_fan>,<M_set>],"Values":[2,22],"language":"it"}
```

Key order is what `JSON.stringify` emits for the app's object; it is cosmetic, and JSON key order carries no
meaning for the bridge. For a 0.1 C setpoint register (`formula_inverse` `#*10`) only `Values[1]` changes, to
`220`. That specific formula string is `NOT FOUND`.

### 5.5 What is certain vs. what must be captured

Certain, source-proven: the wire shape, the algebra, `1`/`0` booleans, the clock encoding, `144`, `32768`,
`BufferId` `1`, `Protocol` `RWMSmaster`, the ON/OFF description lookup, and the inverted power-status
polarity.

Must come from a live `deviceGetRegistersMap`: every offset, every mask, every `set_min`/`set_max`/`step`,
every `formula`/`formula_inverse`, every `enc_val` and `enable_val` list, and therefore the numeric meanings
of ON, OFF, every stove state and every alarm.

---

## 6. Command delivery, polling, timeouts, and why writes fail silently

### 6.1 The write path, step by step

| Step | What happens | Citation |
|---|---|---|
| 1 | UI save calls `register.update()` | `js/it.omniaweb.widgets.js:1382-1391` |
| 2 | `Register.update` builds `RegisterData` and calls `device.update(...getData())` | `js/agua/Register.js:542-551` |
| 3 | `Device.update` sets `updating`, ensures the map, calls `_update` | `js/agua/Device.js:132-147` |
| 4 | `_update` clears `idRequest`, POSTs `deviceRequestWriting` | `js/agua/Device.js:2363-2374` |
| 5 | On `Success`, stores `idRequest` and resolves the ticket string | `js/agua/Device.js:2396-2399` |
| 6 | On failure with `mt` not `'N'`, resolves `pl` directly; otherwise rejects `"0000"` | `js/agua/Device.js:2401-2405` |
| 7 | `Device.update` receives that string and does two things | `js/agua/Device.js:152-157` |

The critical four lines:

```js
152:  if( typeof response === 'string' )
153:  {
154:      reference.idRequest = response;
155:      reference._getJobStatus( true );   // started, never awaited, never caught
156:      resolve( true );                   // caller is told "success" here
157:  } else {
```

The promise from line 155 is discarded: no `.then`, no `.catch`, not returned. Reads do await their job:
`refresh()` returns the job promise (`js/agua/Device.js:306-313`) and so does `getInfo()`
(`js/agua/Device.js:384-393`). The write path deliberately or accidentally does not.

### 6.2 The poll loop

`_getJobStatus` (`js/agua/Device.js:2612-2672`):

1. `idRequest === null` rejects immediately (`:2614-2617`).
2. GET `deviceJobStatus` with affix `'/' + reference.idRequest`, re-read from the live field on every tick
   (`:2631`).
3. Empty `jobAnswerStatus` rejects `"0000"` (`:2637-2639`).
4. `terminated` rejects `"0000"` (`:2643-2645`).
5. `waiting` and `missing job answer` schedule another poll after
   `statusPollingFrequency` (default 300 ms) (`:2647-2650`).
6. `completed` parses `jobAnswerData`; `true` resolves, anything else rejects (`:2652-2659`).
7. Any other status matches no branch, so the promise never settles (`:2641-2660`).

### 6.3 Timeouts, retries, limits

| Concern | Value | Citation |
|---|---|---|
| Job poll interval | `statusPollingFrequency`, default 300 ms, no backoff | `js/agua/Device.js:2649` |
| Auto-refresh interval | `refreshDeviceInterval`, default 3000 ms | `js/it.omniaweb.widgets.js:518` |
| Max poll attempts | `NOT FOUND`, no counter | `js/agua/Device.js:2612-2672` |
| Job deadline | `NOT FOUND`, no timer | `js/agua/Device.js:2647-2650` |
| HTTP timeout | `NOT FOUND`, no `timeout:` key | `js/agua/agua.js:938-962` |
| Write retry | `NOT FOUND`, `grep retry\|Retry\|tentativ` over `js/` returns 0 | measurement |
| Read retry | `NOT FOUND`, the next refresh tick is the only recovery | `js/agua/Device.js:205-233` |
| Rate limiting | `NOT FOUND`; the only server feedback channel is `Blocked-Content` | `js/agua/agua.js:971-972` |

### 6.4 The silent-failure analysis

The shipped app never learns whether a write was applied. Five independent mechanisms cause that, and any
one of them alone reproduces the reported symptom ("I set the fan speed and nothing happened, with no
error").

**a. The write's outcome has no consumer.** The poll from `js/agua/Device.js:155` resolves or rejects into
nothing. `terminated`, empty status, parse failure and inactivity all become unhandled rejections after the
dialog has already closed (`js/it.omniaweb.widgets.js:1388-1390`).

**b. `completed` is treated as `applied`.** `_parseRegistersValues` writes the returned numbers into the map
and returns `true` without comparing them to the `Values[]` that were sent (`js/agua/Device.js:2703-2713`
versus the payload at `:2386`). A clamped value, an ignored command and a correct write are
indistinguishable.

**c. An empty answer counts as success.** No `Items` or no `Values` returns `true`
(`js/agua/Device.js:2686-2689`).

**d. The ticket is a single shared field.** `_getJobStatus` re-reads `reference.idRequest` on every tick
(`:2631`), while both job-creating calls clear it first (`:2366`, `:2560`) and then store their own ticket
(`:2398`, `:2585`). Closing the modal restarts auto-refresh 3 s later
(`js/it.omniaweb.logic.js:1852`), so a read job can overwrite the ticket while the write poller is still
looping. From then on the write poller watches the read job and parses the read payload. If the field is
`null` at that moment, it requests `deviceJobStatus/null`.

**e. Unknown statuses and unbounded waits hang.** Only four status strings are handled
(`js/agua/Device.js:2641-2660`), and `waiting` loops forever with no HTTP timeout
(`js/agua/agua.js:938-962`). A stove that never answers produces silence, not an error.

The only failure the user can see is read-side: all read values equal to `32768` raises
`Device.INACTIVITY_ERROR` (`js/agua/Device.js:2699-2701`, `:2726-2741`), which surfaces as the inactivity
view (`js/it.omniaweb.logic.js:2089-2091`, `:2128-2131`). That is a reachability signal, not a write
outcome. When the next refresh succeeds, a clamped value simply appears, with no message.

**Definition for a port.** In the app, "success" means: the HTTP call was accepted and a job reached
`completed` with a non-empty payload. It does not mean the requested value is now active. Section 9 fixes
that by construction.

---

## 7. Stove states and alarms (IT / EN)

### 7.1 App-level power states

The only state enum in the bundle (`js/agua/Device.js:38-40`):

| Code | Constant | IT label | EN label | Citation |
|---|---|---|---|---|
| `"off"` | `POWER_STATUS_OFF` | La stufa è spenta | OFF | `js/agua/Device.js:39`; `lang/it.json:1439`; `lang/en.json:1439` |
| `"on"` | `POWER_STATUS_ON` | La stufa è accesa | ON | `js/agua/Device.js:38`; `lang/it.json:1440`; `lang/en.json:1440` |
| `"undefined"` | `POWER_STATUS_UNDEFINED` | none rendered | none rendered | `js/agua/Device.js:40`; `js/brands/mypiazzetta/it.omniaweb.widgets.js:872-877` |

Power button labels are crossed against the state, because the button performs the opposite action:

| Key | IT | EN | Citation |
|---|---|---|---|
| `device.status.button.off` | Accendi | ON | `lang/it.json:1430`; `lang/en.json:1430` |
| `device.status.button.on` | Spegni | OFF | `lang/it.json:1431`; `lang/en.json:1431` |

The status bar switches on those three codes and picks style and icon: `device-power-on`/`check`,
`device-power-off`/`minus`, `device-power-idle`/`warning`
(`js/brands/mypiazzetta/it.omniaweb.widgets.js:859-877`). If `hasAlarm()` is true, the bar is forced to
`device-alarm` and the verbose text becomes `getAlarmLabel()` (`:878-881`).

### 7.2 State and alarm registers

| Register | Role | Citation |
|---|---|---|
| `status` (`GET`) | current numeric state code, rendered through `enc_val` | `js/agua/Device.js:1060`, `:1130` |
| `status_managed` (write) | target state, written with the ON/OFF enum value | `js/agua/Device.js:990-1006` |
| `status_managed_on` (ENABLE) | state codes from which the button turns the stove on | `js/agua/Device.js:1020`, `:1024-1027` |
| `status_managed_off` (ENABLE) | state codes from which the button turns it off | `js/agua/Device.js:1021`, `:1028-1031` |
| `alarms` (ENABLE + `GET`) | ENABLE lists alarm state codes; `GET` holds the alarm text | `js/agua/Device.js:1131`, `:1149` |

Status text is `status.getFormattedValue()` when a translation exists, else `global.not_available`
(`js/agua/Device.js:1058-1062`; `lang/it.json:1547`).

**No numeric state table exists in the bundle.** Names like accensione, pulizia, modulazione, standby do not
occur in `js/` (grep returns 0), and the only `Accensione` strings in `lang/it.json` are chrono and natural
mode start labels (`lang/it.json:196`, `:441`, `:1368`). A Kotlin client must read the state vocabulary from
the register payload:

```jsonc
{
  "enc_val":    [ { "value": 0, "lang": "it_IT", "description": "..." },
                  { "value": 0, "lang": "en_US", "description": "..." } ],  // js/agua/NativeRegister.js:497-519
  "enable_val": [ { "value": 0 }, ... ]                                    // js/agua/NativeRegister.js:366-378
}
```

Language keys in `enc_val` use the `it_IT` / `en_US` form (`js/it.omniaweb.language.js:48-67`).

### 7.3 Alarm detection and text

| Aspect | Rule | Citation |
|---|---|---|
| Alarm active? | the current `status` value is contained in the `alarms` ENABLE `enable_val` list | `js/agua/Device.js:1127-1134` |
| Alarm text | the `alarms` GET register's `getFormattedValue()`, else `global.not_available` | `js/agua/Device.js:1145-1155` |
| Alarm label | `alarms_get`: Allarmi / Alarms | `lang/it.json:504-507`; `lang/en.json:504-507` |
| UI treatment | forced `device-alarm` style, text replaced by the alarm label | `js/brands/mypiazzetta/it.omniaweb.widgets.js:878-881` |

The numeric alarm list and its texts are `NOT FOUND` in the bundle; they come from `alarms.enc_val`, exactly
as in 7.2. Any specific alarm code table is `UNVERIFIED` unless captured from a live map.

### 7.4 Warnings that are local

`Device.getWarnings()` (`js/agua/Device.js:1087-1116`) produces exactly three warnings:

| Key | Trigger | IT | EN | Citation |
|---|---|---|---|---|
| `voltage_current` | `mancata_tensione` GET == 0 | La stufa sta segnalando una attuale mancanza di tensione. Verifica i cavi e i collegamenti. | The stove is signaling a current lack of voltage. Check the cables and connections. | `js/agua/Device.js:1092-1097`; `lang/it.json:1467` |
| `voltage_restart` | `mancata_tensione_riavvio` GET == 0 | La stufa ha segnalato una mancanza di tensione durante la fase di riavvio. | The stove has reported a lack of voltage during the restart phase. | `js/agua/Device.js:1100-1105`; `lang/it.json:1468` |
| `auger_load` | `coclea_load` GET == 1 | Per ripristino condizioni di avvio svuotare il braciere. | To restore start-up conditions, empty the brazier. | `js/agua/Device.js:1108-1113`; `lang/it.json:1465` |

`Device.getPelletStockStatus()` (`js/agua/Device.js:1248-1298`) produces two more:

| Key | Trigger | Text | Citation |
|---|---|---|---|
| `pellet` | any group matching `^ris_pellet_([a-z]*)` has a truthy GET value; the value is the regex capture | pellet-stock block, keys `empty` / `ok` / `ris` | `js/agua/Device.js:1259-1266`; `lang/it.json:454-467` |
| `worktime` | `ore_service >= ore_service_soglia` | "La stufa ha superato le {max} ore di utilizzo (circa {value}). ..." / "The stove has exceeded {max} hours of use (approximately {value}). ..." | `js/agua/Device.js:1271-1278`; `lang/it.json:1469`; `lang/en.json:1469` |

Pellet stock constants: `ok`, `ris`, `empty` (`js/agua/Device.js:41-43`). When the only warning is `ok`,
the result is `ok` itself (`js/agua/Device.js:1282-1291`). Warning container title: Attenzione / Caution
(`lang/it.json:1466`).

### 7.5 Other numeric flags the UI reads

| Register | Value to meaning | Citation |
|---|---|---|
| `coclea_load` | `255` shows the power button | `js/brands/mypiazzetta/it.omniaweb.widgets.js:893` |
| `cicalino` | `0` on images, `1` off images | `js/brands/mypiazzetta/it.omniaweb.widgets.js:1547`, `:2393-2397` |
| `multicomfort` | `0` / `1` / `2` select different UI blocks | `js/brands/mypiazzetta/it.omniaweb.widgets.js:2520-2528` |
| `multicomfort` + probes | probe set `1` with value `0` and list entry `2` labelled `WIRELESS`; set `0` with value > `1` is a mismatch branch | `js/brands/mypiazzetta/it.omniaweb.widgets.js:2502-2504` |
| `sonda_canale` | `< 33` renders DIP label `OFF`, else `ON` | `js/agua/TemperatureProbes.js:201-204` |

### 7.6 Temperature fields

Probe registers are discovered by grouping regex `^sonda_(\d)_(.+)`, with sub-keys `temp` and `adr`, plus
the channel register `sonda_canale` (`js/agua/Device.js:935-978`).

| Register key | Role | IT (full) | EN (full) | Citation |
|---|---|---|---|---|
| `temp_air_get` | room temperature | Temperatura Ambiente | Room Temperature | `lang/it.json:1184-1187` |
| `temp_air_set` | room setpoint | Set Temperatura Ambiente | Set Room Temperature | `lang/it.json:1196-1199` |
| `temp_air_min` | room minimum | Temperatura Ambiente Min | Min Room Temperature | `lang/it.json:1192-1195` |
| `temp_air_max` | room maximum | Temperatura Ambiente Max | Max room Temperature | `lang/it.json:1188-1191` |
| `temp_gas_flue_get` | flue gas | Temperatura Fumi | Smoke Temperature | `lang/it.json:1200-1203` |
| `temp_probe_k_get` | type K flue probe | Sonda Fumi tipo K | Smoke Probe Type K | `lang/it.json:1204-1207` |
| `temp_water_get` / `_set` | water temperature / setpoint | Temperatura Acqua / Set Temperatura Acqua | Water Temperature / Set Water Temperature | `lang/it.json:1232-1235`, `:1268-1271` |
| `temp_water_min` / `_max` | water min / max | Temperatura Acqua (Caldaia) Min / Max | (Boiler) Water Temperature Min / Max | `lang/it.json:1236-1243` |
| `temp_water_boiler_*` | boiler block | Temperatura Boiler etc. | Boiler Temperature etc. | `lang/it.json:1208-1227` |
| `temp_water_puffer_*` | puffer block | Temperatura Puffer etc. | Buffer Temperature etc. | `lang/it.json:1244-1263` |
| `temp_water_san_set` | sanitary setpoint | Set Temperatura Acqua Sanitaria | Set Sanitary Water Temperature | `lang/it.json:1264-1267` |
| `canalization_1_*`, `canalization_2_*` | duct blocks | Canalizzazione N Temperatura aria | Duct N air temp. | `lang/it.json:540-559`, `:576-595` |
| `sonda_<n>_temp` | wireless probe temperature | labels from server | labels from server | `js/agua/Device.js:939`, `:952-971` |
| `unit_measure` | unit selector, SET value 0 or 1 | | | `js/agua/agua.js:51-61` |

Units (`lang/it.json:1557-1570`): `celsius` index 0 formula `#`, `fahrenheit` index 1 formula
`int(#*9/5)+32`. `Max room Temperature` and `Pellets depleated.` are the shipped English defects; reproduce
them only if you want label parity with the app.

---

## 8. BLE local control (optional path)

BLE is a JSON text RPC tunnel over one Nordic-UART-style characteristic. There is no CRC, no checksum, no
command id byte and no binary register frame.

### 8.1 UUIDs

| Role | UUID | Citation |
|---|---|---|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `js/it.omniaweb.config.js:88` |
| Characteristic, write, read and notify on the same handle | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | `js/it.omniaweb.config.js:89` |
| Applied to the device object | both values set | `js/agua/Device.js:2214-2215` |

Everything happens on `...0002`. The NUS TX characteristic `...0003` is never used
(`js/plugins/it.omniaweb.plugins.ble.central.js:570`, `:619`). `requestMtu` exists but is never called
(`js/plugins/it.omniaweb.plugins.ble.central.js:358-380`); `writeWithoutResponse` is defined and never used
(`:657`).

### 8.2 Frame layout

Phase A, the 8-byte announcement, written before any body byte (`js/plugins/it.omniaweb.plugins.ble.central.js:853-867`,
built at `:165-180`):

| Offset | Size | Value |
|---|---|---|
| 0..3 | 4 | `4A 53 4F 4E` (`J S O N`) |
| 4 | 1 | body length low byte (`bytesLength & 0xFF`) |
| 5 | 1 | body length high byte (`(bytesLength >> 8) & 0xFF`) |
| 6 | 1 | chunk size low byte (`0x14` = 20) |
| 7 | 1 | chunk size high byte (`0x00`) |

Length is the UTF-8 byte length of the JSON body, from `TextEncoder` (`:794-795`, encoder `:143-146`). Both
fields are little-endian 16-bit values; chunk size comes from `bleMessageChunkSize` = 20
(`js/it.omniaweb.config.js:90`).

Phase B, the body, sliced into 20-byte chunks written in order, each next chunk started from the previous
write's success callback (`js/plugins/it.omniaweb.plugins.ble.central.js:822-846`),
`chunks = ceil(length / 20)` (`:796`). No delay, no padding, no per-chunk header. Each chunk is raw UTF-8
bytes (`:624`, `:837`), so a multi-byte character can be split across chunks; the receiver must concatenate
before decoding.

Phase C, the request envelope, fixed key order (`js/it.omniaweb.bluetoothdevice.js:150-164`):

```json
{"mt":"Q","s":{"ss":"Apk","sj":"<app uuid>"},"r":{"rj":"Navel"},"pl":{"Cmd":"<command>", ...args}}
```

`Cmd` is the cloud method with `device` removed (`js/agua/agua.js:499`): `deviceRequestWriting` becomes
`RequestWriting`, `deviceGetBufferReading` becomes `GetBufferReading`, `deviceJobStatus` becomes
`JobStatus`. `mt` is `"Q"` by default (`js/it.omniaweb.bluetoothdevice.js:155`) and `"C"` for
`SetConfigBuffer` (`:339`). The device replies with `"N"` for a NACK (`:275`).

Phase D, the response, read by polling the same characteristic. Accumulate `readData` until a read trims to
exactly `Navel`, then `JSON.parse` the accumulated text (or `{}` if empty)
(`js/plugins/it.omniaweb.plugins.ble.central.js:799-821`, terminator test `:810`, parse `:813`).

### 8.3 Worked chunk arithmetic

Read example, exact body string:

```json
{"mt":"Q","s":{"ss":"Apk","sj":"a1b2c3d4-0000-0000-0000-000000000000"},"r":{"rj":"Navel"},"pl":{"Cmd":"GetBufferReading","id_product":123,"id_device":456,"BufferId":1,"registration_number":"0000000000000000"}}
```

209 UTF-8 bytes, prelude `4A 53 4F 4E D1 00 14 00`, 11 chunks (ten of 20 bytes plus 9).

Write example, exact body string:

```json
{"mt":"Q","s":{"ss":"Apk","sj":"a1b2c3d4-0000-0000-0000-000000000000"},"r":{"rj":"Navel"},"pl":{"Cmd":"RequestWriting","id_product":123,"id_device":456,"Protocol":"RWMSmaster","registration_number":"0000000000000000","Items":[1000],"Masks":[255],"Values":[2],"Endianess":["L"],"BitData":[8]}}
```

292 UTF-8 bytes, prelude `4A 53 4F 4E 24 01 14 00`, 15 chunks (fourteen of 20 bytes plus 12). The length
always follows the actual string: a longer UUID, id or item list changes both the prelude bytes and the
chunk count. Compute it from encoded bytes, never from character count.

### 8.4 Connect sequence

`BluetoothDevice.connect` (`js/it.omniaweb.bluetoothdevice.js:46-112`) chains:

1. `ble.connect(deviceId)` (`js/plugins/it.omniaweb.plugins.ble.central.js:389-410`, called at
   `js/it.omniaweb.bluetoothdevice.js:57`).
2. `startNotification` on the same service and characteristic. The handler only logs
   (`js/it.omniaweb.bluetoothdevice.js:63-71`), and the plugin's success callback is dead code because it
   tests `typeof failure === 'success'` (`js/plugins/it.omniaweb.plugins.ble.central.js:717`). Notifications
   are unused; all data comes from polling reads.
3. `Identity` with `{Id: <wifi MAC>, Security: <security_code>}` (`js/it.omniaweb.bluetoothdevice.js:80-87`;
   sources `js/agua/Device.js:1849-1852`, `:1965-1968`).
4. `checkConfigBufferId` via `GetBufferId` (`js/it.omniaweb.bluetoothdevice.js:264-300`). Accepted when
   `mt !== 'N'`, `pl.BufferCnt > 0`, and `defaultBufferId` (1) is in `pl.Indexes`.
5. On any of those failures, `SetConfigBuffer` with `mt: "C"` and
   `{BufferId:1, Freq:3, Protocol:"RWMSmaster", Items:[...], BitData:[...], Endianess:[...]}`, with offsets
   de-duplicated (`js/it.omniaweb.bluetoothdevice.js:309-341`).
6. Dispatch `BLUETOOTH_DEVICE_CONNECTED` (`js/it.omniaweb.bluetoothdevice.js:101`).

`invokeMethod` re-enters `connect()` on every call and returns early when already connected
(`js/it.omniaweb.bluetoothdevice.js:125-127`, `:50-53`).

### 8.5 Scan, name matching, Wi-Fi provisioning

Bluetooth mode is a local flag in `localStorage` under `bluetooth` (`js/it.omniaweb.logic.js:1643-1646`,
`:1703-1708`), enabled for this brand by `showWifiBleSwitch: true` (`js/it.omniaweb.config.js:64`). Scanning
requires scan permission and location enabled (`js/plugins/it.omniaweb.plugins.diagnostics.js:558-561`).

The scan is unfiltered, with `reportDuplicates:false` and a 20 s window
(`js/plugins/it.omniaweb.plugins.ble.central.js:257-291`, `:14`). The peripheral is resolved by advertised
name, not by service UUID (`js/agua/Device.js:2203-2219`). The name is `wifiPrefix + last 6 characters of
the Wi-Fi MAC`, with `wifiPrefix` `T009_` and `ssidDigits` `6`
(`js/agua/Device.js:1895-1898`; `js/it.omniaweb.config.js:56-57`), and it is the same string as the stove's
`T009_xxxxxx` access-point SSID (`js/agua/Device.js:1881-1884`).

Wi-Fi commands (`js/it.omniaweb.bluetoothdevice.js:352-400`):

| Command | Payload | Note |
|---|---|---|
| `StatusWifiStation` | none | response `pl.ConnSta` in `Disconnected`, `Connected`, `Connecting`, `Disconnecting`, `Connecting_Fail`, plus `pl.Aps[]` (`js/it.omniaweb.views.js:2587-2604`) |
| `SetWifiStation` | `{Activation:"Connect", Id:1, ssid, password}` | 10 s app-side timeout (`js/it.omniaweb.bluetoothdevice.js:394-400`, mechanics `:136-145`) |
| `SetWifiStation` | `{Activation:"Disconnect"}` | `js/it.omniaweb.bluetoothdevice.js:372-383` |

The timeout in `invokeMethod` is a wall clock that rejects `"timeout"`; it does not cancel the BLE write
(`js/it.omniaweb.bluetoothdevice.js:136-145`).

### 8.6 Register access over BLE

No BLE command addresses a register directly. Reads are `GetBufferReading` and writes are `RequestWriting`,
both carrying `Items`/`Values` arrays built from the same register map
(`js/agua/Device.js:2565-2592`, `:2371-2407`). Over BLE the response has no job ticket: `mt !== 'N'` means
`pl` is the answer, which the same `Items`/`Values` parser consumes (`js/agua/Device.js:2396-2407`,
`:2684-2717`). Each successful write triggers a forced refresh, so expect one read frame per write frame
(`js/agua/Device.js:179-185`).

Offline caveats, all real in source:

- The register map is not fetched in Bluetooth mode: `_getRemoteRegistersMap` returns immediately
  (`js/agua/Device.js:2502-2506`). Without the cached `<MAC>.json` there are no offsets
  (`js/agua/Device.js:2473`).
- `Identity` needs `mac` and `security_code` from the cloud device record, so those must be cached too
  (`js/agua/Device.js:1849-1852`, `:1965-1968`).
- Auto-refresh is suppressed in this mode (`js/agua/Device.js:208-212`).

### 8.7 Absent by design

`NOT FOUND` across the bundle: CRC, checksum, parity, command-id bytes, a fixed binary register frame,
inter-chunk delays, any chunk size other than 20, MTU negotiation in use, a notify stream carrying
responses, BLE bonding, LESC or passkey code, the meaning of `Freq: 3` (`js/it.omniaweb.bluetoothdevice.js:333`),
and the failure semantics of `Identity`. The fan-speed register on this path is `NOT FOUND` for the same
reason as in the cloud path: it lives in the server map.

---

## 9. Proposed Kotlin API surface

The contract in one sentence: a write is only "applied" when a subsequent independent read shows the
requested value **through the register mask**. Ticket acceptance means nothing. This is a deliberate
departure from the app, which resolves writes on ticket acceptance (section 6.4b) and never retries.

The Kotlin below is a design sketch written from the source. Nothing in this section was compiled in this
workspace, because the task scope was documentation only. Treat types and signatures as the contract, and
check syntax when you implement.

### 9.1 Errors

```kotlin
sealed class AguaException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Bridge answered Success=false, or a transport error with a code. */
    class BridgeError(val code: String, val info: String? = null) : AguaException("bridge error $code")

    /** HTTP/DNS/TLS failure, or the request exceeded the client timeout. */
    class TransportError(cause: Throwable) : AguaException("transport failure", cause)

    /** 1106 / 1116 / 1144 surfaced after the replay policy was exhausted. */
    class SessionInvalid(val code: String) : AguaException("session invalid: $code")

    /** Job answered "terminated". Terminal: the stove refused or aborted the command. */
    class TerminatedByStove(val ticket: JobTicket) : AguaException("job terminated")

    /** Poll deadline elapsed while the job stayed waiting / missing job answer. */
    class JobTimeout(val ticket: JobTicket, val polls: Int, val elapsedMs: Long) :
        AguaException("job $ticket not completed after $polls polls")

    /** Read answered with all values equal to 32768. */
    class StoveUnreachable(val ticket: JobTicket?) : AguaException("stove unreachable")

    /** Completed job carried no Items/Values. The app treats this as success; we do not. */
    class EmptyAnswer(val ticket: JobTicket) : AguaException("empty job answer")

    /** Read-back succeeded but the requested value is not what the stove reports. */
    class NotConfirmed(
        val requested: RegisterValues,
        val observed: RegisterValues?,
        val attempts: Int,
    ) : AguaException("write not confirmed after $attempts attempt(s)")

    /** The register key is absent from the device map. */
    class RegisterNotInMap(val key: String) : AguaException("register $key not in map")

    /** A control is not expressible on this device (for example a mode register that does not exist). */
    class Unsupported(val reason: String) : AguaException(reason)
}
```

Retry policy: retry **at most N times** (N = 3 total attempts by default) only for retryable failures
(`TransportError`, `JobTimeout`, `EmptyAnswer`, and a `NotConfirmed` mismatch). Do not retry `BridgeError`
with a terminal code, `TerminatedByStove`, `StoveUnreachable`, `RegisterNotInMap` or `SessionInvalid`.
After the last attempt, throw the typed error above. Never return a boolean.

### 9.2 Value types

```kotlin
enum class HttpMethod { GET, POST, PUT, DELETE }

/** The two affix uses in the app: productList and deviceJobStatus. Everything else passes null. */
data class BridgeCall(
    val action: String,
    val method: HttpMethod,
    val payload: JsonObject? = null,
    val affix: String? = null,
)

data class BrandConfig(
    val baseUrl: String = "https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/", // js/it.omniaweb.config.js:48
    val customerCode: String = "458632",   // js/it.omniaweb.config.js:47
    val idBrand: String = "1",             // js/it.omniaweb.config.js:46
    val brandSlug: String = "piazzetta",   // js/it.omniaweb.config.js:44
    val clientId: String = "mypiazzetta",  // js/it.omniaweb.config.js:45, consent payloads only
    val appVersion: String = "1.9.91",     // js/it.omniaweb.config.js:39
    val inactivityCode: Int = 32768,       // js/it.omniaweb.config.js:58
    val defaultBufferId: Int = 1,          // js/it.omniaweb.config.js:87
)

data class AguaUser(val id: String?, val aguaId: String?, val email: String?)  // js/agua/User.js:866-885

data class AguaSession(
    val token: String?,          // "agua.token"
    val refreshToken: String?,   // "agua.refreshToken"
    val appUuid: String?,        // "agua.uuid", also the pre-login authorization value
)
```

### 9.3 Transport and session

```kotlin
interface BridgeClient {
    /** Adds CustomerCode, IdBrand, ApplicationVersion, authorization, local, brand, aguaid, userid,
     *  method and url (js/agua/agua.js:891-903, :955-956). Merges `language` on non-GET (js/agua/agua.js:922-925).
     *  Throws BridgeError / TransportError. Applies the 1106 refresh-and-replay and 1144
     *  activate-and-replay policies once each (js/agua/agua.js:1002-1056). */
    suspend fun call(call: BridgeCall): JsonObject
}

interface SessionStore {
    fun load(): AguaSession
    fun save(session: AguaSession)
    fun clear()
}

interface AuthService {
    suspend fun login(email: String, password: String, language: String): AguaSession
    suspend fun userDetail(): AguaUser
    suspend fun refreshToken(): AguaSession
    suspend fun logout()
    suspend fun appSignup(installationId: String, pushToken: String?, phoneVersion: String)
}
```

Implementation notes for `BridgeClient`: GET carries no body (`js/agua/agua.js:946`); `url` header is
`action + affix` (`:956`); `method` header is the verb (`:955`); every request needs a client-side timeout,
which the app lacks (`js/agua/agua.js:938-962`); `Blocked-Content` is parsed on every success (`:971-972`)
and can be exposed as `val blocked: Set<String>`.

### 9.4 Device and register model

```kotlin
data class DeviceRef(
    val id: String,             // js/agua/Device.js:1778
    val idDevice: String,       // js/agua/Device.js:1806
    val idProduct: String,      // js/agua/Device.js:1820
    val registrationNumber: String, // product_serial, js/agua/Device.js:1911, :2827-2830
    val name: String,           // js/agua/Device.js:1981
    val mac: String,            // js/agua/Device.js:1851
    val securityCode: String?,  // js/agua/Device.js:1967, BLE Identity only
    val isOnline: Boolean?,     // js/agua/Device.js:2160
)

enum class RegisterType { GET, SET, MIN, MAX, ENABLE, OTHER }

data class EnumValue(val value: Int, val lang: String, val description: String)

data class RegisterDefinition(
    val regKey: String,            // "temp_air"
    val regKeyGroup: String,       // "temp_air"
    val regType: RegisterType,
    val offset: Int,               // server field, never hardcoded
    val mask: Int,                 // read-side filter
    val bigEndian: Boolean,        // "Endianess" token "B" / "L"
    val isWord: Boolean,           // "BitData" token 16 / 8
    val formula: String,           // raw -> engineering, '#' placeholder
    val formulaInverse: String,    // engineering -> raw
    val setMin: Int,
    val setMax: Int,
    val step: Int,
    val isHex: Boolean,
    val isTemperature: Boolean,
    val encVal: List<EnumValue>,
    val enableVal: List<Int>,
    val formatString: String?,
)

data class RegisterGroup(
    val groupKey: String,
    val byType: Map<RegisterType, RegisterDefinition>,
)

/** One entry of the five parallel wire arrays (js/agua/RegisterData.js:180-189). */
data class RegisterWrite<T>(
    val item: Int,
    val mask: Int,
    val value: T?,
    val endianess: String,
    val bitdata: Int,
)

data class RegisterValues(
    val items: List<Int>,
    val values: List<Int>,
    val ticket: JobTicket? = null,
)

@JvmInline value class JobTicket(val id: String)
```

### 9.5 Codec

```kotlin
interface FormulaEvaluator {
    /** Equivalent of mexp.eval: supports '#', integers, + - * / and int(). */
    fun eval(expression: String): Double
}

class RegisterCodec(private val evaluator: FormulaEvaluator) {

    /** engineering value -> raw, js/agua/NativeRegister.js:135-146. Mask is NOT applied. */
    fun encode(engineering: Double, def: RegisterDefinition): Int {
        val substituted = def.formulaInverse.replace("#", trimNumber(engineering))
        val evaluated = evaluator.eval(substituted)
        // parseInt(value, 16) in the source parses the decimal string as hex, so mirror that here.
        return if (def.isHex) evaluated.toInt().toString().toInt(16) else evaluated.toInt()
    }

    /** raw -> engineering, js/agua/NativeRegister.js:85-88. Mask IS applied, then temperature. */
    fun decode(raw: Int, def: RegisterDefinition): Double {
        val masked = raw and def.mask
        val base = evaluator.eval(def.formula.replace("#", if (def.isHex) masked.toString(16) else masked.toString()))
        return base // temperature-unit step is applied by the UI layer, js/agua/NativeRegister.js:116-118
    }

    /** Compare a requested value with an observed one through the mask. */
    fun matches(def: RegisterDefinition, requestedRaw: Int?, observedRaw: Int?): Boolean =
        requestedRaw != null && observedRaw != null && (requestedRaw and def.mask) == (observedRaw and def.mask)

    private fun trimNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
}
```

The hex handling above mirrors the source's `parseInt(value, 16)`. Keep it, even though real maps may never
use it.

### 9.6 Device service and job polling

```kotlin
interface DeviceService {
    suspend fun listDevices(language: String): List<DeviceRef>
    suspend fun registerMap(device: DeviceRef, lastUpdate: String?): List<RegisterGroup>
    suspend fun read(device: DeviceRef, hideLoader: Boolean = true): RegisterValues
    /** Returns a ticket, or an immediate answer parsed to RegisterValues. */
    suspend fun write(device: DeviceRef, writes: List<RegisterWrite<Int>>): WriteAck
}

sealed interface WriteAck {
    data class Ticket(val ticket: JobTicket) : WriteAck
    data class Immediate(val values: RegisterValues) : WriteAck
}

interface JobPoller {
    /** Ticket is a parameter, never a mutable per-device field (the app's bug, js/agua/Device.js:2631). */
    suspend fun poll(ticket: JobTicket, policy: WritePolicy): RegisterValues
}
```

`JobPoller.poll` maps the four status strings exactly as `js/agua/Device.js:2637-2660` does, and adds the
one branch the app lacks:

| `jobAnswerStatus` (case-insensitive) | Result |
|---|---|
| empty | `BridgeError("0000")` |
| `terminated` | `TerminatedByStove(ticket)` |
| `waiting`, `missing job answer` | delay, poll again until the deadline, then `JobTimeout` |
| `completed` | parse `jobAnswerData`; no `Items`/`Values` is `EmptyAnswer`; all values `32768` is `StoveUnreachable` |
| anything else | `BridgeError(status)` (the app hangs here) |

### 9.7 Confirmed-write contract

```kotlin
data class WritePolicy(
    val maxAttempts: Int = 3,               // RECOMMENDED: the app never retries
    val pollIntervalMs: Long = 300,         // matches statusPollingFrequency, js/agua/Device.js:2649
    val pollIntervalStepMs: Long = 100,     // RECOMMENDED backoff
    val pollIntervalMaxMs: Long = 1_500,    // RECOMMENDED cap
    val jobDeadlineMs: Long = 20_000,       // RECOMMENDED: the app has no deadline
    val retryDelayMs: List<Long> = listOf(2_000, 5_000), // RECOMMENDED between attempts
    val requestTimeoutMs: Long = 15_000,    // RECOMMENDED: the app has no HTTP timeout
)

data class ConfirmedWrite(
    val requested: RegisterValues,
    val observed: RegisterValues,
    val attempts: Int,
    val ticket: JobTicket?,
)

interface RegisterWriter {
    /**
     * send -> poll -> confirm, retry up to policy.maxAttempts, then throw AguaException.
     * Never returns a boolean. Never reports success from ticket acceptance alone.
     */
    suspend fun writeConfirmed(
        device: DeviceRef,
        groupKey: String,
        engineeringValue: Double,
        policy: WritePolicy = WritePolicy(),
    ): ConfirmedWrite
}
```

The algorithm, in order:

```
attempt = 1
loop:
  1. def = group(groupKey).byType[SET] ?: byType[ENABLE] ?: byType[GET]   // js/agua/Register.js:488-509
     else throw RegisterNotInMap(groupKey)
  2. raw = codec.encode(engineeringValue, def)                            // js/agua/NativeRegister.js:135-146
  3. ack = deviceService.write(device, [RegisterWrite(def.offset, def.mask, raw,
                                                       if (def.bigEndian) "B" else "L",
                                                       if (def.isWord) 16 else 8)])
  4. if ack is Ticket: answer = jobPoller.poll(ack.ticket, policy)        // js/agua/Device.js:2637-2660
     else: answer = ack.values
  5. observed = deviceService.read(device)                                // independent re-read
  6. if observed has all values == 32768 -> throw StoveUnreachable
  7. if every requested (offset, raw) matches observed through the mask -> return ConfirmedWrite
  8. if attempt == policy.maxAttempts -> throw NotConfirmed(requested, observed, attempt)
  9. sleep(policy.retryDelayMs[attempt - 1]); attempt++; goto loop
```

Serialization requirement: hold one `Mutex` per device around steps 3 to 6. The app allows a read job to
start while a write job is unconfirmed (`js/agua/Device.js:2560`, `:2585`) and overwrites the shared ticket,
which is exactly the failure this design removes.

Comparison requirement: compare through the mask (step 7). A bitfield register will otherwise produce false
mismatches on bits the command does not own.

### 9.8 Control-level API

```kotlin
interface StoveController {
    /** vent_main SET. Range is def.setMin..def.setMax from the map; speed count is not known at compile time. */
    suspend fun setFanSpeed(device: DeviceRef, level: Int, policy: WritePolicy = WritePolicy()): ConfirmedWrite

    /** temp_air SET. Value is engineering unit after codec.decode. */
    suspend fun setRoomSetpoint(device: DeviceRef, value: Double, policy: WritePolicy = WritePolicy()): ConfirmedWrite

    /** power SET. */
    suspend fun setPowerLevel(device: DeviceRef, level: Int, policy: WritePolicy = WritePolicy()): ConfirmedWrite

    /** status_managed: raw value of the enc_val entry whose description equals "ON" / "OFF". */
    suspend fun setPowered(device: DeviceRef, on: Boolean, policy: WritePolicy = WritePolicy()): ConfirmedWrite

    /** natural_mode_type 0/1, natural_mode_manual 0/1, start/until as hours*6 + minutes/10, 144 = disabled.
     *  Sent as one request (js/agua/registers/NaturalModeRegister.js:380-400). */
    suspend fun setNaturalMode(
        device: DeviceRef,
        timed: Boolean,
        manual: Boolean,
        start: LocalTime?,
        until: LocalTime?,
        policy: WritePolicy = WritePolicy(),
    ): ConfirmedWrite

    /** chrono_general_enable, chrono_week_enable, chrono_pN_enable: 1 / 0. */
    suspend fun setChronoEnabled(device: DeviceRef, target: ChronoTarget, enabled: Boolean): ConfirmedWrite

    /** There is no single mode register. Throws Unsupported unless the device map carries the flags. */
    suspend fun setOperatingMode(device: DeviceRef, mode: OperatingMode): ConfirmedWrite
}

enum class ChronoTarget { GENERAL, WEEK, PROGRAM_1, PROGRAM_2, PROGRAM_3, PROGRAM_4, PROGRAM_5, PROGRAM_6 }

sealed interface OperatingMode {
    data object Manual : OperatingMode
    data object Timed : OperatingMode
    data class MultiComfort(val raw: Int) : OperatingMode   // 0/1/2; semantics beyond UI blocks NOT FOUND
}
```

`setPowered` resolves the enum value by description text, not by a constant:

```kotlin
fun onOffRaw(def: RegisterDefinition, on: Boolean): Int {
    val key = if (on) "ON" else "OFF"
    return def.encVal.firstOrNull { it.lang.equals("it_IT", true) && it.description == key }?.value
        ?: def.encVal.firstOrNull { it.description == key }?.value
        ?: throw AguaException.RegisterNotInMap("status_managed enc_val '$key'")
}
```

The app compares against the current UI language (`js/agua/NativeRegister.js:326-336`), but the server's
descriptions are literally `ON` and `OFF`, so language-independent matching is safe and clearer.

---

## 10. Open questions

Each item is either absent from the bundle or only observable against the live service. None of them blocks
a first implementation, all of them block full parity.

### 10.1 Server-side facts

1. **Token format and expiry.** Never decoded. `NOT FOUND`. `UNVERIFIED` whether it is a JWT.
2. **Refresh-token rotation.** The client stores only the new access token (`js/agua/User.js:568`).
   `NOT FOUND`.
3. **HTTP status mapping.** The client never reads `jqXHR.status`; which errors arrive as 200 with
   `Success:false` and which as 4xx is `NOT FOUND` (`js/agua/agua.js:969-1066`).
4. **Full error catalogue.** Only `1106`, `1116`, `1144`, `1134` and `INVALID_APP_ERROR` are named
   (`js/agua/agua.js:995-1060`; `js/it.omniaweb.logic.js:221-224`, `:3297-3299`). The rest is `NOT FOUND`.
5. **Server routes.** Whether `/api/bridge/endpoint/` is the only route, or an alias that dispatches on the
   `url` header, is `NOT FOUND`. The client always uses that one URL (`js/agua/agua.js:941`).
6. **`Blocked-Content` semantics.** Comma-split into a set and used for feature gating
   (`js/agua/agua.js:971-972`; `js/it.omniaweb.logic.js:4088-4092`). The value vocabulary is `NOT FOUND`.
7. **Local authorization acceptance.** The app sends `authorization: <uuid>` with `local: true` before
   login (`js/agua/User.js:127-129`) and still calls `deviceList` (`js/it.omniaweb.logic.js:1113`), but
   whether the server honours it is `UNVERIFIED`.
8. **Write acceptance semantics.** Whether the bridge ever rejects a write for a device-level reason, and
   what `jobAnswerStatus` values exist beyond the four handled strings, is `NOT FOUND`
   (`js/agua/Device.js:2637-2660`).
9. **TLS pinning.** No pinning code in the bundle. Whether the native Android layer pins certificates is
   `NOT FOUND` (outside `www/`).
10. **`userDetail` full shape.** Only `id`, `agua_id`, `email` are read (`js/agua/User.js:796`, `:866-885`).
11. **`deviceList` full shape.** Only the fields in 3.1 are read. Registers arrive through the map.

### 10.2 Stove data that only a live capture can settle

12. **Every register offset and mask.** `NOT FOUND` in the APK by design (section 4.1).
13. **Fan-speed count and range.** `NOT FOUND`. Grep for `vent`/`fan` word tokens over `js/` returns 0 hits;
    the only artefact is the `vent_main_set` label (`lang/it.json:1272-1274`).
14. **Setpoint range and resolution.** `NOT FOUND`. Resolution lives in `formula_inverse`, range in
    `set_min`/`set_max` (`js/agua/NativeRegister.js:135-146`, `:213-230`).
15. **Power-level count.** Only a lower bound is provable: `power > 3` is a branch
    (`js/agua/registers/MultifireRegister.js:55`), so at least four levels exist.
16. **Numeric meanings of ON and OFF for `status_managed`.** In the server `enc_val`, addressed only by
    description text (`js/agua/Device.js:998`; `js/agua/NativeRegister.js:326-336`).
17. **`status_managed_on` / `status_managed_off` enable lists.** Server data (`js/agua/NativeRegister.js:366-378`;
    used at `js/agua/Device.js:1020-1021`).
18. **The state vocabulary and all alarm codes with IT/EN text.** They come from `status.enc_val`,
    `alarms.enc_val` and `alarms.enable_val`. None exist in the bundle (section 7.2). Any hardcoded table
    would be `UNVERIFIED`.
19. **`power_auto`, `power_night`, `eco_stop` semantics and ranges.** Labels only; no `.js` code reads them
    (`lang/it.json:1000-1002`, `1112-1114`, `1140-1142`). `NOT FOUND`.
20. **Whether any mode concept spans eco, comfort, auto and manual.** Only feature flags exist; a single
    mode register is `NOT FOUND` (section 5.1).
21. **`Freq: 3` on `SetConfigBuffer`.** Transmitted, never interpreted (`js/it.omniaweb.bluetoothdevice.js:333`).
    `NOT FOUND`.
22. **BLE `Identity` failure modes and the security-code format.** Only field names are visible
    (`js/it.omniaweb.bluetoothdevice.js:80-87`; `js/agua/Device.js:1965-1968`). `NOT FOUND`.
23. **Firmware string format.** Rebuilt as `FW-xV-y-z-w` from `fw_1..fw_6` with no documented meaning
    (`js/agua/Device.js:1166-1188`). `UNVERIFIED`.
24. **`unit_measure` semantics beyond 0 = celsius, 1 = fahrenheit.** Any other index is `NOT FOUND`
    (`js/agua/agua.js:51-61`).

### 10.3 Report-level leftovers

25. **Quality-range raw bounds.** The class shifts by `MIDDLE_VALUE = 5`
    (`js/agua/registers/QualityRangeRegister.js:29`, `:49`, `:63`, `:77`). No bound exists in the bundle;
    any range is `UNVERIFIED`.
26. **Formula language.** The app delegates to the bundled `math-expression-evaluator`
    (`index.html:62`), so the server may send any expression that library accepts. The actual subset in use
    is `NOT FOUND`. A Kotlin evaluator must be chosen or written before formulas can be treated as data.
