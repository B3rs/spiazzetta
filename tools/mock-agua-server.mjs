// Mock Agua bridge that mirrors a real Piazzetta P220M register map, so every control can be
// exercised without touching a real stove. Masked writes are honoured like the real controller.
const PORT = Number(process.env.MOCK_PORT ?? 8099);

const DAY_FLAGS = [
  ["monday", 1], ["tuesday", 2], ["wednesday", 4], ["thursday", 8],
  ["friday", 16], ["saturday", 32], ["sunday", 64],
];

const state = {
  status: 0,
  setpoint: 20,
  room: 21.5,
  flue: 31,
  ductRight: 20,
  ductLeft: 19,
  naturalMode: 1,
  multifire: 1,
  power: 1,
  serviceHours: 908,
  serviceThreshold: 2000,
  clockHour: 12,
  clockMinute: 30,
  pelletOk: 1,
  sharedFlags: 0,
  qualityLow: 6,
  qualityHigh: 7,
  chrono: {},
  acceptsWrites: true,
  log: [],
};

for (let n = 1; n <= 6; n += 1) {
  state.chrono[n] = { enable: 0, start: 42, stop: 51, temperature: 20, power: 3, days: 0b0011111 };
}

const reg = (group, key, type, offset, extra = {}) => ({
  reg_key_group: group,
  reg_key: key,
  reg_type: type,
  offset,
  mask: extra.mask ?? 255,
  formula: extra.formula ?? "#",
  formula_inverse: extra.formulaInverse ?? "#",
  set_min: extra.min,
  set_max: extra.max,
  is_word: extra.word ?? false,
  big_endian: false,
  is_temperature: extra.temperature ?? false,
  enc_val: extra.encVal,
  enable_val: extra.enableVal,
});

const registers = [
  reg("status", "status_get", "GET", 33, {
    encVal: [
      { value: 0, lang: "it_IT", description: "OFF" },
      { value: 0, lang: "en_US", description: "OFF" },
      { value: 3, lang: "it_IT", description: "In funzione" },
      { value: 3, lang: "en_US", description: "Working" },
    ],
  }),
  reg("status_managed", "status_managed_get", "GET", 232, {
    encVal: [
      { value: 1, lang: "it_IT", description: "ON" },
      { value: 0, lang: "it_IT", description: "OFF" },
    ],
  }),
  reg("status_managed_on", "status_managed_on_enable", "ENABLE", 33, { enableVal: [{ value: 0 }] }),
  reg("status_managed_off", "status_managed_off_enable", "ENABLE", 33, { enableVal: [{ value: 3 }] }),
  reg("alarms", "alarms_enable", "ENABLE", 51, { enableVal: [{ value: 10 }] }),
  reg("alarms", "alarms_get", "GET", 51, {
    encVal: [{ value: 0, lang: "it_IT", description: "NO AL" }],
  }),
  reg("temp_air", "temp_air_get", "GET", 1, { formula: "#/2", formulaInverse: "#*2", temperature: true }),
  reg("temp_air", "temp_air_set", "SET", 1, { formula: "#/2", formulaInverse: "#*2", temperature: true, min: 7, max: 30 }),
  reg("temp_air2", "temp_air2_get", "GET", 68, { formula: "#/2", formulaInverse: "#*2" }),
  reg("sonda_ambiente", "sonda_ambiente_get", "GET", 33041, {
    encVal: [{ value: 1, lang: "it_IT", description: "ON" }],
  }),
  reg("temp_gas_flue", "temp_gas_flue_get", "GET", 2, {
    formula: "#+30",
    formulaInverse: "#-30",
    temperature: true,
  }),
  reg("canalization_dx", "canalization_dx_set", "SET", 32893, { min: 7, max: 30 }),
  reg("canalization_sx", "canalization_sx_set", "SET", 33025, { min: 7, max: 30 }),
  reg("natural_mode_new", "natural_mode_new_set", "SET", 32836, {
    formula: "#/16",
    formulaInverse: "#*16",
    min: 0,
    max: 1,
  }),
  reg("multifire_1", "multifire_1_set", "SET", 32897, { min: 0, max: 3 }),
  reg("multifire_modality", "multifire_modality_get", "GET", 32844, {
    encVal: [{ value: 0, lang: "it_IT", description: "NORMAL" }],
  }),
  reg("power", "power_set", "SET", 52, { min: 1, max: 4 }),
  reg("real_power", "real_power_get", "GET", 52, { min: 0, max: 5 }),
  reg("ris_pellet_ok", "ris_pellet_ok_get", "GET", 32966, { mask: 65535, min: 0, max: 1 }),
  reg("ris_pellet_empty", "ris_pellet_empty_get", "GET", 57, { mask: 32, formula: "#/32", formulaInverse: "#*32" }),
  reg("ris_pellet_ris", "ris_pellet_ris_get", "GET", 57, { mask: 128, formula: "#/128", formulaInverse: "#*128" }),
  reg("ore_service", "ore_service_get", "GET", 32999, { mask: 65535, word: true }),
  reg("ore_service_soglia", "ore_service_soglia_get", "GET", 33034, { formula: "#*10+250", formulaInverse: "(#-250)/10" }),
  reg("p_quality_p12", "p_quality_p12_set", "SET", 32956, { min: 0, max: 10 }),
  reg("c_quality_p12", "c_quality_p12_set", "SET", 32960, { min: 0, max: 10 }),
  reg("therm_ext", "therm_ext_get", "GET", 32840, {
    encVal: [{ value: 0, lang: "it_IT", description: "MODULATE" }],
  }),
  reg("clock_hour", "clock_hour_get", "GET", 33017),
  reg("clock_minute", "clock_minute_get", "GET", 33018),
  ...[1, 2, 3, 4, 5, 6].map((i) => reg("fw_" + i, "fw_" + i + "_get", "GET", 59 + i)),
];

for (let n = 1; n <= 6; n += 1) {
  const prefix = "chrono_p" + n;
  const base = 34000 + n * 10;
  registers.push(
    reg(prefix + "_enable", prefix + "_enable_set", "SET", base, { min: 0, max: 1 }),
    reg(prefix + "_start", prefix + "_start_set", "SET", base + 1, { min: 0, max: 144 }),
    reg(prefix + "_stop", prefix + "_stop_set", "SET", base + 2, { min: 0, max: 144 }),
    reg(prefix + "_temperature", prefix + "_temperature_set", "SET", base + 3, { min: 7, max: 30 }),
    reg(prefix + "_power", prefix + "_power_set", "SET", base + 4, { min: 1, max: 4 }),
  );
  for (const [day, flag] of DAY_FLAGS) {
    registers.push(
      reg(prefix + "_day_" + day, prefix + "_day_" + day + "_set", "SET", base + 5, {
        mask: flag,
        formula: "#/" + flag,
        formulaInverse: "#*" + flag,
        min: 0,
        max: 1,
      }),
    );
  }
  registers.push(
    reg(prefix + "_multifire_1", prefix + "_multifire_1_set", "SET", base + 6, { min: 0, max: 3 }),
  );
}

const jobs = new Map();
let jobSeq = 0;

function chronoRaw(n, field) {
  const program = state.chrono[n];
  if (!program) return 0;
  switch (field) {
    case "enable": return program.enable;
    case "start": return program.start;
    case "stop": return program.stop;
    case "temperature": return program.temperature;
    case "power": return program.power;
    case "multifire_1": return program.multifire ?? 1;
    case "day": return program.days;
    default: return 0;
  }
}

function values() {
  const answer = {
    Items: [33, 232, 51, 1, 68, 33041, 2, 32893, 33025, 32836, 32897, 32844, 52, 32966, 57, 32999, 33034, 32840, 33017, 33018],
    Values: [
      state.status, 1, 0,
      Math.round(state.setpoint * 2),
      Math.round(state.room * 2),
      1,
      Math.round(state.flue - 30),
      state.ductRight,
      state.ductLeft,
      state.naturalMode * 16 + 2,
      state.multifire,
      0,
      state.power,
      state.pelletOk,
      state.sharedFlags,
      state.serviceHours,
      175,
      0,
      state.clockHour,
      state.clockMinute,
      6,
      7,
    ],
  };
  answer.Items.push(32956, 32960);
  answer.Values.length = 20;
  answer.Values.push(state.qualityLow, state.qualityHigh);
  for (let i = 1; i <= 6; i += 1) {
    answer.Items.push(59 + 1 + 0);
  }
  answer.Items = answer.Items.slice(0, answer.Values.length);
  // firmware characters arrive as their own offsets
  // Device.getFirmware builds FW-<fw1><fw2>-<fw3>-V-<fw4><fw5><fw6>
  const chars = ["A".charCodeAt(0), "R".charCodeAt(0), "1".charCodeAt(0), "1".charCodeAt(0), "4".charCodeAt(0), "1".charCodeAt(0)];
  for (let i = 0; i < 6; i += 1) {
    answer.Items.push(60 + i);
    answer.Values.push(chars[i]);
  }
  for (let n = 1; n <= 6; n += 1) {
    const base = 34000 + n * 10;
    answer.Items.push(base, base + 1, base + 2, base + 3, base + 4, base + 5);
    answer.Values.push(
      chronoRaw(n, "enable"),
      chronoRaw(n, "start"),
      chronoRaw(n, "stop"),
      chronoRaw(n, "temperature"),
      chronoRaw(n, "power"),
      chronoRaw(n, "day"),
    );
  }
  return answer;
}

function applyWrite(offset, mask, value) {
  if (offset === 1) state.setpoint = Math.round((value / 2) * 2) / 2;
  else if (offset === 32893) state.ductRight = value;
  else if (offset === 33025) state.ductLeft = value;
  else if (offset === 32836) state.naturalMode = value / 16 >= 1 ? 1 : 0;
  else if (offset === 32897) state.multifire = value;
  else if (offset === 52) state.power = value;
  else if (offset === 232) state.status = value === 1 ? 3 : 0;
  else if (offset === 57) state.sharedFlags = (state.sharedFlags & ~mask) | (value & mask);
  else if (offset === 32956) state.qualityLow = value;
  else if (offset === 32960) state.qualityHigh = value;
  else if (offset >= 34000) {
    const n = Math.floor((offset - 34000) / 10);
    const step = offset - (34000 + n * 10);
    const program = state.chrono[n];
    if (!program) return;
    if (step === 0) program.enable = value;
    else if (step === 1) program.start = value;
    else if (step === 2) program.stop = value;
    else if (step === 3) program.temperature = value;
    else if (step === 4) program.power = value;
    else if (step === 5) program.days = (program.days & ~mask) | (value & mask);
  }
}

const deviceList = {
  Success: true,
  device: [{
    id: "p220m", id_device: "77", id_product: "42", id_client: "mypiazzetta",
    mac: "AA:BB:CC:DD:EE:FF", product_serial: "P220M0001", name: "P220M (QA)",
    is_managed: true, product_info: { registration_number: "P220M0001" },
  }],
};

function handle(action, body) {
  if (action === "userLogin") {
    if (!body.email || !body.password) return { Success: false, Error: "1134" };
    return { Success: true, token: "qa-token", refresh_token: "qa-refresh" };
  }
  if (action === "appSignup") return { Success: true, id_app: "qa-app" };
  if (action === "userDetail") return { Success: true, User: { id: 11, agua_id: 22 } };
  if (action === "deviceList") return deviceList;
  if (action === "deviceGetRegistersMap") {
    return { Success: true, device_registers_map: { registers_map: [{ registers }] } };
  }
  if (action === "deviceGetBufferReading") {
    const id = "read-" + (++jobSeq);
    jobs.set(id, values());
    return { Success: true, idRequest: id };
  }
  if (action === "deviceRequestWriting") {
    state.log.push(body);
    if (state.acceptsWrites) {
      const items = body.Items ?? [];
      const masks = body.Masks ?? [];
      const vals = body.Values ?? [];
      for (let i = 0; i < items.length; i += 1) {
        applyWrite(Number(items[i]), Number(masks[i] ?? 255), Number(vals[i]));
      }
    }
    const id = "write-" + (++jobSeq);
    jobs.set(id, values());
    return { Success: true, idRequest: id };
  }
  if (action.startsWith("deviceJobStatus/")) {
    const id = action.slice("deviceJobStatus/".length);
    return { Success: true, jobAnswerStatus: "completed", jobAnswerData: jobs.get(id) ?? values() };
  }
  if (action === "__qa/ignores-writes") { state.acceptsWrites = false; return { ok: true }; }
  if (action === "__qa/accepts-writes") { state.acceptsWrites = true; return { ok: true }; }
  if (action === "__qa/state") return state;
  return { Success: false, Error: "9999" };
}

Bun.serve({
  port: PORT,
  hostname: "0.0.0.0",
  async fetch(request) {
    const url = new URL(request.url);
    let body = {};
    if (request.method !== "GET") {
      const text = await request.text();
      try { body = text ? JSON.parse(text) : {}; } catch { body = {}; }
    }
    const action = request.headers.get("url") ?? url.pathname.split("/").pop();
    const answer = handle(action, body);
    if (!action.startsWith("__qa") && action !== "deviceGetBufferReading" && action !== "deviceJobStatus/") {
      console.log(new Date().toISOString(), action, JSON.stringify(body).slice(0, 220));
    }
    return new Response(JSON.stringify(answer), { headers: { "Content-Type": "application/json" } });
  },
});

console.log("mock P220M bridge listening on http://0.0.0.0:" + PORT);
