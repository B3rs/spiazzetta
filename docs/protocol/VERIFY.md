# VERIFY.md — falsification review of reports 01–06

**Source of truth (read-only, unmodified):** `docs/protocol/source-snapshot`
(MyPiazzetta 1.9.91 Cordova bundle). Every path below is relative to that root.
All six reports exist; none is `MISSING`.

Review stance: adversarial. For each report every `file:line` citation was opened with
`sed -n` / `grep -n`, and every constant, URL, action name, offset, mask and enum value was
re-grepped in the source. Absence claims were tested by re-running the stated grep.
Report 06's 115 key rows were checked mechanically: `lang/it.json` and `lang/en.json` were
parsed with a line-aware JSON parser that records the physical line of every key, and each
row's key path, IT text, EN text and both line numbers were compared.

Root facts re-confirmed before any report was read: `applicationVersion` `1.9.91`
(`js/it.omniaweb.config.js:39`), brand block `js/it.omniaweb.config.js:41-68`, host
`js/it.omniaweb.config.js:48`, `code` `458632` `:47`, `id` `1` `:46`, `clientId`
`mypiazzetta` `:45`.

---

## 1. Verdicts

| Report | Verdict | Distinct citations | Citations re-opened at the exact line | PASS | FAIL |
|---|---|---|---|---|---|
| 01-cloud-auth.md | **FAIL (3)** | 168 | 157 | 165 | 3 |
| 02-device-jobs.md | **PASS** | 169 | 163 | 169 | 0 |
| 03-registers-control.md | **FAIL (5)** | 121 | 120 | 116 | 5 |
| 04-state-alarms.md | **FAIL (1)** | 112 | 112 | 111 | 1 |
| 05-ble.md | **FAIL (1)** | 129 | 127 | 128 | 1 |
| 06-vocabulary.md | **FAIL (2)** | 237 | 237 | 235 | 2 |
| **Total** | **4 of 6 need edits** | **936** | **916** | **924** | **12** |

Counting rules, so the numbers are reproducible:

* **Distinct citations** — unique `file:line` / `file:start-end` strings per report. All 936
  resolve to an existing file and to lines that exist (no dangling or out-of-range citation
  anywhere in the six documents).
* **Re-opened at the exact line** — the citation falls inside a range that was actually printed
  from the source in this review (or was verified by `grep -n` for the literal it asserts).
  The remainder were structurally validated only and are **not** counted as PASS.
* **PASS = distinct citations − FAIL**, where a FAIL is a citation whose claim the source
  contradicts or does not support (listed in §2). PASS therefore means "opened and matched",
  not "probably fine".

---

## 2. FAIL rows

Format: `<report> | <claim> | <what the source actually says> | <file:line>`

| # | Report | Claim | What the source actually says | file:line |
|---|---|---|---|---|
| F1 | 01-cloud-auth.md §1.5 endpoint table | `deviceJobStatus` is a **POST** whose `url` header is `deviceJobStatus` | the call passes `it.omniaweb.agua.methods.GET`, `null` payload and the affix `'/' + reference.idRequest`, so the request is a GET and the header is `url: deviceJobStatus/<idRequest>` (report 02 states this correctly) | `js/agua/Device.js:2625-2632`; `js/agua/agua.js:955-956` |
| F2 | 01-cloud-auth.md §1.1 | "The optional `affix` argument … appears in **exactly one place**: `productList` passes `'/' + code`" | there is a second affix use: `deviceJobStatus` passes `'/' + reference.idRequest`. `grep -rn "'/' +" js/` returns three matches: the two affix arguments (`js/agua/agua.js:333`, `js/agua/Device.js:2631`) and one unrelated concatenation in the connectivity probe | `js/agua/Device.js:2631`; `js/agua/agua.js:333`; `js/plugins/it.omniaweb.plugins.network.information.js:121` |
| F3 | 01-cloud-auth.md §1.5 (last paragraph) | the reachability probe's "literal request URL is `https://…/api/bridge/endpoint//https://…/firmware/agua.html`" | the base `initialize` that builds `host + '/' + endpoint` (`:121`) is **overridden later in the same file** by a brand override that sets `endpoint = it.omniaweb.config.checkInternetConnection.endpoint` (the bare firmware URL), and the override is the one published (`:182`). The probe GET is therefore `https://piazzetta-iot.app2cloud.it/firmware/agua.html?t=<random>` — the same dead-code trap the report correctly catches in `agua.js`, missed here | `js/plugins/it.omniaweb.plugins.network.information.js:163-172`, `:182`, `:60`, `:121` |
| F4 | 03-registers-control.md §1.4 (last bullet) | "this happens in aggregate helpers that add both endpoints **unconditionally**, e.g. `AirEnergySavingRegister.js:210-219`" | the cited helper adds each endpoint only after a null check (`if( this.getStart() !== null )`, `if( this.getStop() !== null )`); nothing is added unconditionally | `js/agua/registers/AirEnergySavingRegister.js:208-217` |
| F5 | 03-registers-control.md §2.1 row (e) | `power_auto`, `power_night`, `eco_stop` are separate feature registers whose behaviour is evidenced by app code at `Device.js:1362-1383` | that range is `hasNewNaturalMode()` / `isNewNaturalModeEnabled()`, i.e. `natural_mode_new` only; `power_auto`, `power_night` and `eco_stop` occur in **no** `.js` file (grep = 0 hits) — only as `lang/it.json` labels, which is what the report itself says in §4 #9 | `js/agua/Device.js:1362-1383`; contrast `js/agua/Device.js:1632`, `:1658` (`multiconfort`, which the app does read) |
| F6 | 03-registers-control.md §2.2 | "Quality range … **display range is `raw` 0..10 -> -5..+5**" | the class defines only `MIDDLE_VALUE = 5` and subtracts/adds it; raw `set_min`/`set_max` come from the server map. No 0..10 range (nor any raw bound) exists in the bundle — the report's own §1 says ranges are server data | `js/agua/registers/QualityRangeRegister.js:29`, `:47-49`, `:61-63`, `:75-77`, `:133-137` |
| F7 | 03-registers-control.md §4 #10 | "the **41 hits** elsewhere in `www` are all vendored Cordova/lib code" | `grep -rn -i -e base64 -e atob -e btoa` over `www` returns **63 hits in 23 files** (0 under `js/`); the file list is right, the count is not | e.g. `plugins/cordova-plugin-ble-central/www/ble.js:26`; measurement: `grep -rn -i -e base64 -e atob -e btoa www` piped to `wc -l` = 63 |
| F8 | 03-registers-control.md §2.2 row "Quality range" | citation `QualityRangeRegister.js:136` for `raw = display + 5` | the `value + MIDDLE_VALUE` write is line 135; line 136 is the closing `return this;` | `js/agua/registers/QualityRangeRegister.js:133-137` |
| F9 | 04-state-alarms.md §2.2 (table header) | "`Device.getWarnings()` (`Device.js:1087-1116`) produces **these** warning objects", followed by five rows including `worktime` and `pellet` | `getWarnings()` produces only `voltage_current`, `voltage_restart`, `auger_load`. `worktime` and `pellet` are produced by `getPelletStockStatus()` | `js/agua/Device.js:1087-1116` vs `js/agua/Device.js:1248-1298` (`worktime` `:1271-1278`, `pellet` `:1259-1266`) |
| F10 | 05-ble.md §7.2 | "Single-item body, **292 UTF-8 bytes**", prelude `24 01` (`len=0x0124=292`), "**15 chunks** (fourteen chunks of 20 bytes plus a 12-byte tail)" | the JSON body printed in that same section measures **303 UTF-8 bytes** (`new TextEncoder().encode(line).length === 303`), so the prelude length would be `2F 01` and the count `ceil(303/20) = 16` chunks (15 × 20 + 3). The chunk arithmetic is internally consistent with 292 but inconsistent with the example that is shown | mechanism: `js/plugins/it.omniaweb.plugins.ble.central.js:794-796`, `:165-180` |
| F11 | 06-vocabulary.md row `login.subscribe` | `Registrati` / `Activate` at `lang/it.json:1601 / lang/en.json:1601` | `login.subscribe` is at line **1611** in both bundles. Line 1601 is a different key, `login.failure.subscribe`, which happens to carry the same Italian string ("Registrati"), which is how the error survives a value-only check | `lang/it.json:1601` (`login.failure.subscribe`), `lang/it.json:1611` (`login.subscribe`); `lang/en.json` same lines |
| F12 | 06-vocabulary.md row `login.subscribe` (EN) | `lang/en.json:1601` | same defect as F11 on the English bundle | `lang/en.json:1611` |

F11 and F12 are one editorial error (one table row, two citations) counted as two FAILs because
the deliverable counts citations.

---

## 3. What each report got right (PASS substance)

Not exhaustive — these are the load-bearing claims that were re-read and held. The count
column in §1 covers the rest.

**01 — PASS 165/168.** Transport envelope (`js/agua/agua.js:891-903`, `:938-956`), header names
and casing, `authorization` = raw token else installation UUID (`js/agua/User.js:125-135`),
`local` = "no token" (`js/agua/User.js:77-83`), `aguaid`←`user.id` (`js/agua/User.js:882-885`) and
`userid`←`user.agua_id` (`js/agua/User.js:868-871`), the dead pre-override implementation and its
override (`js/agua/agua.js:517-546`, `:915`, `:1079`), success/error envelope and the
`1106`/`1116`/`1144` branches (`js/agua/agua.js:969-1066`), `Blocked-Content` split (`:971-972`),
the `translateRegKeyList` token wipe (`:978-982`), no-cookie/no-crypto claims (`grep cookie|
withCredentials` = 0; `md5|sha1|sha256|hmac|api_key|apikey|btoa` outside `libs/` = only UI
"signature" hits), every action name in the §1.5 table (all re-grepped in `js/agua/*.js`,
`js/it.omniaweb.logic.js`), the `appSignup` body (`js/agua/agua.js:704-719`) and its
`agua.uuid = response.id_app` (`:729-733`), plaintext password (`js/agua/User.js:446`,
`js/it.omniaweb.logic.js:3288`), `1134`→activation form (`js/it.omniaweb.logic.js:3297-3299`),
`INVALID_APP_ERROR` (`:221-224`), device-list field readers (`js/agua/Device.js:1776-1981`,
`:2843`), `registration_number` = serial (`js/agua/Device.js:2827-2830`), `clientId` used only
in consent/announcement payloads (`js/it.omniaweb.logic.js:607`, `:3684`, `:4132`).

**02 — PASS 169/169, no contradicted claim.** The central finding is exact: `Device.update`
starts `_getJobStatus(true)` as a bare statement and resolves `true` on the next line
(`js/agua/Device.js:152-157`); the write job's promise has no consumer. Also verified:
`_update` payload incl. `Protocol:"RWMSmaster"`, `BitData:8`, `Endianess:"L"` merged under `data`
(`js/agua/Device.js:2371-2388`), ticket in `this.idRequest` (`:24`, `:2366`, `:2398`, `:2560`,
`:2585`), `mt`/`pl` shortcut (`:2401-2405`, `:2588-2592`), `mt === 'N'` = NACK
(`js/it.omniaweb.bluetoothdevice.js:275`), poll loop and its four statuses
(`js/agua/Device.js:2612-2660`), unknown status hangs (no `default` case), `_parseRegistersValues`
returns `true` with no `Items`/`Values` (`:2686-2689`), no comparison of returned vs sent values,
`inactivityCode` 32768 (`js/it.omniaweb.config.js:58`; `js/agua/Device.js:2726-2741`), no retry
(`grep retry|Retry|tentativ` = 0) and no HTTP timeout (`grep timeout` in `agua.js` = 0),
`statusPollingFrequency` 300 (`js/agua/Device.js:2649`), `refreshDeviceInterval` 3000
(`js/it.omniaweb.widgets.js:518`, `js/it.omniaweb.views.js:2034`,
`js/it.omniaweb.logic.js:1852`), dialog close restarts auto-refresh
(`js/it.omniaweb.logic.js:1849-1853`).

**03 — PASS 116/121.** The "no offsets/masks in the APK" thesis holds: every numeric field is read
from the server record at `js/agua/NativeRegister.js:157-160`, `:255-258`, `:213-230`, `:241-244`,
`:269-272`, `:283-286`, `:297-300`, `:311-314`, `:366-378`, `:464-481`, `:497-519`, and the map is
fetched/cached at `js/agua/Device.js:2513-2535`, `:2462-2490`, `:2753-2774`. Value algebra
(`setValue` `js/agua/NativeRegister.js:135-146`, `getEvaluatedValue` `:85-88`, temperature
`:116-118`), the five parallel arrays (`js/agua/RegisterData.js:38-47`, `:59-70`, `:180-188`), the
`SET→ENABLE→GET` fallback (`js/agua/Register.js:488-509`), switch/slider derivation
(`:93-96`, `:121-124`), `Register.setValue` touching only `SET` (`:299-306`), the class dispatch
table (`js/agua/Device.js:2262-2294`), `MultifireRegister` minimum=1 when
`isNewNaturalModeEnabled() && power > 3` (`js/agua/registers/MultifireRegister.js:45-62`),
`Multifire2Register.isManageable` (`js/agua/registers/Multifire2Register.js:37-49`), the
10-minute time encoding `hours*6 + minutes/10` (`js/agua/registers/NaturalModeRegister.js:424`) and
its decode (`:360-362`), sentinel 144 (`:34-35`; `js/agua/ChronosProgram.js:35-36`, `:428-445`),
chrono day flags (`js/agua/Device.js:866-867` `weekdays` order + `:908-912`),
`setPowered` writing the `GET` record of `status_managed` by looking up the enum text `ON`/`OFF`
(`js/agua/Device.js:990-1006`, `js/agua/NativeRegister.js:326-336`), the inverted
`getPowerStatus` (`js/agua/Device.js:1017-1033`), the not-found list for fan/setpoint/power
ranges (`grep -rnw -i -e vent -e fan js/` = 0 hits; only `vent_main_set` label at
`lang/it.json:1272`), and `base64|atob|btoa` = 0 hits under `js/`.

**04 — PASS 111/112.** Power-state constants and their IT/EN labels
(`js/agua/Device.js:38-40`; `lang/it.json:1430`, `:1431`, `:1439`, `:1440`), cross-wired button keys
(`lang/it.json:1430-1431`), status-bar switch and alarm override
(`js/brands/mypiazzetta/it.omniaweb.widgets.js:851-889`), `getStatusLabel` (`js/agua/Device.js:1058-1062`),
`hasAlarm` (`:1127-1134`), `getAlarmLabel` (`:1145-1155`), the three `getWarnings` conditions
(`:1092-1113`), `getPelletStockStatus` pellet regex `^ris_pellet_([a-z]*)` (`:1259-1266`) and
`ore_service >= ore_service_soglia` (`:1271-1278`), `coclea_load === 255` gating the power button
(`js/brands/mypiazzetta/it.omniaweb.widgets.js:893`), `cicalino` 0/1 images (`:1547`, `:2393-2397`),
`multicomfort` 0/1/2 + `WIRELESS` mismatch branch (`:2502-2528`), `sonda_canale < 33` DIP label
(`js/agua/TemperatureProbes.js:201-204`), the temperature key table (all 15 `lang/it.json` /
`lang/en.json` ranges checked: `:1184-1207`, `:1232-1243`, `:1244-1271`, `:540-559`, `:576-595`),
`temperature_units_of_measure` (`lang/it.json:1556-1571`), the inventory claims (11 locales,
`lang/it.json` 58,807 B, `lang/en.json` 54,806 B, `85` SVGs in `img/stoves/`, `data/` = only
`countries.json` 5,295 B), and the "no state names in the bundle" grep
(`accensione|pulizia|modulaz|stand.by` over `js/` = 0; the only `Accensione` strings in
`lang/it.json` are start/chrono labels at `:196`, `:441`, `:650`, `:714`, `:778`, `:842`, `:906`, `:970`, `:1368`, `:1396`).

**05 — PASS 128/129.** Single-characteristic design (`js/it.omniaweb.config.js:88-89`;
`js/agua/Device.js:2214-2215`; `6e400003` appears nowhere), prelude `J S O N len_lo len_hi 20 00`
built by `getWriteAckMessage` (`js/plugins/it.omniaweb.plugins.ble.central.js:165-180`, bytes at
`:170-177`), `length = message.byteLength` and `Math.ceil(length/20)` (`:794-796`), sequential
chunk writes from the previous success callback (`:822-846`), 8-byte prelude written first
(`:853-867`), read-until-`Navel` with `JSON.parse` (`:799-821`, `:810`, `:813`, `:815`), UTF-8
encode/decode (`:143-146`, `:154-157`), request envelope key order and values
(`js/it.omniaweb.bluetoothdevice.js:152-164`), `Cmd = action.replace(/device/g,'')`
(`js/agua/agua.js:499`), `mt` ∈ {`Q`, `C`, `N`} (`bluetoothdevice.js:155`, `:339`, `:275`),
connect chain incl. `Identity`, `GetBufferId` and `SetConfigBuffer` fallback
(`bluetoothdevice.js:46-112`, `:270-341`, `Freq: 3` at `:333`), Wi-Fi commands and the 10 s
timeout (`:352-400`, `:136-145`), the dead notification success callback
`typeof failure === 'success'` (`js/plugins/it.omniaweb.plugins.ble.central.js:717`), unfiltered
scan with `reportDuplicates:false` (`:257-291`), `T009_` + last 6 MAC chars
(`js/agua/Device.js:1895-1898`; `js/it.omniaweb.config.js:56-57`), name-based device resolution
(`js/agua/Device.js:2203-2219`), and the "no CRC/checksum/opcode" absences
(`grep crc|CRC|checksum|Checksum js/` = 0).

**06 — PASS 235/237 (114 of 115 rows perfect).** Both bundles were parsed independently: 995 leaf
paths each, identical path sets (0 differences), 986 scalar leaves — all consistent with the
report's structural claim. 114 rows matched on key path, IT text, EN text, IT line and EN line.
Verified prose: 11 locale files, the "Max room Temperature" / "Pellets depleated." EN defects, the
`Set …` prefix convention, `Da {0} a {1}` (`lang/it.json:184`), `devices_list.uclock` =
"Aggiorna orologio", no `"comfort"` / `"eco"` string, no firmware-update flow,
`NETWORK_OFFLINE: "offline"` (`js/it.omniaweb.eventsmanager.js:29`).

---

## 4. Independent answer: does the shipped app verify a register write by re-reading device state?

**No — not in any sense that can detect a failed or clamped write. It re-reads, but it never
compares, and the write's own outcome is unobserved.**

What the write path actually does:

1. `Register.update()` → `device.update({Items,Masks,Values,Endianess,BitData})`
   (`js/agua/Register.js:542-551`; arrays from `js/agua/RegisterData.js:180-188`).
2. `Device.update` POSTs `deviceRequestWriting` and, when the bridge answers with a ticket string,
   starts the poll with `reference._getJobStatus( true );` and **immediately resolves `true`** —
   the job promise is discarded (`js/agua/Device.js:152-157`; the poll is `:155`).
3. That discarded poll is the re-read: `deviceJobStatus/<idRequest>` (`js/agua/Device.js:2625-2632`)
   until `jobAnswerStatus === 'completed'`, then `jobAnswerData` is fed to
   `_parseRegistersValues` (`:2652-2659`), which writes the returned numbers back into the local
   register map by offset (`:2703-2713`).
4. Nothing compares those numbers with the `Values[]` that were sent (`:2386`). There is no
   `requested`/`expected`/`mismatch` logic anywhere in `js/agua/*.js` (grep = 0 hits), and a
   `completed` job with no `Items`/`Values` returns `true` (`:2686-2689`), i.e. blank answer =
   success.
5. The independent re-read the UI relies on is the ordinary refresh: in Bluetooth mode the write
   path forces `refresh(true)` (`js/agua/Device.js:178-183`); in cloud mode the modal close
   restarts auto-refresh with `refreshDeviceInterval` (3000 ms) (`js/it.omniaweb.logic.js:1852`),
   which issues `deviceGetBufferReading` (`js/agua/Device.js:2557-2578`) and applies the answer by
   offset through the same parser (`:288-345`, `:2684-2714`). That refresh can only surface
   *plausibility* failures: all values equal to `inactivityCode` 32768 →
   `Device.INACTIVITY_ERROR` (`js/it.omniaweb.config.js:58`; `js/agua/Device.js:2726-2741`),
   which the UI renders as the inactivity page (`js/it.omniaweb.logic.js:2089-2091`,
   `:2128-2131`). A stove that clamps or ignores the value simply shows a different number later,
   with no error.

So: write → job poll (answer applied to the cached map, unobserved, uncompared) → next periodic
read (applied, uncompared, no write-specific error path). There is no read-back verification, no
retry, and no user-visible write outcome beyond the immediate HTTP/job-ticket acceptance.

---

## 5. Appendix — citations that are imprecise but not false (not counted as FAIL)

* `01` §5 #10 — `deviceList` "attempted in that state (`js/it.omniaweb.logic.js:663`)": line 663
  shows `SHOW_DEVICES_LIST`; the actual `deviceList` call is `user.getDevices(...)` at
  `js/it.omniaweb.logic.js:1113` → `js/agua/User.js:232`. Substance holds.
* `02` §2.5 — "`.finally` closes the modal (`js/it.omniaweb.widgets.js:1384-1390`)": the `.finally`
  block is `:1392-1394`; `:1384-1390` covers the `then`/`catch`. Substance holds.
* `03` §1.3 — `Register.js:333-352`: the `getLabelKey` comment block starts at 333, the function at
  339. Substance holds.
* `04` §4 — `Register.js:333-352` cited for "`getLabelKey()` selects the native register key": same
  as above; correct content.
* `05` §3 — "`invokeMethod` re-enters `connect()` on every call … (`js/it.omniaweb.bluetoothdevice.js:133-135`)":
  the re-entry is `:125-127`; 133-135 is the timeout block. Substance holds.
* `06` — "Both files are structurally identical (986 leaf keys each)": reproduces exactly if
  "leaf" means scalar values with arrays not expanded (986 in each; 995 paths / 1044 entries if
  array elements are counted). Not an error, but the metric should be named.

## 6. Absence claims that were re-tested and hold

`retry|Retry|tentativ` over `js/` = 0 · `timeout` in `js/agua/agua.js` = 0 ·
`cookie|withCredentials` over `js/` = 0 · `md5|sha1|sha256|hmac|api_key|apikey|btoa` outside
`libs/` = UI "signature" only · `base64|atob|btoa` over `js/` = 0 (63 hits elsewhere in `www`, see
F7) · `crc|CRC|checksum|Checksum` over `js/` = 0 · `grep -rnw -i -e vent -e fan` over `js/` = 0 ·
`accensione|pulizia|modulaz|stand.by` over `js/` = 0 · `6e400003`/NUS TX = 0 ·
`requestMtu` = definition + Cordova shim only, never called · `writeWithoutResponse` = definition +
shim only, never called · `power_auto|power_night|eco_stop` over `js/` = 0 (see F5).
