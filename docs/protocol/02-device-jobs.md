# 02 — Device jobs: how MyPiazzetta reads and writes stove data through the Agua bridge

Reverse-engineered from the unminified production bundle of **MyPiazzetta 1.9.91**
(`it.omniaweb.config.js:39`) shipped inside the APK. Everything below is cited as
`<file>:<line>` relative to the read-only source root:

```
SRC = docs/protocol/source-snapshot
```

Absolute paths for the citations used most often:

| Citation prefix | Absolute path |
|---|---|
| `js/agua/Device.js` | `SRC/js/agua/Device.js` |
| `js/agua/agua.js` | `SRC/js/agua/agua.js` |
| `js/agua/User.js` | `SRC/js/agua/User.js` |
| `js/agua/Register.js` | `SRC/js/agua/Register.js` |
| `js/agua/RegisterData.js` | `SRC/js/agua/RegisterData.js` |
| `js/it.omniaweb.logic.js` | `SRC/js/it.omniaweb.logic.js` |
| `js/it.omniaweb.widgets.js` | `SRC/js/it.omniaweb.widgets.js` |
| `js/it.omniaweb.config.js` | `SRC/js/it.omniaweb.config.js` |

Where the source does not contain a fact, it is marked **NOT FOUND** — nothing here is
inferred protocol detail presented as fact.

---

## 0. Transport envelope (must be understood before the action table)

The bridge is a **single POST/GET endpoint with the operation name carried in a request
header**, not in the path.

| Fact | Evidence |
|---|---|
| Base URL `https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/` | `js/it.omniaweb.config.js:48` |
| Brand `clientId` = `mypiazzetta` | `js/it.omniaweb.config.js:45` |
| Brand `code` = `458632` (sent as `CustomerCode`) | `js/it.omniaweb.config.js:47` |
| Brand `id` = `1` (sent as `IdBrand`) | `js/it.omniaweb.config.js:46` |
| App version `1.9.91` (sent as `ApplicationVersion`) | `js/it.omniaweb.config.js:39` |
| HTTP verbs available: POST / GET / DELETE / PUT | `js/agua/agua.js:32` |
| Every call is `$.ajax` to the **brand host only** | `js/agua/agua.js:941` |
| Operation name is sent in header `url` = `action + affix` | `js/agua/agua.js:956` |
| Verb is sent in header `method` | `js/agua/agua.js:955` |
| Body is JSON of the payload; **GET sends no body at all** | `js/agua/agua.js:946` |
| `language` is merged into every non-GET payload | `js/agua/agua.js:924` |
| No `timeout` option is set on the ajax call (no client-side HTTP timeout) | ajax block `js/agua/agua.js:938-962` (no `timeout:` key present) |

Identity headers, added to every request (`js/agua/agua.js:891-901`):

```
CustomerCode: "458632"      js/agua/agua.js:894
IdBrand:      1             js/agua/agua.js:895
ApplicationVersion: "1.9.91" js/agua/agua.js:896
authorization: <token>      js/agua/agua.js:897
local: <bool>               js/agua/agua.js:898
brand: "piazzetta"          js/agua/agua.js:899
aguaid: <agua id>           js/agua/agua.js:900
userid: <agua user id>      js/agua/agua.js:901
```

**Response envelope.** Success is decided *only* by the `Success` field of the JSON body
(`js/agua/agua.js:973`). On `Success` the whole body is resolved. On non-success the promise
is rejected with `Error`, else `Info`, else `false` (`js/agua/agua.js:983`). Transport
errors carry `responseJSON.Error`; three codes get special handling — `1116` clears the user
and restarts the app (`js/agua/agua.js:995`), `1106` refreshes the token and replays the
request once (`js/agua/agua.js:1002`), `1144` re-activates the app and replays once
(`js/agua/agua.js:1033`). Anything else rejects with the raw code
(`js/agua/agua.js:1058-1060`); a body without an `Error` rejects with `"0000"`
(`js/agua/agua.js:1064`).

**Device-method dispatch.** All device calls go through `invokeDeviceMethod`
(`js/agua/agua.js:492`), which routes to BLE when bluetooth mode is on and to the cloud
bridge otherwise (`js/agua/agua.js:495-503`). This document covers **only the cloud path**.

---

## 1. Action table

All actions below are the literal strings passed as the first argument of
`invokeRemoteMethod` / `invokeDeviceMethod`. Payloads are the JSON body; `language` is
implicitly added to every non-GET payload (`js/agua/agua.js:924`) and is omitted from the
tables below.

### 1.1 Device data plane (the jobs)

| Action | Verb / affix | Request payload | Response fields read | Evidence |
|---|---|---|---|---|
| `deviceList` | POST, no affix | *(none; body is just `{language}`)* | `Success`, `device[]` (array of device objects, each mapped into a `Device`) | call `js/agua/User.js:232-234`; mapping `js/agua/User.js:241-247` |
| `deviceGetRegistersMap` | POST, no affix | `id_product`, `id_device`, `last_update` (nullable delta token), `registration_number` | `Success`, `device_registers_map.registers_map[0]` (nullable → keep cache) | payload `js/agua/Device.js:2515-2521`; response `js/agua/Device.js:2530-2536` |
| `deviceGetBufferReading` | POST, no affix | `id_product`, `id_device`, `BufferId` (`defaultBufferId` = 1), `registration_number` | `Success`, `idRequest` (job ticket) **or** `mt` + `pl` (immediate answer) | payload `js/agua/Device.js:2568-2574`; response `js/agua/Device.js:2583-2591`; buffer id `js/it.omniaweb.config.js:87` |
| `deviceRequestWriting` | POST, no affix | `id_product`, `id_device`, `Protocol:"RWMSmaster"`, `BitData:8`, `Endianess:"L"`, `registration_number`, then the register payload `Items[]`, `Masks[]`, `Values[]`, `Endianess[]`, `BitData[]` | `Success`, `idRequest` (job ticket) **or** `mt` + `pl` | payload `js/agua/Device.js:2374-2387`; actual arrays from `RegisterData.getData()` `js/agua/RegisterData.js:180-188`; response `js/agua/Device.js:2396-2405` |
| `deviceJobStatus` | **GET**, affix `/<idRequest>` | none (GET body is `null`) | `jobAnswerStatus` (`waiting` \| `missing job answer` \| `completed` \| `terminated`), `jobAnswerData` = `{Items[], Values[]}` | call `js/agua/Device.js:2625-2632`; status switch `js/agua/Device.js:2637-2660` |
| `deviceUpdate` | POST, no affix | caller-supplied object (rename/description edit) | `Success` | `js/agua/Device.js:534` |
| `deviceUnRegister` | POST, no affix | `id_product`, `id_device`, `registration_number` | `Success` | `js/agua/Device.js:502-508` |
| `updateLastRequestDuration` | POST, no affix | `duration`, `result` (bool), `id_device`, `id_product`, `registration_number` | none read (telemetry) | `js/agua/Device.js:266-274`; fired after every read `js/agua/Device.js:348`, `js/agua/Device.js:424` |

### 1.2 Device lookup / registration

| Action | Verb | Request payload | Response fields | Evidence |
|---|---|---|---|---|
| `deviceInfoFromSerialnumber` | POST | `product_serial` | `Success`, `device_product[]` (last element wrapped as a `Device`) | `js/agua/agua.js:375-386` |
| `deviceInfoFromMac` | POST | `mac` | `Success`, `device_product[]` | `js/agua/agua.js:413-424` |
| `deviceRegister` | POST | `mac`, `reg_code`, `cod_art`, `product_serial`, `name`, `description`, `country`, `state`, `city`, `autorization_it` | `Success` | `js/agua/User.js:355-371`; brand override `js/agua/User.js:856` |
| `deviceRegisterListWithUser` | POST | `id_app`, `deviceList[]` (raw device data objects) | `Success` | `js/agua/User.js:671-686` |

### 1.3 Session / config (needed because they gate the device calls)

| Action | Verb | Request payload | Response fields | Evidence |
|---|---|---|---|---|
| `userLogin` | POST | `email`, `password` | `token`, `refresh_token` (stored locally) | `js/agua/User.js:440-458` |
| `userDetail` | GET | none | `Success`, `User` | `js/agua/User.js:183-193` |
| `userLogout` | POST | — | — | `js/agua/User.js:598` |
| `userSignup` / `userActivate` / `userLostPasswordApp` / `userChangeLostPasswordApp` | POST | — | — | `js/agua/User.js:416`, `:495`, `:726`, `:748` |
| `appSignup` | POST | `language`, `id_app`, `push_notification_token`, `push_notification_active`, `phone_id`, `phone_type`, `phone_version` | `Success`, `id_app` | `js/agua/agua.js:705-730` |
| `getAppConfiguration` | POST | `language` (explicitly re-sent) | `configuration` (server-side overrides of the poll/refresh knobs) | `js/it.omniaweb.logic.js:210-217` |
| `translateRegKeyList` | POST | `lang`, `lang_two_letter`, `update_date` | `languages.languages[0].registers[]` (`reg_key`, `reg_name`, `reg_name_app`) | call `js/agua/agua.js:792-798`; response read `js/agua/agua.js:805-807`; parse `js/agua/agua.js:831-856` |
| `setClientConsents` | POST | `client_id` (`"mypiazzetta"`), `consents{}` | — | `js/it.omniaweb.logic.js:3680-3687` |

### 1.4 Job ticket mechanism

A job ticket is the opaque **`idRequest`** string returned by `deviceGetBufferReading`
(`js/agua/Device.js:2585`) or `deviceRequestWriting` (`js/agua/Device.js:2398`). It is
stored on the device instance in `this.idRequest` (`js/agua/Device.js:24`, written at
`js/agua/Device.js:2398` and `js/agua/Device.js:2585`) and is then polled through
`deviceJobStatus` with the ticket as a **URL affix** (`js/agua/Device.js:2628-2632`), which
the transport turns into the header `url: "deviceJobStatus/<ticket>"`
(`js/agua/agua.js:956`). Both job-creating calls first reset the field to `null`
(`js/agua/Device.js:2366`, `js/agua/Device.js:2560`).

**There is exactly one `idRequest` per device instance.** Nothing namespaces the ticket per
operation — see §4.4.

**Immediate-answer shortcut.** If the bridge already has the stove's reply, it returns
`mt` (message type) plus `pl` (payload) instead of a ticket. `mt !== 'N'` means "the device
answered" and `pl` is `.` — no job is created; `mt === 'N'` is the device-protocol NACK
(`js/it.omniaweb.bluetoothdevice.js:275`) and is treated as failure with code `"0000"`
(`js/agua/Device.js:2401-2405`, `js/agua/Device.js:2588-2592`).

---

## 2. The full write-then-verify sequence as implemented

### 2.1 Register value → HTTP call

1. UI: the settings modal's *Save* button (`js/it.omniaweb.widgets.js:1401-1403`) calls
   `registerUpdate()`, which calls `register.update()` (`js/it.omniaweb.widgets.js:1382-1391`).
2. `Register.prototype.update` builds `RegisterData` and calls
   `getDevice().update( ...getData() )` (`js/agua/Register.js:542-551`); the data object is
   `{Items, Masks, Values, Endianess, BitData}` (`js/agua/RegisterData.js:180-188`).
   For the "fan speed" class of control the register is a plain SET register selected by
   group key out of the fetched map (`js/agua/Device.js:574-581`); the literal fan/ventilation
   register key is **NOT FOUND** hard-coded in the app — it exists only in the
   server-delivered register map (`js/agua/Device.js:2530-2536`).
3. `Device.prototype.update` (`js/agua/Device.js:132`) sets `updating = true`
   (`js/agua/Device.js:134`), ensures the register map is loaded
   (`js/agua/Device.js:140-147`, map fetch at `js/agua/Device.js:2424-2443`) and calls
   `_update(data, true)` (`js/agua/Device.js:145`).
4. `_update` POSTs `deviceRequestWriting` with the six fixed fields plus the register arrays
   (`js/agua/Device.js:2371-2387`).
5. On `response.Success` the ticket is stored and the ticket string is resolved
   (`js/agua/Device.js:2396-2399`). On failure, if the bridge returned an immediate device
   answer (`mt` set and not `'N'`) the **answer payload `pl` is resolved instead of a
   ticket** (`js/agua/Device.js:2401-2403`), otherwise `reject("0000")`
   (`js/agua/Device.js:2405`).

### 2.2 What `Device.update` does with the ticket — the critical 4 lines

```js
152:  if( typeof response === 'string' )
153:  {
154:      reference.idRequest = response;
155:      reference._getJobStatus( true );      // <-- started, never awaited, never .catch()-ed
156:      resolve( true );                      // <-- caller is told "success" right here
157:  } else {
```

`js/agua/Device.js:152-157`. The `// <--` annotations are mine; the code text and line
numbers are verbatim. The job-status promise returned on line 155 is **discarded**:
no `.then`, no `catch`, not returned. The caller's promise resolves `true` one statement
later, before any poll has happened.

Contrast with the read paths, which *do* await:

* `refresh()` returns `reference._getJobStatus( showLoader !== true )` inside its chain
  (`js/agua/Device.js:306-313`).
* `getInfo()` likewise returns `reference._getJobStatus( true )`
  (`js/agua/Device.js:384-393`).

### 2.3 Job polling (`_getJobStatus`)

`js/agua/Device.js:2612-2672`:

1. Reject immediately if `idRequest === null` (`js/agua/Device.js:2614-2617`).
2. GET `deviceJobStatus` with affix `'/' + reference.idRequest` — **read from the live field
   on every tick** (`js/agua/Device.js:2631`).
3. Empty `jobAnswerStatus` → `reject("0000")` (`js/agua/Device.js:2637-2639`).
4. `terminated` → `reject("0000")` (`js/agua/Device.js:2643-2645`).
5. `waiting` / `missing job answer` → `setTimeout(polling, statusPollingFrequency|300)`
   (`js/agua/Device.js:2647-2650`). **No attempt counter, no deadline.**
6. `completed` → `_parseRegistersValues( jobAnswerData )`; `true` resolves, anything else
   rejects (`js/agua/Device.js:2652-2659`).
7. Any other status string → **no branch matches**, the chain simply ends and the promise
   never settles (`js/agua/Device.js:2641-2660`).

### 2.4 "Parsing" is not verification

`_parseRegistersValues` (`js/agua/Device.js:2684-2714`):

* No `Items`/`Values` in the answer → returns `true` (**empty answer = success**)
  (`js/agua/Device.js:2686-2689`).
* `Values` empty → `false` (`js/agua/Device.js:2695-2698`).
* All values equal to `inactivityCode` → `Device.INACTIVITY_ERROR`
  (`js/agua/Device.js:2699-2701`, check at `js/agua/Device.js:2726-2741`).
* Otherwise it **writes the returned numbers into the registers map by offset** and returns
  `true` (`js/agua/Device.js:2703-2713`).

Nowhere does it compare the returned values with the `Values[]` that were sent in
`deviceRequestWriting` (`js/agua/Device.js:2386`). "Success" therefore means *the job
reached `completed` with a non-empty payload*, not *the fan speed now equals what I asked*.

### 2.5 What the UI does with that "success"

* `register.update()` dispatches the register's `updated` signal and resolves
  (`js/agua/Register.js:551-558`).
* The modal's `registerUpdate` resolves → `.finally` closes the modal
  (`js/it.omniaweb.widgets.js:1384-1390`); the modal is only closed with an error path if
  the *immediate* promise rejected (`js/it.omniaweb.widgets.js:1386-1389`).
* Closing the modal restarts auto-refresh at `refreshDeviceInterval` (3000 ms)
  (`js/it.omniaweb.logic.js:1809-1853`, restart at `js/it.omniaweb.logic.js:1852`;
  default from `js/it.omniaweb.widgets.js:518`).
* That refresh is the **only** thing that will ever show the true stove value — 3 s later, in
  the background, with no comparison and no user-visible outcome.

### 2.6 Sequence, as implemented

```
UI save            widgets.js:1384        register.update()
                   Register.js:551        device.update({Items,Masks,Values,Endianess,BitData})
                   Device.js:145          _update(data, true)
                   Device.js:2374         POST deviceRequestWriting  ->  {Success, idRequest}
                   Device.js:2398         this.idRequest = idRequest
                   Device.js:155          _getJobStatus(true)        [detached promise]
                   Device.js:156          resolve(true)              [UI sees "saved"]
                   widgets.js:1390        modal closes
                   logic.js:1852          initAutorefresh(3000)      [3 s later: a read]
  (detached)       Device.js:2628         GET deviceJobStatus/<idRequest>
                   Device.js:2647-2649    repeat every 300 ms while waiting/missing job answer
                   Device.js:2652-2659    completed -> parse -> resolve/reject  [nobody listens]
                   Device.js:2643-2645    terminated -> reject("0000")          [nobody listens]
```

---

## 3. Poll / backoff / timeout constants

| Constant | Value | Where | Notes |
|---|---|---|---|
| Job-status poll interval | `statusPollingFrequency`, default **300 ms** | `js/agua/Device.js:2649` | Fixed, no backoff. Server may override via `configuration` (`js/it.omniaweb.logic.js:234-237`, populated at `js/it.omniaweb.logic.js:217`) |
| Device auto-refresh interval | `refreshDeviceInterval`, default **3000 ms** | `js/it.omniaweb.widgets.js:518`, `js/it.omniaweb.views.js:2034`, `js/it.omniaweb.logic.js:1852` | Same server-override mechanism |
| Max job-status attempts | **NOT FOUND** | — | No counter anywhere in `_getJobStatus` (`js/agua/Device.js:2612-2672`) |
| Job overall deadline / timeout | **NOT FOUND** | — | No timer guards the poll loop (`js/agua/Device.js:2647-2650`) |
| HTTP request timeout | **NOT FOUND** | ajax block `js/agua/agua.js:938-962` | No `timeout:` option; a hung bridge hangs the promise forever |
| Retry of a failed write | **NOT FOUND** | grep `retry|Retry|tentativ` over `SRC/js` → 0 hits | Writes are never re-sent |
| Retry of a failed read | **NOT FOUND** | — | The next `refresh` tick is the only "retry" (`js/agua/Device.js:205-233`) |
| Buffer id (`BufferId` on reads) | `defaultBufferId` = **1** | `js/it.omniaweb.config.js:87` | Sent on every `deviceGetBufferReading` (`js/agua/Device.js:2572`) |
| Register-map cache | in-memory + per-MAC JSON file, refreshed only with `last_update` | `js/agua/Device.js:2424-2445`, `js/agua/Device.js:2473`, `js/agua/Device.js:2753-2783` | `device_registers_map: null` means "unchanged" (`js/agua/Device.js:2532-2535`) |
| Connectivity poll | `checkInternetConnection.dataInterval` = **10000 ms** | `js/it.omniaweb.config.js:83`, used at `js/plugins/it.omniaweb.plugins.network.information.js:109` | Connectivity only, not a device poll |
| Rate limiting | **NOT FOUND** | — | No client-side throttle, no 429 handling. The only server feedback channel found is the `Blocked-Content` response header (`js/agua/agua.js:971-972`), which feeds feature gating (`js/it.omniaweb.logic.js:4088-4092`) |

---

## 4. Why it silently fails

The user complaint — *"I set the fan speed and it is not applied, with no error"* — is
explained by the source in five independent ways. Any one of them alone is enough to
produce it.

### 4.1 The write's result is never awaited, and its rejection has no consumer

`js/agua/Device.js:152-156`: the job status is started as a bare expression statement
(line 155) and the caller is resolved `true` on the next line. There is no `.catch` on that
promise, so **every** terminal outcome of the write job — `terminated`, empty status,
parse failure, `INACTIVITY_ERROR` — becomes an unhandled rejection. Nothing in the UI
subscribes to it. The save dialog has already closed by then
(`js/it.omniaweb.widgets.js:1388-1390`).

This is asymmetric with reads on purpose-looking code: `refresh()` returns the job-status
promise and *does* surface failures (`js/agua/Device.js:306-313`,
`js/agua/Device.js:343-345`), and `getInfo()` does the same
(`js/agua/Device.js:391-393`). The write path simply does not.

### 4.2 "Completed" is treated as "applied"

`_parseRegistersValues` writes the stove's echoed numbers into the register map and returns
`true` without comparing them to the requested `Values`
(`js/agua/Device.js:2703-2713` vs the sent payload at `js/agua/Device.js:2386`). A stove that
clamps the fan speed, ignores it because of an operating state, or answers with the previous
value produces exactly the same `true` as a successful set.

### 4.3 Empty or missing job answers count as success

`js/agua/Device.js:2686-2689` returns `true` when the payload has no `Items`/`Values`. A
`completed` job whose `jobAnswerData` is blank therefore reports success with nothing applied
and nothing checked.

### 4.4 The ticket is a single shared field, so the poller can be re-pointed mid-flight

`_getJobStatus` reads `reference.idRequest` fresh on **every** poll tick
(`js/agua/Device.js:2631`), while `_getReadBufferId` clears it
(`js/agua/Device.js:2560`) and then stores the *read* job id (`js/agua/Device.js:2585`), and
`_update` clears it too (`js/agua/Device.js:2366`). Because the modal close restarts
auto-refresh (`js/it.omniaweb.logic.js:1852`), the very next read (3 s later, or sooner if a
screen also refreshes) can overwrite the id while the write poller is still in its 300 ms
loop. From then on the write poller polls the read job's ticket — and when that read job
completes it parses the read payload, resolves, and nobody is any the wiser. If the field is
`null` at that instant, the poller requests `deviceJobStatus/null`
(`js/agua/Device.js:2631`).

### 4.5 Unknown job statuses hang forever; there is no deadline

Only `waiting`, `missing job answer`, `completed`, `terminated` are handled
(`js/agua/Device.js:2641-2660`). Any other string ends the polling chain with the promise
neither resolved nor rejected. And for `waiting` / `missing job answer` the loop is
unbounded (`js/agua/Device.js:2647-2649`) with no HTTP timeout
(`js/agua/agua.js:938-962`) — a stove that never answers polls forever, silently. The app
has no code path that ever tells the user "the command was not confirmed".

### 4.6 The only failure the user *can* see is the read-side one

The stove-not-answering case is detected by the **read** path, not the write path: all
returned values equal to `inactivityCode` = **32768**
(`js/it.omniaweb.config.js:58`, check at `js/agua/Device.js:2726-2741`) makes
`_parseRegistersValues` return `Device.INACTIVITY_ERROR` (`js/agua/Device.js:2699-2701`,
constant at `js/agua/Device.js:44`), which surfaces as the inactivity view
(`js/it.omniaweb.logic.js:2089-2091`) or via the `inactivity` signal dispatched on refresh
failure (`js/agua/Device.js:343`) → `onDeviceInactivity`
(`js/it.omniaweb.logic.js:1128`, `js/it.omniaweb.logic.js:2128-2131`). So the app *can* say
"stove unreachable" — but only on a refresh, never as the outcome of a write. When the next
refresh happens to succeed (stove awake, stale register map), the fan speed merely reverts
and no message is ever shown.

**Summary of the root cause:** the write is a *fire-and-forget ticket* whose ticket is
polled in a detached, uncaught promise, and whose `completed` state is accepted without
comparing the applied value to the requested value. There is no retry, no deadline, and no
read-back verification.

---

## 5. Kotlin reimplementation: confirm-with-retry design notes

Goal: never resolve "applied" from a ticket, only from an observation.

### 5.1 Transport layer

* One `BridgeClient.call(action, payload, method, affix)` that reproduces the header-carried
  protocol: `url = action + affix` (`js/agua/agua.js:956`), `method` header
  (`js/agua/agua.js:955`), JSON body for non-GET, no body for GET
  (`js/agua/agua.js:946`), plus the identity headers (`js/agua/agua.js:894-901`).
* Unlike the original, give every request a per-call `HttpTimeout` (e.g. 10 s connect +
  20 s read) — the JS app has none (`js/agua/agua.js:938-962`).
* Treat `Success == false` as a typed error carrying `Error`/`Info`
  (mirror `js/agua/agua.js:983`), and keep the `1106 → refresh token → replay once` and
  `1144 → activate → replay once` behaviours (`js/agua/agua.js:1002`, `js/agua/agua.js:1033`).
* Parse `Blocked-Content` (`js/agua/agua.js:971`) into the feature-gate set if parity with
  the app's menu gating is required.

### 5.2 Job ticket type

Model the ticket as a value, not a field on the device:

```kotlin
@JvmInline value class JobTicket(val id: String)

sealed interface JobState {
    data class Waiting(val ticket: JobTicket) : JobState
    data class Completed(val answer: RegisterValues) : JobState   // Items[]/Values[]
    data object Terminated : JobState
    data object Unanswered : JobState        // "missing job answer" past the deadline
    data class Failed(val code: String) : JobState
}
```

Rules that fix §4.4 by construction:

* `pollJob(ticket, ...)` must take the ticket as a **parameter** and must never read a
  mutable per-device "current ticket" field. In the original, `_getJobStatus` re-reads
  `reference.idRequest` on every tick (`js/agua/Device.js:2631`) — that is the bug.
* Serialize all device operations per device behind a `Mutex`, so no read job can start
  while a write job is unconfirmed (the original allows exactly that via
  `js/agua/Device.js:2560`/`:2585`).

### 5.3 Write pipeline: write → poll → **read back → compare**

```
1. writeRegister(deviceId, registers)   -> deviceRequestWriting              // Device.js:2374
2. ticket = response.idRequest          -> if mt/pl present, use pl directly  // Device.js:2401
3. answer = pollJob(ticket, deadline)   -> completed / terminated / timeout  // Device.js:2628
4. verify = readRegisters(deviceId)     -> deviceGetBufferReading           // Device.js:2568
5. compare(verify[offsets], requested[values], masks)  // NOT IMPLEMENTED in the app (Device.js:2703-2713)
6. mismatch -> retry per policy; exhausted -> surface WriteOutcome.Failed
```

Step 5 is the whole point: the source has no such comparison, which is why a clamped or
ignored value is indistinguishable from success.

Comparison details to carry over from the register model: the write payload is per-register
`Items[]` (offset), `Masks[]`, `Values[]`, `Endianess[]`, `BitData[]`
(`js/agua/RegisterData.js:180-188`), and the read answer is `Items[]` + `Values[]` applied by
offset (`js/agua/Device.js:2703-2712`). Compare **through the mask**
(`js/agua/NativeRegister.js:255`) or you will produce false mismatches on bitfields.

### 5.4 Retry and timeout policy (recommendation — the app has none)

Nothing in the source provides a retry or deadline (see the §3 "NOT FOUND" rows), so these
are deliberate choices for the Kotlin client, tuned to the app's cadence:

| Concern | Recommendation | Rationale from source |
|---|---|---|
| Poll interval | start 300 ms, `+100 ms` per attempt, cap 1500 ms, small jitter | matches `statusPollingFrequency` default 300 (`js/agua/Device.js:2649`) |
| Job deadline | 20 s per write attempt (`withTimeout`) | no deadline exists in the app (`js/agua/Device.js:2647-2650`); 20 s ≈ 6–7 polls at the cadence above |
| Write attempts | 3 total (1 initial + 2 retries) | app never retries (`retry` grep: 0 hits) |
| Read-back | always, once per attempt, after `completed` | app never verifies (`js/agua/Device.js:2703-2713`) |
| Backoff between attempts | 2 s, 5 s | keeps the whole interaction under the 3000 ms refresh cadence's perceived latency (`js/it.omniaweb.widgets.js:518`) |
| `terminated` | **do not retry the ticket**; optionally retry the command once | `terminated` is terminal in the app and is dropped on the floor (`js/agua/Device.js:2643-2645`) |
| `missing job answer` | poll until the job deadline, then treat as `Unanswered` | the app polls it forever (`js/agua/Device.js:2648-2649`) |
| `INACTIVITY_ERROR` (all values = 32768) | fail fast with `StoveUnreachable`, do not retry blindly | `js/agua/Device.js:2699-2701`, `js/it.omniaweb.config.js:58` |
| Empty `completed` payload | treat as failure, not success | the app treats it as success (`js/agua/Device.js:2686-2689`) |

### 5.5 Result type and UX contract

```kotlin
sealed interface WriteOutcome {
    data class Confirmed(val observed: RegisterValues) : WriteOutcome
    data class Rejected(val code: String) : WriteOutcome        // "0000", 1106, …
    data object TerminatedByStove : WriteOutcome
    data object StoveUnreachable : WriteOutcome                 // 32768 / inactivity
    data object Timeout : WriteOutcome
    data class Mismatch(val requested: RegisterValues, val observed: RegisterValues) : WriteOutcome
}
```

Surface `Timeout`, `Mismatch`, `TerminatedByStove` to the user. In the original these are all
indistinguishable from success (`js/agua/Device.js:152-156`), which is exactly the reported
symptom.

### 5.6 Parity gotchas worth keeping in mind

* Non-GET requests silently carry `language` in the body (`js/agua/agua.js:924`) — some
  backends validate the body shape, so include it.
* The read must specify `BufferId` = `defaultBufferId` = 1 (`js/it.omniaweb.config.js:87`);
  there is no negotiation in the cloud path (only BLE probes buffers, via
  `GetBufferId`, `js/it.omniaweb.bluetoothdevice.js:270-290`).
* `device_registers_map: null` on `deviceGetRegistersMap` means "unchanged since
  `last_update`" — keep the cached map (`js/agua/Device.js:2532-2535`). Cache the map per MAC
  and persist it, as the app does (`js/agua/Device.js:2473`, `js/agua/Device.js:2753-2783`).
* Device identity fields needed for every device call: `id_product` (`js/agua/Device.js:1820`),
  `id_device` (`js/agua/Device.js:1806`), and `registration_number`, which is literally the
  serial number (`js/agua/Device.js:2827-2831`, serial at `js/agua/Device.js:1911`).
* Multi-device: the list comes from a single `deviceList` call, sorted by `name` client-side
  (`js/agua/User.js:241-254`), cached, and backed up to `devices.json` (`js/agua/User.js:258`,
  `js/agua/User.js:814`). Selection is by `id` (`js/agua/User.js:322-334`) or by the
  app-level `currentDevice` (`js/it.omniaweb.logic.js:439-452`).
* Device object fields available for a model class (all `this._data.*`):
  `id` `js/agua/Device.js:1778`, `id_client` `js/agua/Device.js:1792`,
  `id_device` `js/agua/Device.js:1806`, `id_product` `js/agua/Device.js:1820`,
  `mac` `js/agua/Device.js:1851`, `name` `js/agua/Device.js:1981`,
  `description` `js/agua/Device.js:1995`, `name_product` (model) `js/agua/Device.js:1953`,
  `product_serial` `js/agua/Device.js:1911`, `reg_code` `js/agua/Device.js:1925`,
  `cod_art` `js/agua/Device.js:1939`, `security_code` `js/agua/Device.js:1967`,
  `country` `js/agua/Device.js:2009`, `state` `js/agua/Device.js:2023`,
  `city` `js/agua/Device.js:2037`, `city_id` `js/agua/Device.js:2051`,
  `creation_date` `js/agua/Device.js:2065`, `update_date` `js/agua/Device.js:2079`,
  `autorization_it` `js/agua/Device.js:2093`, `assistance_code` `js/agua/Device.js:2105-2119`,
  `product_info` `js/agua/Device.js:75-78`, `is_managed` `js/agua/Device.js:2843`.
  **Firmware is not a device field** — it is reconstructed from registers `fw_1..fw_6` as
  `FW-xV-y-z-w` (`js/agua/Device.js:1166-1188`).
* Online flag: `is_online` (`js/agua/Device.js:2160`). The app never gates a write on it —
  recommended: check it before writing (this is a deviation from the source, deliberately).

---

## Appendix — citation verification

Every line cited above was re-read from `SRC` with `grep -n` / `sed -n`. Reproduction
commands:

```bash
SRC=docs/protocol/source-snapshot

# write path: detached job status
sed -n '148,160p' $SRC/js/agua/Device.js

# write request payload
sed -n '2371,2406p' $SRC/js/agua/Device.js

# read request payload
sed -n '2565,2593p' $SRC/js/agua/Device.js

# job polling and its four statuses
sed -n '2612,2672p' $SRC/js/agua/Device.js

# empty answer == success, values applied without comparison
sed -n '2684,2714p' $SRC/js/agua/Device.js

# inactivityCode 32768 and its check
grep -n 'inactivityCode' $SRC/js/it.omniaweb.config.js $SRC/js/agua/Device.js

# transport: header-carried action, no client timeout
sed -n '938,962p' $SRC/js/agua/agua.js

# poll/refresh constants
grep -rn "statusPollingFrequency\|refreshDeviceInterval" $SRC/js
```

Absence claims were checked with:

```bash
grep -rn 'retry\|Retry\|tentativ' $SRC/js     # 0 hits -> no retry logic
grep -n 'timeout' $SRC/js/agua/agua.js        # 0 hits -> no HTTP timeout
```
