# 03 - Agua register model and stove control definitions

Source of truth for this document: the unminified production bundle of MyPiazzetta 1.9.91
(`docs/protocol/source-snapshot`, `applicationVersion` `1.9.91` at
`js/it.omniaweb.config.js:39`). Every claim below carries a `<file>:<line>` citation relative to
`assets/www/`. Nothing here is inferred from firmware, a HAR capture, or a live device; where the
app does not carry the data the text says NOT FOUND instead of guessing.

Code blocks below are verbatim from the cited line range except that leading indentation is
normalised to one tab level, that `...` marks elided lines, and that the temperature-unit block from
`lang/it.json` is re-flowed onto single lines with `\u00b0` escapes for ASCII safety (the bundle
contains the literal degree sign).

The single most important structural fact, stated up front and proved in section 1:

> **The APK does not contain register addresses.** `offset`, `mask`, `big_endian`, `is_word`,
> `formula`, `formula_inverse`, `set_min`, `set_max`, `step`, `enc_val` and `enable_val` are
> **server-side data** delivered by `deviceGetRegistersMap` and stored per device. The app is a
> generic engine over that map. Consequently the numeric offsets, masks, scales and ranges for
> fan speed, setpoint, power level, on/off, mode and chrono **are NOT FOUND in the www bundle**;
> what *is* found is (a) the exact wire format, (b) the exact arithmetic the app applies to a
> map entry, and (c) the register *keys* and semantics of every stove control.

---

## 1. The register model

### 1.1 Where a register definition comes from

On first update the app fetches the device's register map from the cloud and groups the flat
`registers` array by `reg_key_group`:

```js
// js/agua/Device.js:2233-2302 (excerpt)
Device.prototype.setRegistersMap = function( map, backup )
{
	...
	var l = map.registers.length;
	for( i = 0; i < l; i++ )
	{
		item = map.registers[i];
		if( !temp[ item.reg_key_group ] )
		{
			temp[ item.reg_key_group ] = [];
		}
		temp[ item.reg_key_group ].push( item );
	}
	...
```

Each element of `map.registers` is one raw register definition record; its fields are the ones the
`NativeRegister` accessors read (section 1.2). The fetch itself:

```js
// js/agua/Device.js:2513-2535 (excerpt)
it.omniaweb.agua.invokeRemoteMethod
(
	  'deviceGetRegistersMap'
	, {
		  id_product: reference.getProductID()
		, id_device: reference.getDeviceID()
		, last_update: lastUpdate
		, registration_number: reference.getRegistrationNumber()
	}
	, it.omniaweb.agua.methods.POST
	, null
	, true
)
...
	if( response.Success )
	{
		if( response.device_registers_map !== null )
		{
			reference.setRegistersMap( response.device_registers_map.registers_map[0], true );
		}
```

The same JSON is cached on the phone, keyed by MAC address with the colons stripped, and used as a
fast path on later runs (`js/agua/Device.js:2462-2490`, write side `js/agua/Device.js:2753-2774`).
So a Kotlin client must do what the app does: **fetch the map once, persist it, then drive
everything from it.** No stove model table exists in the app.

### 1.2 `NativeRegister` - one definition record, one raw value

```js
// js/agua/NativeRegister.js:22-28
var NativeRegister = function( register, data )
{
	this._register = register;
	this._data = data;
	this._parseValuesMap();
	this.value = null;
};
```

`data` is the server record. Every getter is a straight field read:

| Server field | Accessor | Meaning | Citation |
|---|---|---|---|
| `offset` | `getOffset()` | register address passed in `Items` | `js/agua/NativeRegister.js:157-160` |
| `mask` | `getMask()` | bit mask applied **on read** | `js/agua/NativeRegister.js:255-258` |
| `big_endian` | `getEndianess()` -> `'B'` / `'L'` | byte order token for `Endianess` | `js/agua/NativeRegister.js:269-272` |
| `is_word` | `getBitData()` -> `16` / `8` | width token for `BitData` | `js/agua/NativeRegister.js:283-286` |
| `set_min` | `getMinimumValue()` | slider minimum | `js/agua/NativeRegister.js:213-216` |
| `set_max` | `getMaximumValue()` | slider maximum | `js/agua/NativeRegister.js:227-230` |
| `step` | `getStep()` | slider step | `js/agua/NativeRegister.js:241-244` |
| `is_hex` | `isHex()` | value is hex-formatted, not binary | `js/agua/NativeRegister.js:464-467` |
| `is_temperature` | `isTemperature()` | apply temperature unit formula | `js/agua/NativeRegister.js:311-314` |
| `formula` | used by `getEvaluatedValue()` | raw -> engineering value | `js/agua/NativeRegister.js:87` |
| `formula_inverse` | used by `setValue()` | engineering value -> raw | `js/agua/NativeRegister.js:141` |
| `enc_val[]` | `_parseValuesMap()` | enum labels `{value, lang, description}` | `js/agua/NativeRegister.js:497-519` |
| `enable_val[]` | `getEnabledValues()` | values for which the register is enabled | `js/agua/NativeRegister.js:366-378` |
| `reg_key` | `getKey()` | textual key | `js/agua/NativeRegister.js:171-174` |
| `reg_type` | `getType()` | `GET` / `SET` / `MIN` / `MAX` / `ENABLE` | `js/agua/NativeRegister.js:199-202` |
| `reg_key_group` | `getGroup()` | grouping key | `js/agua/NativeRegister.js:185-188` |
| `app_image_name` | `getImageName()` | UI icon | `js/agua/NativeRegister.js:297-300` |
| `display_dashboard` / `display_info` / `display_manage` / `display_order` | UI placement | | `js/agua/NativeRegister.js:422-425`, `436-439`, `450-453`, `478-481` |

The `#` placeholder in both formulas is replaced by the numeric value before evaluation; evaluation
is done by the bundled `math-expression-evaluator` library (`libs/math-expression-evaluator/math-expression-evaluator.min.js`,
loaded at `index.html:62`).

### 1.3 `Register` - a group of typed `NativeRegister`s

```js
// js/agua/Register.js:32-43
var Register = function( group, device, registers )
{
	this._group = group;
	this._device = device;
	this._ranges_mode = false;
	this._registers = {};
	for( var i = 0; i < registers.length; i++ )
	{
		this._registers[ registers[i].reg_type ] = new it.omniaweb.agua.NativeRegister( this, registers[i] );
	}
	this._setupSignals();
};
```

So one user-facing control is a **group** (`reg_key_group`) holding up to one record per type:
`GET`, `SET`, `MIN`, `MAX`, `ENABLE` (`js/agua/Register.js:16-20`, `45-52`). A control is a switch
when `min === 0 && max === 1` (`js/agua/Register.js:93-96`), a slider otherwise
(`js/agua/Register.js:121-124`); the slider is built with exactly these values:

```js
// js/it.omniaweb.widgets.js:1106-1112
			min: _this14.props.register.getMinimumValue(),
			max: _this14.props.register.getMaximumValue(),
			step: _this14.props.register.getStep(),
			value: _this14.props.register.getSETValue(),
			tooltipFormat: function tooltipFormat(args) {
				return reference.props.register.setValue(args.value).getSETFormattedValue();
			}
```

Note the important consequence: **no stove range is hardcoded in the UI** - the slider range,
including the setpoint range, comes from the map's `set_min` / `set_max` / `step`.

Several registers may share one physical `offset` and differ only by `mask`
(`Device.prototype.getRegistersByOffset()` returns a list, `js/agua/Device.js:547-562`), which is
how sub-fields live inside one word.

### 1.4 `RegisterData` - the five parallel arrays of a request

```js
// js/agua/RegisterData.js:38-47
RegisterData.prototype.reset = function()
{
	this.items = [];
	this.masks = [];
	this.values = [];
	this.endianess = [];
	this.bitdata = [];

	return this;
};
```

```js
// js/agua/RegisterData.js:59-70
RegisterData.prototype.addRegisterData = function( register )
{
	switch( true )
	{
		case register instanceof it.omniaweb.agua.NativeRegister:

			this.items.push( register.getOffset() );
			this.masks.push( register.getMask() );
			this.values.push( register.getValue( true ) );
			this.endianess.push( register.getEndianess() );
			this.bitdata.push( register.getBitData() );
			break;
```

and the wire shape, with capitalised keys:

```js
// js/agua/RegisterData.js:180-189
RegisterData.prototype.getData = function()
{
	return {
		  Items: this.getItems()
		, Masks: this.getMasks()
		, Values: this.getValues()
		, Endianess: this.getEndianess()
		, BitData: this.getBitData()
	};
};
```

Key semantics for a reimplementation:

* `Values[i]` is taken with `getValue( true )`, i.e. **`preventEvaluation === true`**, which returns
  the stored raw value untouched (`js/agua/NativeRegister.js:101-109`). The mask is **not** applied
  on the way out - the mask only accompanies the write so the device knows which bits to replace.
* Arrays are always arrays, even for a single register, and their order is the order of
  `addRegisterData()` calls. All five arrays are index-aligned.
* An entry may carry `null` in `Values` if the register was added without ever being set, because
  `this.value` starts as `null` (`js/agua/NativeRegister.js:27`, `139`); this happens in aggregate
  helpers that add both endpoints unconditionally, e.g. `js/agua/registers/AirEnergySavingRegister.js:210-219`.

### 1.5 Read path - how a response is decoded

Reads never carry binary or base64 register data. The app asks for a buffer read, then polls a job,
then applies the returned `{Items, Values}` pairs by offset:

```js
// js/agua/Device.js:2565-2575 (excerpt)
it.omniaweb.agua.invokeDeviceMethod
(
	  reference
	, 'deviceGetBufferReading'
	, {
		  id_product: reference.getProductID()
		, id_device: reference.getDeviceID()
		, BufferId: it.omniaweb.config.defaultBufferId
		, registration_number: reference.getRegistrationNumber()
	}
	, it.omniaweb.agua.methods.POST
```

`defaultBufferId` is `1` (`js/it.omniaweb.config.js:87`). The response is either the parsed payload
or a job id string; a job id is polled with `GET /<idRequest>` (`js/agua/Device.js:2625-2632`) until
`jobAnswerStatus` is `completed`, and the payload is then `response.jobAnswerData`
(`js/agua/Device.js:2653`).

```js
// js/agua/Device.js:2699-2713
			if( !this._hasValidValues( data.Values ) )
			{
				return Device.INACTIVITY_ERROR;
			}
			if(this.registersMap !== null)
			{
				for( i = 0; i < l; i++ )
				{
					for( group in this.registersMap )
					{
						this.registersMap[ group ].setOffsetValue( parseInt( data.Items[i] ), data.Values[i] );
					}
				}
			}
			return true;
```

```js
// js/agua/Register.js:182-193
Register.prototype.setOffsetValue = function( offset, value )
{
	var item, type;
	for( type in this._registers )
	{
		item = this._registers[ type ];
		if( item.getOffset() === offset )
		{
			item.setValue( value, true );
		}
	}
};
```

Decoding rules, therefore:

1. Transport is JSON over HTTPS. Values are plain JSON numbers, one per requested offset.
   **There is no base64 layer and no hex frame anywhere in the app**: the only hex handling is the
   per-register `is_hex` flag (`js/agua/NativeRegister.js:87`, `142`), and a grep for
   `base64|atob|btoa` over `js/` returns no register-related hit.
2. The raw number is stored verbatim into **every** register whose `offset` matches, in every group
   (`setValue(value, true)` = `preventEvaluation`).
3. Masking and scaling happen lazily, at read time, inside `getEvaluatedValue()`:

```js
// js/agua/NativeRegister.js:85-88
NativeRegister.prototype.getEvaluatedValue = function( value )
{
	return mexp.eval( this._data.formula.replace( '#', ( this.isHex() ? value.toString( 16 ) : value ) & this.getMask() ) );
};
```

   i.e. `engineering = eval(formula with '#' replaced by (raw & mask))`.
4. Temperature registers then get a second transformation to the display unit
   (`js/agua/NativeRegister.js:116-118`), using the table in `lang/it.json:1556-1570`:

```json
// lang/it.json:1556-1570
"temperature_units_of_measure": {
	"celsius":    { "formula": "#",             "index": 0, "key": "celsius",    "label": "Gradi Celsius",    "symbol": "\u00b0C" },
	"fahrenheit": { "formula": "int(#*9/5)+32", "index": 1, "key": "fahrenheit", "label": "Gradi Fahrenheit", "symbol": "\u00b0F" }
}
```

   The active unit is a real register, `unit_measure`, when present (`js/agua/agua.js:51-57`).

A response whose `Values` are all equal to `currentBrand.inactivityCode` (`32768`,
`js/it.omniaweb.config.js:58`) is treated as "device did not answer" (`js/agua/Device.js:2726-2741`).

### 1.6 Write path - how a request is serialized and batched

One `Device.update(data)` call sends **one** `deviceRequestWriting` request for **all** registers in
`data`. Defaults are merged first, caller data second, so the per-register arrays replace the scalar
defaults:

```js
// js/agua/Device.js:2371-2388
					it.omniaweb.agua.invokeDeviceMethod
					(
						  reference
						, 'deviceRequestWriting'
						, $.extend
						(
							{}
							, {
								  id_product: reference.getProductID()
								, id_device: reference.getDeviceID()
								, Protocol: "RWMSmaster"
								, BitData: 8
								, Endianess: "L"
								, registration_number: reference.getRegistrationNumber()
							}
							, data
						)
						, it.omniaweb.agua.methods.POST
```

`data` is `RegisterData.getData()`, so the effective body always contains `Items`, `Masks`,
`Values`, `Endianess`, `BitData` as arrays. The default `Protocol` string is `RWMSmaster`; the
`BitData: 8` / `Endianess: "L"` scalars only survive if a caller passes a payload without those keys.

The brand layer then POSTs to the bridge endpoint and puts the method name in headers rather than in
the URL path:

```js
// js/agua/agua.js:941-956
						  url: it.omniaweb.config.currentBrand.host
						, method: method
						, dataType: "json"
						, async: true
						, contentType: "application/json; charset=utf-8"
						, data: method === "GET" ? null : JSON.stringify( $.extend( {}, payload ) )
						, crossDomain: true
						, beforeSend: function( jqXHR, settings )
						{
							var headers = reference.getRemoteMethodInvocationHeaders(), prop;
							for( prop in headers )
							{
								jqXHR.setRequestHeader( prop, headers[prop] );
							}
							jqXHR.setRequestHeader( 'method', method );
							jqXHR.setRequestHeader( 'url', action + urlaffix );
```

Host, customer code and client id (`js/it.omniaweb.config.js:44-48`):

```json
"brand": "piazzetta", "clientId": "mypiazzetta", "id": 1, "code": "458632",
"host": "https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/"
```

Headers set on every call (`js/agua/agua.js:891-903`): `CustomerCode`, `IdBrand`,
`ApplicationVersion`, `authorization`, `local`, `brand`, `aguaid`, `userid`. Every non-GET payload
also gets `language` appended (`js/agua/agua.js:922-925`), so bodies always carry
`"language":"it"` (or the selected language).

Batching rules:

* A single control writes its SET (and, when the group has one, ENABLE) record
  (`js/agua/Register.js:488-509`); a logical feature writes several groups at once, e.g. natural
  mode sends type + manual + start + until in one request
  (`js/agua/registers/NaturalModeRegister.js:380-400`), chronos send general/weekly/program enables
  in one request (`js/agua/Chronos.js:457-477`).
* The auger-load helper deliberately sends the GET and SET records of the same group together,
  which proves that a write request may also request a readback of that offset
  (`js/agua/registers/AugerLoadRegister.js:277-283`).
* Full-device write = `getCurrentRegistersMap()`, which concatenates `getFullRegisterData()` for
  every group (`js/agua/Device.js:2313-2327`) - used by the BLE config-buffer setup, which
  additionally de-duplicates offsets and drops `Masks` entirely
  (`js/it.omniaweb.bluetoothdevice.js:309-341`).

Over Bluetooth the same payload is wrapped in an Agu/Navel envelope and the method name is sent
without the `device` prefix (`js/agua/agua.js:494-503`, `js/it.omniaweb.bluetoothdevice.js:147-165`):

```js
// js/it.omniaweb.bluetoothdevice.js:152-164
									{
										  mt: it.omniaweb.utils.isEmpty( mt ) ? "Q" : mt
										, s: { ss: "Apk", sj: it.omniaweb.agua.getCurrentUser().getUUID() }
										, r: { rj: "Navel" }
										, pl: $.extend( {}, {Cmd:method}, payload )
									}
```

### 1.7 Value algebra summary (the part a Kotlin port must copy exactly)

`setValue(v)` (write direction), `js/agua/NativeRegister.js:135-146`:

```js
NativeRegister.prototype.setValue = function( value, preventEvaluation )
{
	if( preventEvaluation === true )
	{
		this.value = value;
	} else {
		value = mexp.eval( this._data.formula_inverse.replace( '#', value ) );
		this.value = this.isHex() ? parseInt( value, 16 ) : value;
	}

	return this;
};
```

So: `raw = is_hex ? parseInt(eval(formula_inverse.replace('#', engineering)), 16) : eval(formula_inverse.replace('#', engineering))`.
Masks are never applied on write. `getMask()` is a read-side filter, and `getBitData()` /
`getEndianess()` only travel as metadata tokens.

Register-type overrides that change behaviour (all four are real, in-source deviations a port must
replicate):

| Class | Behaviour | Citation |
|---|---|---|
| `QualityRangeRegister` | display value = raw - 5; write value = display + 5; min/max also shifted | `js/agua/registers/QualityRangeRegister.js:29`, `47-49`, `75-79`, `133-137` |
| `MultifireRegister` | minimum forced to 1 while new natural mode is enabled and `power > 3` | `js/agua/registers/MultifireRegister.js:45-62` |
| `Multifire2Register` | manageable only when `multifire_modality` ENABLE value is `1` | `js/agua/registers/Multifire2Register.js:37-49` |
| `NaturalModeRegister` | time encoding (section 2.2) and 0/1 type bit | `js/agua/registers/NaturalModeRegister.js:34-37`, `137-156`, `222-231` |

Group -> class dispatch happens in `setRegistersMap` (`js/agua/Device.js:2262-2294`): groups
`p_quality_p12` and `c_quality_p12` use `QualityRangeRegister`, `multifire_1` / `multifire_2` use
their subclasses, everything else (including `vent_main`, `temp_air`, `power`, `status_managed`)
is a plain `Register`.

---

## 2. Control register table

### 2.1 The six requested controls

`offset`, `mask`, `size`, `endianess` and the numeric range of every row come from the server map at
runtime; the table states that explicitly and cites the accessor that consumes each field. The
`reg_key` names are hard facts: they are the keys the app itself looks up or ships translations for.

| # | Control | `reg_key` (+ `reg_type`) | offset | mask | size (BitData) | endianess | value scale / range | Evidence |
|---|---|---|---|---|---|---|---|---|
| a | Fan speed / ventilation ("Set Vent. Ambiente") | `vent_main` / `SET` only - the bundle carries no `vent_main_get`, `_min`, `_max` or `_enable` key | NOT FOUND (server field `offset`) | NOT FOUND (server field `mask`) | `is_word ? 16 : 8` | `big_endian ? "B" : "L"` (request default `"L"`) | raw integer; range is `set_min`..`set_max` with `step` from the map - the app never hardcodes a speed count | `lang/it.json:1272`, `js/agua/NativeRegister.js:157-160`, `255-258`, `283-286`, `269-272`, `213-216`, `227-230`, `241-244` |
| a2 | Fan speed inside chrono program P1..P6 ("Set Ventilazione Chrono PN") | `chrono_pN_fan` / `SET` (`N` = 1..6) | NOT FOUND | NOT FOUND | as above | as above | as above | `lang/it.json:632` (P1; P2..P6 follow the same pattern, see section 4) |
| b | Room setpoint ("Set Temperatura Ambiente") | `temp_air` / `SET`, plus `GET`, `MIN`, `MAX` | NOT FOUND | NOT FOUND | as above | as above | engineering value = `set_min`..`set_max` (range itself NOT FOUND); `raw = eval(formula_inverse)`; display unit applied when `is_temperature` | `lang/it.json:1196`, `1184`, `1192`, `1188`, `js/agua/NativeRegister.js:87`, `141`, `116-118` |
| c | Power level ("Set Potenza") | `power` / `SET`, `MIN`, `MAX`, `ENABLE` | NOT FOUND | NOT FOUND | as above | as above | integer level, `set_min`..`set_max` (NOT FOUND); upper bound demonstrably >= 4 because `power > 3` is a branch in `MultifireRegister` | `lang/it.json:1144`, `1124`, `1120`, `1116`, `js/agua/registers/MultifireRegister.js:53-58` |
| d | Power on/off (standby) | `status_managed` / the group's `GET` record is selected explicitly and written (`getRegisterData()` falls back SET -> ENABLE -> GET when no SET exists); value chosen by enum description `"ON"` / `"OFF"` | NOT FOUND | NOT FOUND | as above | as above | enum: raw value = `enc_val[].value` whose `description` for the current language equals `"ON"` or `"OFF"`; concrete numbers NOT FOUND (they live in `enc_val`, server data) | `js/agua/Device.js:990-1006`, `js/agua/Register.js:488-509`, `js/agua/NativeRegister.js:326-336`, `497-519` |
| d2 | Status readback used to render on/off | `status` / `GET`; enable lists `status_managed_on`, `status_managed_off` | NOT FOUND | NOT FOUND | as above | as above | value is `off` when it is in `status_managed_on.enable_val`, `on` when in `status_managed_off.enable_val` (naming is inverted in source, see excerpt below) | `js/agua/Device.js:1017-1033`, `lang/it.json:1168`, `1172`, `1176`, `1180` |
| e | Operating mode eco / comfort / manual / auto | NOT FOUND as a single mode register. What exists are separate feature registers: `natural_mode_type` (0 = manual, 1 = timed), `natural_mode_manual` (0/1), `natural_mode_new` (1 = new natural mode), `multifire_modality` (ENABLE == 1), `power_auto`, `power_night`, `multiconfort`, `eco_stop` | NOT FOUND | NOT FOUND | as above | as above | 0/1 flags and (for `eco_stop`) an unknown encoding | `js/agua/registers/NaturalModeRegister.js:137-156`, `241-269`, `js/agua/Device.js:1362-1383`, `js/agua/registers/Multifire2Register.js:39-47`, `lang/it.json:1112`, `1140`, `1000` |
| f | Chrono thermostat enable | `chrono_general_enable` / `SET` (0/1); `chrono_week_enable` / `SET` (0/1); `chrono_pN_enable` / `SET` (0/1) | NOT FOUND | NOT FOUND | as above | as above | exactly `1` = enabled, `0` = disabled | `js/agua/Chronos.js:122-129`, `200-207`, `js/agua/ChronosProgram.js:100-110`, `lang/it.json:596`, `984`, `628` |

Evidence for the inverted status naming, verbatim:

```js
// js/agua/Device.js:1017-1033
Device.prototype.getPowerStatus = function()
{
	var
		init = this.getRegisterByKey( 'status_managed_on' ).getMainNativeRegister().getEnabledValues(),
		stop = this.getRegisterByKey( 'status_managed_off' ).getMainNativeRegister().getEnabledValues(),
		value = this.getRegisterByKey( 'status' ).getMainNativeRegister().getValue()
	;
	if( it.omniaweb.utils.inArray( value, init ) )
	{
		return Device.POWER_STATUS_OFF;
	}
	if( it.omniaweb.utils.inArray( value, stop ) )
	{
		return Device.POWER_STATUS_ON;
	}
	return Device.POWER_STATUS_UNDEFINED;
};
```

`status_managed_on`/`status_managed_off` are `ENABLE`-type records of two *different* groups
(`status_managed_on_enable`, `status_managed_off_enable` in `lang/it.json:1180`, `1176`); their
`enable_val` lists name the `status` values that mean "off" and "on" respectively. A port must keep
that mapping as data, not as a hardcoded polarity.

Power on/off write path, verbatim:

```js
// js/agua/Device.js:990-1006
Device.prototype.setPowered = function( powered )
{
	var
		// get the register
		  register = this.getRegisterByKey( 'status_managed' )
		// get the native register
		, native = register.getRegisterByType( it.omniaweb.agua.Register.TYPE_GET )
		// get the correct key
		, key = powered ? 'ON' : 'OFF'
		// get the correct value
		, value = native.getValueByLanguageKey( key )
	;
	// set the new value
	native.setValue( value );
	// update register
	return register.update();
};
```

`getValueByLanguageKey()` scans `enc_val` for an entry whose description in the current language
equals the literal `"ON"` / `"OFF"` (`js/agua/NativeRegister.js:326-336`), so the register's enum
descriptions in the cloud database are literally `ON` / `OFF` regardless of UI language.

### 2.2 Value encodings that *are* defined in the app

These are the numeric conventions a Kotlin port can implement today; each is source-proven.

| Encoding | Rule | Citation |
|---|---|---|
| Boolean / enable flag | write `1` for enabled, `0` for disabled | `js/agua/Chronos.js:126`, `204`; `js/agua/ChronosProgram.js:104`; `js/agua/registers/NaturalModeRegister.js:265` |
| Natural-mode / chrono clock value | `value = hours * 6 + minutes / 10` -> 10-minute resolution, 0..143 for 00:00..23:50 | `js/agua/registers/NaturalModeRegister.js:424` |
| Same, decoded | `hours = floor(value * 10 / 60)`, `minutes = (value * 10) % 60` | `js/agua/registers/NaturalModeRegister.js:360-362`; `js/agua/ChronosProgram.js:252-254` |
| Time disabled sentinel | `144` means `--:--` (no start / no stop) | `js/agua/registers/NaturalModeRegister.js:34-35`; `js/agua/ChronosProgram.js:35-36` |
| Chrono disabled sentinel detection | a start/stop register is "disabled-capable" when its `set_max === 144` | `js/agua/ChronosProgram.js:428-445` |
| Chrono day flags | one `SET` per weekday, `1` = active, order monday..sunday | `js/agua/Device.js:869`, `910-912`; `js/agua/ChronosProgram.js:349-357` |
| Natural-mode type | `0` = manual, `1` = timed | `js/agua/registers/NaturalModeRegister.js:137-156`, `226` |
| Quality range (pellet/combustion) | `raw = display + 5`, so display range is `raw` 0..10 -> -5..+5 | `js/agua/registers/QualityRangeRegister.js:29`, `49`, `63`, `77`, `136` |
| Temperature display | celsius `#`, fahrenheit `int(#*9/5)+32`, `int` truncation | `lang/it.json:1558`, `1565` |
| Disabled/absent registers | group is "enabled" only if the ENABLE value is in `enable_val`, and an empty `enable_val` implicitly means `1` | `js/agua/Register.js:699-711` |
| Value plausibility | response all equal to `32768` = inactivity error, not data | `js/it.omniaweb.config.js:58`; `js/agua/Device.js:2726-2741` |

### 2.3 Where a port must get the numbers

The fields a Kotlin `RegisterDefinition` needs are exactly those read at
`js/agua/NativeRegister.js:157-160`, `255-258`, `213-216`, `227-230`, `241-244`, `269-272`,
`283-286`, `87`, `141`, `464-467`, `311-314`, `497-519`. They arrive in
`deviceGetRegistersMap -> device_registers_map.registers_map[0].registers[]`
(`js/agua/Device.js:2532-2535`) and are re-used from a local cache
(`js/agua/Device.js:2462-2490`). A reimplementation that hardcodes stove offsets instead of loading
this map will diverge on every firmware/model variant.

Recommended Kotlin shape (mechanical translation of the above, not a source claim):

```kotlin
data class RegisterDefinition(
    val regKey: String, val regKeyGroup: String, val regType: String,
    val offset: Int, val mask: Int,
    val bigEndian: Boolean, val isWord: Boolean,
    val formula: String, val formulaInverse: String,
    val setMin: Int, val setMax: Int, val step: Int,
    val isHex: Boolean, val isTemperature: Boolean,
    val encVal: List<EnumValue>, val enableVal: List<EnumValue>,
)

data class RegisterWrite( // one entry of the five parallel arrays
    val item: Int, val mask: Int, val value: Int?, val endianess: String, val bitdata: Int,
) {
    companion object { fun of(def: RegisterDefinition, raw: Int?) =
        RegisterWrite(def.offset, def.mask, raw,
                      if (def.bigEndian) "B" else "L", if (def.isWord) 16 else 8) }
}

fun encodeWrite(v: Int, def: RegisterDefinition): Int =     // js/agua/NativeRegister.js:135-146
    if (def.isHex) evalExpr(def.formulaInverse.replace("#", v.toString())).toInt(16)
    else evalExpr(def.formulaInverse.replace("#", v.toString())).toInt()

fun decodeRead(raw: Int, def: RegisterDefinition): Double =  // js/agua/NativeRegister.js:85-88
    evalExpr(def.formula.replace("#", (raw and def.mask).toString())).toDouble()
```

---

## 3. Worked example - set fan speed = 2 and setpoint = 22 C

### 3.1 Inputs taken from the register map

At runtime the fetched map yields, for the two groups (values are placeholders because the real
ones are server data - see section 4):

| Group | reg_type | `offset` | `mask` | `is_word` | `big_endian` | `formula` | `formula_inverse` |
|---|---|---|---|---|---|---|---|
| `vent_main` | `SET` | `O_fan` | `M_fan` | false (assumed for an 8-bit level) | false | `#` | `#` |
| `temp_air` | `SET` | `O_set` | `M_set` | false (assumed) | false | `#` or `#/10` (NOT FOUND) | `#` or `#*10` (NOT FOUND) |

The only `vent_main` artefact in the bundle is the `SET` translation
(`lang/it.json:1272`), and `Register.prototype.setValue` only mutates a group's `SET` record
(`js/agua/Register.js:299-306`), so a fan-speed write is a plain single-register update.

### 3.2 Step 1 - engineering value to raw value

`Register.prototype.setValue(2)` -> SET native register -> `NativeRegister.prototype.setValue`
(`js/agua/Register.js:299-306`, `js/agua/NativeRegister.js:135-146`):

* fan: `eval("#" with '#'=2)` = `2`; `is_hex` false -> stored `2`.
* setpoint: `eval(formula_inverse with '#'=22)`.
  * if `formula_inverse` is `#`   -> `22`
  * if `formula_inverse` is `#*10` -> `220`   (a 0.1 C register; this specific string is NOT FOUND)
  * if `formula_inverse` is `#*2`  -> `44`    (0.5 C register; NOT FOUND)

No mask is applied here, and no offset arithmetic is done by the app.

### 3.3 Step 2 - assemble the five arrays

Order = order of `addRegisterData()` calls (fan first, then setpoint), `js/agua/RegisterData.js:59-70`:

```
Items     = [ O_fan,      O_set     ]
Masks     = [ M_fan,      M_set     ]
Values    = [ 2,          22 | 220  ]
Endianess = [ "L",        "L"       ]   // from big_endian: js/agua/NativeRegister.js:269-272
BitData   = [ 8,          8         ]   // from is_word:    js/agua/NativeRegister.js:283-286
```

### 3.4 Step 3 - the exact request body

`Device.update(data)` merges the payload over the defaults `Protocol: "RWMSmaster"`,
`BitData: 8`, `Endianess: "L"` (`js/agua/Device.js:2371-2388`); the brand layer appends `language`
(`js/agua/agua.js:922-925`) and POSTs to `https://piazzetta-iot.app2cloud.it/api/bridge/endpoint/`
with headers `method: POST`, `url: deviceRequestWriting`, `CustomerCode: 458632`,
`IdBrand: 1`, `brand: piazzetta`, `authorization: <token>`, `ApplicationVersion: 1.9.91`
(`js/agua/agua.js:891-903`, `941-956`; `js/it.omniaweb.config.js:39`, `44-48`).

Body, byte-for-byte (JSON as `JSON.stringify` emits it, key order as the app builds the object):

```json
{"id_product":<int>,"id_device":<int>,"Protocol":"RWMSmaster","BitData":[8,8],"Endianess":["L","L"],"registration_number":"<serial>","Items":[<O_fan>,<O_set>],"Masks":[<M_fan>,<M_set>],"Values":[2,22],"language":"it"}
```

With the 0.1 C variant of the setpoint formula the only change is `"Values":[2,220]`.

Value bytes, for the cases the map can specify (`Values` are decimal JSON integers; the width and
byte order below are what `BitData` / `Endianess` declare for that entry):

| Entry | `BitData` | `Endianess` | decimal | hex | bytes on the wire |
|---|---|---|---|---|---|
| fan = 2 | 8 | `L` | 2 | `0x02` | `02` |
| fan = 2 | 16 | `L` | 2 | `0x0002` | `02 00` |
| fan = 2 | 16 | `B` | 2 | `0x0002` | `00 02` |
| setpoint = 22 (identity formula) | 8 | `L` | 22 | `0x16` | `16` |
| setpoint = 22 (identity formula) | 16 | `L` | 22 | `0x0016` | `16 00` |
| setpoint = 22 with `formula_inverse "#*10"` | 16 | `L` | 220 | `0x00DC` | `DC 00` |
| setpoint = 22 with `formula_inverse "#*10"` | 16 | `B` | 220 | `0x00DC` | `00 DC` |

There is no checksum, no framing and no base64 in the cloud path: the payload *is* the JSON above,
and the bridge/Navel turns each `(Items[i], Masks[i], Values[i], Endianess[i], BitData[i])` tuple
into the bus transaction.

### 3.5 Step 4 - verifying the write by reading back

Read is a different method with the same tuple vocabulary minus masks:
`deviceGetBufferReading` with `BufferId: 1` (`js/agua/Device.js:2565-2578`,
`js/it.omniaweb.config.js:87`), polled via `deviceJobStatus` until `completed`
(`js/agua/Device.js:2625-2660`), whose `jobAnswerData` is `{Items:[...], Values:[...]}` and is
applied by offset (`js/agua/Device.js:2699-2713`). Example for a 0.1 C setpoint register:

```
jobAnswerData = {"Items":[<O_fan>,<O_set>],"Values":[2,215]}
fan      : 2  & M_fan -> eval("#" with 2)   = 2      -> "2"
setpoint : 215 & M_set -> eval("#/10" with 215) = 21.5 -> is_temperature ->
           eval("#" with 21.5) = 21.5 -> formatted "21.5 C" (suffix from js/agua/NativeRegister.js:544-553)
```

---

## 4. NOT FOUND

Items the task asked for that genuinely do not exist in `assets/www` (verified by grepping all 143
`js/*.js` files plus the `lang/*.json` bundles):

1. **Any numeric register offset.** The only occurrences of "offset" in app code are the field
   plumbing: `js/agua/NativeRegister.js:157-159`, `js/agua/Register.js:182-193`,
   `js/agua/RegisterData.js:65`, `js/agua/Device.js:547-562`, `2709`. No literal address table exists.
2. **Any numeric register mask.** Same story; `mask` is only read at `js/agua/NativeRegister.js:257`
   and consumed at `:87`.
3. **Fan-speed count / range (1..5, 0..6 or anything else).** No `vent`/`fan` identifier appears in
   app code at all: `grep -rnw -i -e vent -e fan js/agua js/brands 'js/it.omniaweb.*.js' js/plugins`
   returns 0 lines (the 932 substring hits are all `event`/`prevent`); the only ventilation artefact
   is the translation key
   `vent_main_set` ("Set Vent. Ambiente") at `lang/it.json:1272` and the chrono fan keys
   (`chrono_pN_fan_set`, `lang/it.json:632` for P1). The range is `set_min`/`set_max` server data.
4. **Setpoint range (7..40 C or similar) and its -1 (or any) offset.** No temperature bound appears
   in app code; the UI slider is built from `getMinimumValue()`/`getMaximumValue()`
   (`js/it.omniaweb.widgets.js:1106-1108`) which read `set_min`/`set_max`
   (`js/agua/NativeRegister.js:213-230`). No "7", "40", "-1" or 0.5/0.1 step constant is tied to
   `temp_air` anywhere in the bundle. Whether the register is 0.1 C or 1 C resolution is
   `formula_inverse` server data.
5. **Power-level range (1..5).** Only a lower bound of the *observed* minimum is provable: `power > 3`
   is a branch condition (`js/agua/registers/MultifireRegister.js:55`), so at least 4 levels exist.
   No hardcoded count. `power_min` / `power_max` are separate registers (`lang/it.json:1124`, `1120`),
   i.e. the device itself is asked for the limits.
6. **The numeric encodings of `status_managed` = ON / OFF.** They live in the register's `enc_val`
   list in the cloud database, addressed only by description text
   (`js/agua/Device.js:998`, `js/agua/NativeRegister.js:326-336`). The app never contains the numbers.
7. **The numeric values of `status_managed_on` / `status_managed_off` `enable_val` lists.** Server data
   (`js/agua/NativeRegister.js:366-378`; used at `js/agua/Device.js:1020-1021`).
8. **A single "operating mode" register for eco / comfort / manual / auto.** Not present. The app
   models only: `natural_mode_type` (0 manual / 1 timed), `natural_mode_manual` (0/1),
   `natural_mode_new` (0/1), `multifire_modality` (ENABLE == 1), `power_auto`, `power_night`,
   `multiconfort`, `eco_stop` (opaque "Set ECO_STOP", `lang/it.json:1000`). No eco/comfort
   enumerated mode register is referenced by any app code path.
9. **The semantics/unit of `eco_stop`.** Only a label exists (`lang/it.json:1000-1003`); no code
   reads it, so neither its encoding nor its range can be stated.
10. **Any binary framing, checksum, or base64 register payload.** `grep -rn -i -e base64 -e atob -e btoa js`
    returns 0 hits; the 41 hits elsewhere in `www` are all vendored Cordova/lib code
    (`cordova.js`, `cordova-js-src/exec.js`, `libs/materialize/*`, `libs/formvalidation/*`,
    `libs/qrcode-generator/qrcode.js`, `plugins/cordova-plugin-file/www/FileReader.js`,
    `plugins/cordova-plugin-ble-central/www/ble.js`). The only base64 that touches stove data is the
    inbound BLE notification wrapper, which the Cordova plugin decodes to an `ArrayBuffer` before the
    app UTF-8-decodes it (`plugins/cordova-plugin-ble-central/www/ble.js:27-33`,
    `js/plugins/it.omniaweb.plugins.ble.central.js:156`); outbound BLE is the UTF-8 text of the JSON
    envelope, chunked at `bleMessageChunkSize: 20` (`js/it.omniaweb.config.js:90`,
    `js/plugins/it.omniaweb.plugins.ble.central.js:145`, `787-864`). Cloud register values travel as
    JSON numbers (`js/agua/RegisterData.js:180-189`, `js/agua/Device.js:2699-2713`).
11. **The `Protocol`/`BufferId` semantics.** `RWMSmaster` (`js/agua/Device.js:2381`) and `BufferId: 1`
    (`js/it.omniaweb.config.js:87`) are transmitted but never interpreted by the app; the polling
    frequency is documented on the BLE side as `Freq: 3`
    (`js/it.omniaweb.bluetoothdevice.js:333`), with the cloud status-poll interval coming from
    configuration item `statusPollingFrequency`, default `300` ms (`js/agua/Device.js:2649`).

### How to fill the gaps

Fetch `deviceGetRegistersMap` for the device (`js/agua/Device.js:2513-2535`) and read, for the
groups `vent_main`, `temp_air`, `power`, `status_managed`, `status_managed_on`, `status_managed_off`,
`chrono_general_enable`, `chrono_week_enable`, `chrono_pN_enable`: `offset`, `mask`, `big_endian`,
`is_word`, `formula`, `formula_inverse`, `set_min`, `set_max`, `step`, `is_hex`, `is_temperature`,
`enc_val`, `enable_val`. That response - not the APK - is the authoritative stove control table, and
the app proves it by reading exactly those fields at the lines cited in section 2.3.
