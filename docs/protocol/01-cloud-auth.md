# 01 — Agua IoT cloud authentication and session flow

Reverse-engineered from the shipped MyPiazzetta 1.9.91 Cordova bundle.

**Citation root:** `docs/protocol/source-snapshot/` — every `path:line` below is relative to that directory.
**App version:** `"applicationVersion": "1.9.91"` — `js/it.omniaweb.config.js:39`
**Brand block:** `js/it.omniaweb.config.js:41-68`

Nothing in this document was tested against the live cloud. It is a reading of the client code only; where the
server's behaviour cannot be derived from the client, the item is listed in section 5 as `NOT FOUND`.

---

## 1. Transport, envelope and endpoint table

### 1.1 Transport

| Property | Value | Citation |
|---|---|---|
| Base URL | `https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/` | `js/it.omniaweb.config.js:48` |
| Alternate (commented out) hosts | `https://bridge.mypiazzetta.localhost/api/bridge/endpoint/`, local `proxy.php` variant | `js/it.omniaweb.config.js:49-50` |
| Port | `null`; only the dead pre-override implementation appends it | `js/it.omniaweb.config.js:51`, `js/agua/agua.js:523-524` |
| Brand code (`CustomerCode`) | `"458632"` | `js/it.omniaweb.config.js:47` |
| Brand numeric id (`IdBrand`) | `1` | `js/it.omniaweb.config.js:46` |
| Brand slug (`brand`) | `"piazzetta"` | `js/it.omniaweb.config.js:44` |
| `clientId` | `"mypiazzetta"` — **not** an auth credential, only used in consent calls | `js/it.omniaweb.config.js:45` |
| HTTP methods used | literal `POST`, `GET`, `DELETE`, `PUT` | `js/agua/agua.js:32` |
| Content type | `application/json; charset=utf-8` | `js/agua/agua.js:945` |
| Body | `JSON.stringify(payload)` for non-GET; `null` for GET | `js/agua/agua.js:946` |
| Response type | `json`, `crossDomain: true` | `js/agua/agua.js:943`, `js/agua/agua.js:947` |

**There is exactly one URL.** Every bridge call is an HTTP request to the base URL unchanged — the requested
*action* travels in request headers, not in the path:

```
url: it.omniaweb.config.currentBrand.host        // js/agua/agua.js:941
...
jqXHR.setRequestHeader( 'method', method );      // js/agua/agua.js:955
jqXHR.setRequestHeader( 'url', action + urlaffix ); // js/agua/agua.js:956
```

An earlier implementation inside the same file built `url + action + urlaffix` as the request URL
(`js/agua/agua.js:517-546`, URL at `js/agua/agua.js:541`). It is **dead code**: the brand-override block
re-assigns `instance.invokeRemoteMethod` later in the file (`js/agua/agua.js:915`) and the singleton is only
published after that assignment (`js/agua/agua.js:1079`). A Kotlin client must reproduce the *override*
behaviour: single endpoint, action in the `url` header.

The optional `affix` argument is the only path-like suffix mechanism and appears in exactly one place:
`productList` passes `'/' + code`, i.e. `url: productList/458632` (`js/agua/agua.js:333`, combined at
`js/agua/agua.js:956`).

### 1.2 Request headers (the "envelope")

Built by the brand override of `getRemoteMethodInvocationHeaders` (`js/agua/agua.js:891-903`) and applied in
`beforeSend` (`js/agua/agua.js:948-956`):

| Header | Value | Citation |
|---|---|---|
| `CustomerCode` | `458632` (brand `code`) | `js/agua/agua.js:894` |
| `IdBrand` | `1` (brand `id`) | `js/agua/agua.js:895` |
| `ApplicationVersion` | `1.9.91` | `js/agua/agua.js:896` |
| `authorization` | session token for a logged-in user, or the device UUID when not logged in | `js/agua/agua.js:897`, `js/agua/User.js:125-135` |
| `local` | `true`/`false` — `isLocal()` i.e. "no token present" | `js/agua/agua.js:898`, `js/agua/User.js:77-83` |
| `brand` | `piazzetta` | `js/agua/agua.js:899` |
| `aguaid` | `user.id` or `null` | `js/agua/agua.js:900`, `js/agua/User.js:882-885` |
| `userid` | `user.agua_id` or `null` | `js/agua/agua.js:901`, `js/agua/User.js:868-871` |
| `method` | `GET` / `POST` / `PUT` / `DELETE` | `js/agua/agua.js:955` |
| `url` | action name, optionally with affix | `js/agua/agua.js:956` |

All header names are lowercase except `CustomerCode`, `IdBrand`, `ApplicationVersion`; HTTP is case-insensitive
so this is cosmetic, but keep the exact names for fidelity.

`authorization` carries the **raw token** — no `Bearer` prefix, no scheme (`js/agua/User.js:133` returns
`this.token` directly). Unauthenticated calls still send `authorization: <uuid>` with `local: true`
(`js/agua/User.js:127-129`). Pre-login, `aguaid`/`userid` are `null` (`js/agua/User.js:870`, `js/agua/User.js:884`)
and are handed to `setRequestHeader` unchanged (`js/agua/agua.js:953`).

### 1.3 Session storage

- Token is **not** a cookie: the client never sets `withCredentials` and never touches `document.cookie`
  anywhere in `js/` (no match for `cookie`/`withCredentials`), and the AJAX call has no cookie options
  (`js/agua/agua.js:938-947`).
- Session lives in `localStorage` under four keys: `agua.token`, `agua.refreshToken`, `agua.logged`, `agua.user`
  (`js/agua/User.js:456-458`, `js/agua/User.js:700-703`), read/written with `JSON.parse`/`JSON.stringify`
  (`js/it.omniaweb.utils.js:66-88`).
- Device identity `agua.uuid` is also persisted (`js/agua/User.js:112`) and doubles as the pre-login
  `authorization` value.

### 1.4 Password handling

**No client-side hashing, signing, HMAC or API key exists.** The plaintext password from the form field is put
verbatim into the JSON body (`js/agua/User.js:446`, source of the value `js/it.omniaweb.logic.js:3288`).
A repository-wide grep for `md5|sha1|sha256|hmac|signature|api_key|apikey|btoa` over `js/` (excluding `libs/`)
returns only UI "signature" hits in widget files, no crypto. Transport security is HTTPS only
(`js/it.omniaweb.config.js:48`).

### 1.5 Endpoint table

Every row is a `url` header value on a `POST`, `GET` or `DELETE` to the base URL. Methods come from
`it.omniaweb.agua.methods` (`js/agua/agua.js:32`).

| Action (`url` header) | Method | Path | Purpose | Citation |
|---|---|---|---|---|
| `userLogin` | POST | `/api/bridge/endpoint/` | email+password login, returns tokens | `js/agua/User.js:443-448` |
| `userDetail` | GET | same | load user record after login | `js/agua/User.js:184-186` |
| `refreshToken` | POST | same | exchange refresh token for a new access token | `js/agua/User.js:557-561` |
| `userLogout` | POST | same | invalidate the refresh token | `js/agua/User.js:598-602` |
| `userSignup` | POST | same | registration | `js/agua/User.js:414-419` |
| `userActivate` | POST | same | activate account (credentials replayed) | `js/agua/User.js:493-497` |
| `appSignup` | POST | same | register the *app installation* (push/device id) | `js/agua/agua.js:720-724` |
| `deviceList` | POST | same | list devices owned by the user | `js/agua/User.js:230-234` |
| `deviceRegister` | POST | same | register a device | `js/agua/User.js:856` |
| `deviceRegisterListWithUser` | POST | same | merge local devices after activation | `js/agua/User.js:672-686` |
| `deviceUnRegister` | POST | same | unregister a device | `js/agua/Device.js:502` |
| `deviceGetRegistersMap` | POST | same | read a device register map | `js/agua/Device.js:2513-2524` |
| `deviceGetBufferReading` | POST | same | start a buffered telemetry read | `js/agua/Device.js:2565-2578` |
| `deviceJobStatus` | POST | same | poll the telemetry job | `js/agua/Device.js:2628` |
| `deviceRequestWriting` | POST | same | write registers (`Device._update`) | `js/agua/Device.js:2371-2374` |
| `deviceInfoFromSerialnumber` | POST | same | lookup product by serial | `js/agua/agua.js:373-379` |
| `deviceInfoFromMac` | POST | same | lookup product by MAC | `js/agua/agua.js:411-417` |
| `productList` | GET | same, affix `/458632` | product catalogue for this brand | `js/agua/agua.js:328-334` |
| `countryList` | GET | same | country list | `js/agua/agua.js:218-223` |
| `getCountiesByCountryId` | POST | same | provinces | `js/agua/agua.js:256-261` |
| `getCitiesByCountyId` | POST | same | cities | `js/agua/agua.js:290-294` |
| `translateRegKeyList` | POST | same | register-name language bundle (1106 erases tokens) | `js/agua/agua.js:790-798`, `js/agua/agua.js:978-982` |
| `getAppConfiguration` | POST | same | server-driven app config | `js/it.omniaweb.logic.js:208-214` |
| `getClientConsents` | POST | same | consent list, body carries `client_id: "mypiazzetta"` | `js/it.omniaweb.logic.js:603-612` |
| `setClientConsents` | POST | same | submit consents | `js/it.omniaweb.logic.js:3681-3688` |
| `getDeviceSharings` | POST | same | device sharing list | `js/agua/User.js:903-909` |
| `addDeviceSharing` | POST | same | share a device | `js/agua/User.js:944-953` |
| `revokeDeviceSharing` | POST | same | unshare | `js/agua/User.js:987-993` |
| `enableDeviceSharing` | POST | same | accept a sharing code | `js/agua/User.js:1027-1033` |
| `getEasyLifeAuthCode` | POST | same | third-party (EasyLife warranty) redirect URL; not app auth | `js/it.omniaweb.logic.js:3893-3903` |
| `userLostPasswordApp` | POST | same | start password reset | `js/agua/User.js:724-729` |
| `userChangeLostPasswordApp` | POST | same | complete password reset | `js/agua/User.js:746-755` |
| `generateAssistanceCode` | POST | same | create an assistance code | `js/agua/Device.js:1725-1735` |
| `generateAssistanceCode` | DELETE | same | remove an assistance code | `js/agua/Device.js:1749-1759` |
| `getAnnouncement` | POST | same | in-app announcements, body `client_id: "mypiazzetta"` | `js/it.omniaweb.logic.js:4129-4133` |
| `unsubscribeUser` | POST | same | delete the account (reached from the delete-user dialog) | `js/agua/User.js:641`, `js/it.omniaweb.logic.js:1016` |

Out of the bridge path, one plain GET is used as a reachability probe: `endpoint = host + '/' +
checkInternetConnection.endpoint` (`js/plugins/it.omniaweb.plugins.network.information.js:121`), where the second
operand is itself a full URL, `https://piazzetta-iot.app2cloud.it/firmware/agua.html`
(`js/it.omniaweb.config.js:85`) — so the literal request URL is
`https://piazzetta-iot.app2cloud.it/api/bridge/endpoint//https://piazzetta-iot.app2cloud.it/firmware/agua.html`
with a random `?t=` cache-buster appended (`js/plugins/it.omniaweb.plugins.network.information.js:60`). The
client only cares that it does not fail (`js/plugins/it.omniaweb.plugins.network.information.js:64-73`). Treat
this as a connectivity probe, not a protocol endpoint.

### 1.6 Response envelope and error handling

Success path (`js/agua/agua.js:969-985`):

- jQuery `success` (HTTP 2xx with JSON body) **and** `data.Success` truthy → promise resolves with the whole
  response object (`js/agua/agua.js:973-975`).
- HTTP 2xx but `data.Success` falsy → promise rejects with `data.Error`, else `data.Info`, else `false`
  (`js/agua/agua.js:983`).
- Response header `Blocked-Content` is read on every success and comma-split into a blocked-content list
  (`js/agua/agua.js:971-972`).

Transport-error path (`js/agua/agua.js:986-1066`) — requires `jqXHR.responseJSON.Error`:

| `Error` | Meaning (per comments/logic) | Client reaction | Citation |
|---|---|---|---|
| `1106` | user not authorized | if remote user: `refreshUserToken()` then retry the same call; on refresh failure `user.clearData()` + reject | `js/agua/agua.js:1002-1030` |
| `1116` | invalid refresh token | `user.clearData()` and restart app logic | `js/agua/agua.js:994-999` |
| `1144` | application not active | `activateApplication()` (`appSignup`) then retry | `js/agua/agua.js:1032-1056`, `js/agua/agua.js:677-687` |
| any other | — | reject with the raw code | `js/agua/agua.js:1058-1060` |
| no JSON error | — | reject `"0000"` | `js/agua/agua.js:1062-1065` |

Additional app-level code seen only in the UI layer:

| Code | Meaning | Citation |
|---|---|---|
| `1134` | login succeeded but the user account is not activated → show activation form | `js/it.omniaweb.logic.js:3297-3299` |
| `INVALID_APP_ERROR` | `getAppConfiguration` says this client version is too old → "upgrade app" screen | `js/it.omniaweb.logic.js:221-224` |

On `translateRegKeyList` specifically, a `1106` in the 2xx body additionally wipes `agua.token` and
`agua.refreshToken` (`js/agua/agua.js:978-982`).

Note the retry is recursive (`js/agua/agua.js:1013-1019`): the client re-invokes `invokeRemoteMethod` with the
same payload after a successful refresh, with no attempt counter — a permanent `1106` loops as long as the
refresh keeps succeeding.

---

## 2. Request / response examples (reconstructed from source)

### 2.1 Login

```
POST https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/
Content-Type: application/json; charset=utf-8
CustomerCode: 458632
IdBrand: 1
ApplicationVersion: 1.9.91
brand: piazzetta
authorization: 8c1f0d3c-2d3f-4a9c-9f7a-6b1e2f4a5c60
local: true
aguaid: null
userid: null
method: POST
url: userLogin
```

```json
{
  "email": "user@example.com",
  "password": "plaintext-password",
  "language": "it"
}
```

`email`/`password` come from `js/agua/User.js:445-446` (the form field is named `login-username`, i.e. the
email, `js/it.omniaweb.logic.js:3287`). `language` is injected for every non-GET call
(`js/agua/agua.js:922-925`); the language codes are the two-letter ones from
`js/it.omniaweb.config.js:69-81`.

Response — the client reads only `token` and `refresh_token` besides `Success`:

```json
{ "Success": true, "token": "<access token>", "refresh_token": "<refresh token>" }
```

`js/agua/User.js:454-458`. If `token` is absent the promise resolves with the raw response instead of starting
a session (`js/agua/User.js:462`).

### 2.2 User detail (immediately after login)

```
GET  (same URL, no body)
method: GET
url: userDetail
authorization: <access token>
local: false
aguaid: null      // still unset: the User record has not been read yet
userid: null
```

```json
{ "Success": true, "User": { "id": 12345, "agua_id": 678, "email": "user@example.com" } }
```

`js/agua/User.js:182-193`; the only three user fields the code reads are `id` (`js/agua/User.js:884`),
`agua_id` (`js/agua/User.js:870`) and `email` (`js/agua/User.js:796`). Note that they feed the *next*
request's `aguaid`/`userid` headers (`js/agua/agua.js:900-901`), so the first `userDetail` call after login
sends `null` for both.

### 2.3 Device list

```
POST (same URL)
method: POST
url: deviceList
authorization: <access token>
local: false
aguaid: 12345
userid: 678
```

```json
{ "language": "it" }
```

The call passes `null` as payload (`js/agua/User.js:230-234`); `$.extend(payload, {language: …})` at
`js/agua/agua.js:924` therefore yields `{"language":"it"}` (jQuery treats a null first argument as `{}` —
inference from jQuery semantics, not stated in source).

```json
{
  "Success": true,
  "device": [
    {
      "id": "12345",
      "id_device": "abcd",
      "id_product": "42",
      "id_client": "mypiazzetta",
      "mac": "AA:BB:CC:DD:EE:FF",
      "product_serial": "P123456",
      "reg_code": "1234",
      "name": "Stufa soggiorno",
      "is_managed": true,
      "product_info": { "registration_number": "P123456", "is_warranty_covered": true }
    }
  ]
}
```

Field names and their readers: `response.device` array (`js/agua/User.js:241`), `id` (`js/agua/Device.js:1778`),
`id_device` (`js/agua/Device.js:1806`), `id_product` (`js/agua/Device.js:1820`), `id_client`
(`js/agua/Device.js:1792`), `mac` (`js/agua/Device.js:1851`), `product_serial` (`js/agua/Device.js:1911`),
`reg_code` (`js/agua/Device.js:1925`), `name` (`js/agua/Device.js:1981`), `is_managed`
(`js/agua/Device.js:2843`), `product_info` (`js/agua/Device.js:77`), `product_info.registration_number` (`js/it.omniaweb.logic.js:3897`),
`product_info.is_warranty_covered` (`js/agua/Device.js:105`). Any field not listed is not read by the client.
Value types are not pinned by the JSON schema anywhere: `id` is compared with `===` against a DOM
`data-device-id` attribute (`js/agua/User.js:328`), so it is a string in practice; the example above uses
placeholder values.

### 2.4 Register map (first device refresh after the list)

```
POST (same URL)
method: POST
url: deviceGetRegistersMap
```

```json
{ "id_product": "42", "id_device": "abcd", "last_update": null, "registration_number": "P123456", "language": "it" }
```

`js/agua/Device.js:2516-2521` plus injected `language` (`js/agua/agua.js:924`).
Response: `{ "Success": true, "device_registers_map": { "registers_map": [ … ] } }`
(`js/agua/Device.js:2532-2534`). `registration_number` is just the serial number
(`js/agua/Device.js:2827-2830`).

### 2.5 Token refresh

```json
{ "refresh_token": "<refresh token>", "language": "it" }
```

`js/agua/User.js:556-561` + `js/agua/agua.js:924`. Response:

```json
{ "Success": true, "token": "<new access token>" }
```

`js/agua/User.js:566-569`. The client stores only the new `token`; it never updates
`agua.refreshToken` from the refresh response (`js/agua/User.js:568`) — whether the server rotates the refresh
token is `NOT FOUND`.

### 2.6 Logout

```json
{ "refresh_token": "<refresh token>", "language": "it" }
```

`js/agua/User.js:596-602`. Response is only checked for `Success` (`js/agua/User.js:607`); local state is
cleared in **both** the success and the failure path (`js/agua/User.js:609-619`).

### 2.7 App installation signup (`appSignup`)

```json
{
  "language": "it",
  "id_app": "<uuid>",
  "push_notification_token": "<fcm token or ''>",
  "push_notification_active": true,
  "phone_id": "<device uuid>",
  "phone_type": "android",
  "phone_version": "<os version>"
}
```

`js/agua/agua.js:704-719`; the response's `id_app` is persisted back as `agua.uuid`
(`js/agua/agua.js:729-733`). This is called on startup when `agua.activated` is empty
(`js/it.omniaweb.logic.js:274-282`) and again automatically on error `1144`
(`js/agua/agua.js:1033-1056`).

### 2.8 Error responses

HTTP 2xx with a falsy `Success` and an error code (`js/agua/agua.js:983`):

```json
{ "Success": false, "Error": "1134" }
```

HTTP 4xx/5xx carrying the same envelope in `responseJSON` (`js/agua/agua.js:987-989`):

```json
{ "Error": "1106" }
```

Which HTTP status the server uses for which condition is `NOT FOUND` — the client only distinguishes "jQuery
success" from "jQuery error" and never reads `jqXHR.status`.

---

## 3. Call sequence: login → device list

1. **App start.** `runApplication()` runs `checkBleStatus → activateApplication → … → getAppConfiguration`
   (`js/it.omniaweb.logic.js:174-199`).
2. **Installation signup, once.** If `agua.activated` is empty, `appSignup` is POSTed with the push token and
   phone ids, and the returned `id_app` is stored as `agua.uuid`
   (`js/it.omniaweb.logic.js:274-282`, `js/agua/agua.js:677-742`). Error `1144` from any later call repeats this.
3. **Login gate.** Any view other than the allow-list requires a remote session: `isLogged()` →
   `agua.logged` flag, else a `userDetail` probe; on failure the app shows the start screen / login form
   (`js/it.omniaweb.logic.js:731-796`, `js/agua/User.js:146-160`).
4. **Login submit.** The form fields `login-username` / `login-password` are passed to `User.login(email, password)`
   (`js/it.omniaweb.logic.js:3275-3293`).
5. **`userLogin` POST** with headers `CustomerCode/IdBrand/ApplicationVersion/brand/authorization(=uuid)/local=true/
   aguaid=null/userid=null/method=POST/url=userLogin` and body `{email, password, language}`
   (`js/agua/agua.js:891-903`, `js/agua/agua.js:938-956`, `js/agua/User.js:443-448`).
6. **Persist session.** `token`, `refresh_token`, `logged=true` are written to `localStorage`, and the singleton
   user object becomes the current user (`js/agua/User.js:454-460`).
7. **`userDetail` GET** loads the user record into `agua.user` (`js/agua/User.js:171-203`, `js/agua/User.js:184`).
8. **Navigate to the devices list** with `{refresh:true, now:true}` (`js/it.omniaweb.logic.js:3291-3293`).
9. **Online check** — `networkInformation.isOnline(true)`; offline falls back to the `devices.json` cache
   (`js/agua/User.js:219-224`, `js/agua/User.js:266-305`).
10. **`deviceList` POST** with `authorization=<token>`, `local=false`, `aguaid=<User.id>`, `userid=<User.agua_id>`,
    body `{"language":"it"}` (`js/agua/User.js:230-235`, `js/agua/agua.js:891-903`).
11. **Build devices.** `response.device` → `Device` objects, sorted by name, cached to `devices.json`
    (`js/agua/User.js:239-259`).
12. **First device read.** Each device is refreshed later via `deviceGetRegistersMap` and
    `deviceGetBufferReading`/`deviceJobStatus` (`js/agua/Device.js:2513-2524`, `js/agua/Device.js:2565-2578`,
    `js/agua/Device.js:2628`).
13. **Any call returning `1106`** triggers `refreshToken` and a replay of the original action
    (`js/agua/agua.js:1002-1030`, `js/agua/User.js:548-578`); `1116` clears everything and restarts at step 1's
    UI gate (`js/agua/agua.js:994-999`).
14. **Logout** posts `userLogout` with the refresh token and clears `agua.token`, `agua.refreshToken`,
    `agua.logged`, `agua.user` (`js/agua/User.js:589-624`, `js/agua/User.js:698-710`).

---

## 4. Kotlin-friendly field list

```kotlin
// ---- transport / brand ----
object BrandConfig {
    const val BASE_URL = "https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/" // config.js:48
    const val CUSTOMER_CODE = "458632"   // config.js:47  -> header CustomerCode
    const val ID_BRAND = "1"             // config.js:46  -> header IdBrand
    const val BRAND = "piazzetta"        // config.js:44  -> header brand
    const val APP_VERSION = "1.9.91"     // config.js:39  -> header ApplicationVersion
    const val CLIENT_ID = "mypiazzetta"  // config.js:45  -> consent payloads only
}
```

Per call, the request carries (agua.js:891-903, 955-956):

| Header | Kotlin type | Source |
|---|---|---|
| `CustomerCode` | `String` | brand code — `js/agua/agua.js:894` |
| `IdBrand` | `String` | brand id — `js/agua/agua.js:895` |
| `ApplicationVersion` | `String` | app version — `js/agua/agua.js:896` |
| `authorization` | `String` | token, else installation UUID — `js/agua/agua.js:897`, `js/agua/User.js:125-135` |
| `local` | `Boolean` (serialized `"true"`/`"false"`) | token absent — `js/agua/agua.js:898`, `js/agua/User.js:77-83` |
| `brand` | `String` | brand slug — `js/agua/agua.js:899` |
| `aguaid` | `String?` | `User.id` — `js/agua/agua.js:900`, `js/agua/User.js:884` |
| `userid` | `String?` | `User.agua_id` — `js/agua/agua.js:901`, `js/agua/User.js:870` |
| `method` | `String` | HTTP verb — `js/agua/agua.js:955` |
| `url` | `String` | action name (+ optional `/458632`) — `js/agua/agua.js:956` |

Wire DTOs (field names are exactly the JSON keys):

```kotlin
@Serializable
data class BridgeResponse(
    @SerialName("Success") val success: Boolean,        // agua.js:973
    @SerialName("Error")   val error: String? = null,   // agua.js:983, 989
    @SerialName("Info")    val info: String? = null,    // agua.js:983
)

@Serializable
data class LoginRequest(                                // User.js:445-446 + agua.js:924
    val email: String,
    val password: String,
    val language: String,
)

@Serializable
data class LoginResponse(
    @SerialName("Success") val success: Boolean,
    val token: String? = null,                          // User.js:454
    @SerialName("refresh_token") val refreshToken: String? = null, // User.js:457
    @SerialName("Error") val error: String? = null,
    @SerialName("Info") val info: String? = null,
)

@Serializable
data class UserDto(
    val id: String? = null,                             // User.js:884  -> aguaid header (numeric type not pinned)
    @SerialName("agua_id") val aguaId: String? = null,  // User.js:870  -> userid header (numeric type not pinned)
    val email: String? = null,                          // User.js:796
)

@Serializable
data class UserDetailResponse(
    @SerialName("Success") val success: Boolean,
    @SerialName("User") val user: UserDto? = null,      // User.js:193
)

@Serializable
data class DeviceListResponse(
    @SerialName("Success") val success: Boolean,
    val device: List<DeviceDto> = emptyList(),          // User.js:241
)

@Serializable
data class ProductInfoDto(
    @SerialName("registration_number") val registrationNumber: String? = null, // logic.js:3897
    @SerialName("is_warranty_covered") val isWarrantyCovered: Boolean? = null,  // Device.js:105
)

@Serializable
data class DeviceDto(
    val id: String? = null,                             // Device.js:1778
    @SerialName("id_device") val idDevice: String? = null,     // Device.js:1806
    @SerialName("id_product") val idProduct: String? = null,   // Device.js:1820
    @SerialName("id_client") val idClient: String? = null,     // Device.js:1792
    val mac: String? = null,                            // Device.js:1851
    @SerialName("product_serial") val productSerial: String? = null, // Device.js:1911 (== registration_number)
    @SerialName("reg_code") val regCode: String? = null,       // Device.js:1925
    val name: String? = null,                           // Device.js:1981
    @SerialName("is_managed") val isManaged: Boolean? = null,  // Device.js:2843
    @SerialName("product_info") val productInfo: ProductInfoDto? = null, // Device.js:77
)

@Serializable
data class RefreshTokenRequest(                         // User.js:557-560
    @SerialName("refresh_token") val refreshToken: String,
    val language: String,
)

@Serializable
data class RefreshTokenResponse(
    @SerialName("Success") val success: Boolean,
    val token: String? = null,                          // User.js:568
)

@Serializable
data class LogoutRequest(                               // User.js:596-601
    @SerialName("refresh_token") val refreshToken: String,
    val language: String,
)

@Serializable
data class AppSignupRequest(                            // agua.js:704-719
    val language: String,
    @SerialName("id_app") val idApp: String,
    @SerialName("push_notification_token") val pushNotificationToken: String,
    @SerialName("push_notification_active") val pushNotificationActive: Boolean,
    @SerialName("phone_id") val phoneId: String,
    @SerialName("phone_type") val phoneType: String,     // "android" | "desktop"
    @SerialName("phone_version") val phoneVersion: String,
)

@Serializable
data class RegistersMapRequest(                         // Device.js:2516-2521
    @SerialName("id_product") val idProduct: String?,
    @SerialName("id_device") val idDevice: String?,
    @SerialName("last_update") val lastUpdate: String?,
    @SerialName("registration_number") val registrationNumber: String?,
    val language: String,
)
```

Session state to persist (keys are literal, `js/agua/User.js:456-458`, `js/agua/User.js:700-703`,
`js/agua/User.js:112`): `agua.token`, `agua.refreshToken`, `agua.logged`, `agua.user`, `agua.uuid`,
`agua.activated`.

---

## 5. Open questions — NOT FOUND

1. **Token format / expiry.** The token is never split, decoded, or given a TTL in the client; expiry is only
   discovered reactively via error `1106` (`js/agua/agua.js:1002`). Whether it is a JWT: `NOT FOUND`.
2. **Refresh-token rotation.** `refreshToken` response handling stores only `token`
   (`js/agua/User.js:568`); whether the server also issues a new refresh token is `NOT FOUND`.
3. **HTTP status codes per error.** The client never inspects `jqXHR.status` (`js/agua/agua.js:986-1066`);
   which codes arrive as 200-with-`Success:false` versus 4xx is `NOT FOUND`.
4. **Other error codes.** Only `1106`, `1116`, `1144`, `1134` and `INVALID_APP_ERROR` are named anywhere
   (`js/agua/agua.js:995-1060`, `js/it.omniaweb.logic.js:221`, `js/it.omniaweb.logic.js:3297`). The full error
   catalogue is `NOT FOUND`.
5. **Server-side routes.** Whether `/api/bridge/endpoint/` is the only route, or an alias for
   `?action=`/POST routing, is `NOT FOUND` — the client always calls that one URL
   (`js/agua/agua.js:941`).
6. **Full `User` object shape.** The client reads only `id`, `agua_id`, `email`
   (`js/agua/User.js:870`, `js/agua/User.js:884`, `js/agua/User.js:796`); name/surname/phone/etc are
   `NOT FOUND`.
7. **Full `deviceList` device shape.** Only the fields in section 4 are read; registers, firmware info, and
   status fields arrive via `deviceGetRegistersMap` and are outside this document's scope — their full schema is
   `NOT FOUND`.
8. **`Blocked-Content` semantics.** The header is comma-split into a list
   (`js/agua/agua.js:971-972`) and passed to `logic.setBlockedContent`; what the values mean is `NOT FOUND`.
9. **API key / signature.** No API key, HMAC, nonce, or request signature exists in the shipped JS
   (no match for `api_key|apikey|hmac|signature|md5|sha1|sha256` outside `libs/` and UI strings). If the server
   enforces one, it is `NOT FOUND`.
10. **Local (pre-login) authorization acceptance.** The client sends `authorization: <uuid>` with `local: true`
    when no token exists (`js/agua/User.js:127-129`, `js/agua/agua.js:898`), and `deviceList` is attempted in
    that state (`js/it.omniaweb.logic.js:663`), but whether the server accepts it is `NOT FOUND`.
11. **TLS pinning / certificate rules.** No pinning code exists in the JS bundle; whether the native Android layer
    pins certificates is `NOT FOUND` (outside `www/`).
12. **`clientId` usage in auth.** `"mypiazzetta"` appears as `client_id` only in the consent calls
    (`js/it.omniaweb.logic.js:607`, `js/it.omniaweb.logic.js:3684`) and as `client_id: brand.slug` in
    `getAnnouncement` (`js/it.omniaweb.logic.js:4132`), never in a login or session call; any other role is
    `NOT FOUND`.
