
(() => {
  "use strict";

  const CTL = "/data/adb/modules/zapret-ksu/scripts/zapret-ctl.sh";
  const MARK_UPDATE = "\n---UPDATE---\n";
  const MARK_LOG = "\n---LOG---\n";
  const POLL_IDLE_MS = 5000;   
  const POLL_LOGS_MS = 3000;   
  const POLL_UPDATE_MS = 1500; 
  const LOG_LINES = 300;
  const COPY_LINES = 1000;

  const BOOL_KEYS = ["AUTO_NETWORK", "AUTO_NETWORK_START", "AUTO_STOP_NONE", "AUTO_START", "BACKUP_LISTS", "LOG_ENABLED", "GAME_FILTER_TCP", "GAME_FILTER_UDP", "QUIC_ENABLED", "BYPASS_PRIVATE", "BYPASS_VPN", "CONNTRACK_ACCT"];
  const AUTO_STRAT_KEYS = ["WIFI_STRATEGY", "MOBILE_STRATEGY"];
  const isStrategyKey = (k) => k === "STRATEGY" || AUTO_STRAT_KEYS.includes(k);
  const TEXT_KEYS = ["TCP_PORTS", "WIFI_IFACE", "MOBILE_IFACE"];
  const SELECT_KEYS = ["BACKEND", "ENGINE", "WIFI_ENGINE", "MOBILE_ENGINE"];
  const VALID_TEXT = /^[A-Za-z0-9,._+-]*$/;
  const STEP_ORDER = { all: ["nfqws1", "nfqws2", "presets", "strategies"] };

  const $ = (id) => document.getElementById(id);
  const $$ = (sel) => Array.from(document.querySelectorAll(sel));

  const S = {
    tab: "home",
    status: null,          
    update: null,          
    logText: "",
    logRendered: null,
    logFilter: "all",
    saved: {},
    inFlight: false,
    timer: null,
    busyPower: false,
    busyApply: false,
    lastUpdateState: null,
  };


  let cbSeq = 0;
  function exec(cmd) {
    return new Promise((resolve) => {
      if (!window.ksu || typeof window.ksu.exec !== "function") {
        resolve({ errno: -1, stdout: "", stderr: "root manager WebUI bridge not available" });
        return;
      }
      const name = `__ksu_cb_${Date.now()}_${cbSeq++}`;
      window[name] = (errno, stdout, stderr) => {
        delete window[name];
        resolve({ errno: Number(errno), stdout: stdout || "", stderr: stderr || "" });
      };
      try {
        window.ksu.exec(cmd, "{}", name);
      } catch (e) {
        delete window[name];
        resolve({ errno: -1, stdout: "", stderr: String(e) });
      }
    });
  }

  const ctl = (args) => exec(`sh "${CTL}" ${args}`);

  const MARK_BC = "\n---BLOCKCHECK---\n";
  const MARK_BCRES = "\n---BCRESULTS---\n";

  function splitSnapshot(text) {
    const pos = (m) => text.indexOf(m);
    const cut = (from, marker) => {
      const i = pos(marker);
      return i >= 0 ? text.slice(from, i) : "";
    };
    const iu = pos(MARK_UPDATE), ib = pos(MARK_BC), ir = pos(MARK_BCRES), il = pos(MARK_LOG);
    const status = iu >= 0 ? text.slice(0, iu) : text;
    const update = iu >= 0 && ib >= 0 ? text.slice(iu + MARK_UPDATE.length, ib) : "";
    const bc = ib >= 0 && ir >= 0 ? text.slice(ib + MARK_BC.length, ir) : "";
    const bcres = ir >= 0 && il >= 0 ? text.slice(ir + MARK_BCRES.length, il) : "";
    const log = il >= 0 ? text.slice(il + MARK_LOG.length) : "";
    return { status, update, bc, bcres, log };
  }
  const quote = (v) => `"${String(v).replace(/(["\\$`])/g, "\\$1")}"`;

  function parseKV(text) {
    const out = {};
    for (const line of text.split("\n")) {
      const i = line.indexOf("=");
      if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
    return out;
  }

  function human(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return `${n} B`;
    if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1048576).toFixed(2)} MB`;
  }

  function clock(seconds) {
    const s = Number(seconds) || 0;
    return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
  }


  let toastTimer = null;
  function toast(msg, ms = 3200) {
    const el = $("toast");
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("show"), ms);
  }

  function setText(id, value) {
    const el = $(id);
    if (el.textContent !== value) el.textContent = value;
  }

  function showTab(name) {
    S.tab = name;
    for (const t of $$(".tab")) t.classList.toggle("is-active", t.id === `tab-${name}`);
    for (const b of $$(".nav-btn")) b.classList.toggle("is-active", b.dataset.tab === name);
    if (name === "logs") renderLog(true);
    schedule();
    window.scrollTo(0, 0);
  }

  function renderStatus() {
    const s = S.status;
    if (!s) return;
    const saved = S.saved || {};
    const auto = saved.AUTO_NETWORK === "1";
    const active = s.firewall_active === "1";
    const waiting = !active && auto && s.auto_wanted === "1" && s.auto_profile === "none";
    const hero = $("hero");
    const state = s.firewall_active === undefined ? "unknown" : active ? "on" : "off";
    if (hero.dataset.state !== state) hero.dataset.state = state;
    setText("heroState", state === "unknown" ? "Unavailable" : active ? "Active" : waiting ? "Waiting for network" : "Stopped");
    if (active) {
      setText("heroSub", `${s.active_engine || ""} · ${s.active_strategy || ""}${auto ? ` · ${s.auto_profile || "no"} network` : ""}`);
    } else if (auto) {
      setText("heroSub", `Auto: ${s.auto_profile || "none"} → ${s.auto_engine || "-"} · ${s.auto_strategy || "-"}`);
    } else {
      setText("heroSub", "Start to intercept traffic");
    }
    $("powerBtn").textContent = active ? "Stop" : auto ? "Start (auto)" : "Start";
    setText("engineVal", s.active_engine || "-");
    setText("strategyVal", s.active_strategy || "-");
    setText("backendVal", s.firewall_backend || "none");
    setText("nfqwsVal", s.nfqws_running === "1" ? "running" : "not running");
    $("homeAuto").hidden = !auto;
    if (auto) {
      setText("autoWifiVal", `${saved.WIFI_ENGINE || "-"} · ${saved.WIFI_STRATEGY || "-"}`);
      setText("autoMobileVal", `${saved.MOBILE_ENGINE || "-"} · ${saved.MOBILE_STRATEGY || "-"}`);
      const now = s.auto_profile === "wifi" ? "Wi-Fi" : s.auto_profile === "mobile" ? "mobile data" : "no connection";
      setText("autoNowVal", `${now}${s.auto_engine ? ` → ${s.auto_engine} · ${s.auto_strategy}` : ""}`);
      setText("autoWatcherVal", s.auto_watcher === "alive" ? "running" : "not running (press Start, or re-enable the profile)");
    }
    setText("version", s.module_version ? `v${s.module_version}` : "");
    setText("v_nfqws1", s.nfqws1_version || "not installed");
    setText("v_nfqws2", s.nfqws2_version || "not installed");
    const presetsN = Number(s.presets_count) || 0;
    const stratsN = Number(s.strategies_count) || 0;
    setText("v_presets", presetsN ? `${presetsN} installed${s.presets_version ? `, updated ${s.presets_version}` : ""}` : "none installed");
    setText("v_strategies", stratsN ? `${stratsN} .bat files installed` : "none installed");
    if (!S.busyPower) $("powerBtn").disabled = false;
  }

  function renderLog(force) {
    if (S.tab !== "logs" && !force) return;
    const filter = S.logFilter;
    const q = $("logSearch").value.trim().toLowerCase();
    const key = `${filter}|${q}|${S.logText.length}|${S.logText.slice(-80)}`;
    if (!force && key === S.logRendered) return;
    S.logRendered = key;

    const lines = S.logText.split("\n").filter((line) => {
      if (q && !line.toLowerCase().includes(q)) return false;
      return matchesFilter(line, filter);
    });
    const view = $("logView");
    const nearBottom = view.scrollTop + view.clientHeight >= view.scrollHeight - 40;
    view.innerHTML = "";
    if (lines.length === 0) {
      view.textContent = S.logText ? "No lines match this filter." : "(no log yet)";
    } else {
      for (const line of lines) {
        const span = document.createElement("span");
        span.textContent = line + "\n";
        if (/ ERROR:/.test(line)) span.className = "e";
        else if (/ WARN:/.test(line)) span.className = "w";
        else if (/\[fetch\]/.test(line)) span.className = "f";
        view.appendChild(span);
      }
    }
    if ($("autoScroll").checked && (nearBottom || force)) view.scrollTop = view.scrollHeight;
  }

  function matchesFilter(line, filter) {
    switch (filter) {
      case "errors": return / (ERROR|WARN):/.test(line);
      case "svc":    return line.includes("[svc]");
      case "ctl":    return line.includes("[ctl]");
      case "fw":     return line.includes("[fw]");
      case "engine": return line.includes("[engine]");
      case "fetch":  return line.includes("[fetch]");
      default:       return true;
    }
  }

  function renderUpdate() {
    const u = S.update;
    const banner = $("updateBanner");
    const running = u && u.update_state === "running";
    banner.hidden = !running;

    if (u && u.update_state && u.update_state !== "idle" && u.update_state !== S.lastUpdateState) {
      S.lastUpdateState = u.update_state;
      if (u.update_state === "ok") toast("Update complete");
      if (u.update_state === "error") toast(`Update failed: ${u.update_message}`, 6000);
    }
    if (u && u.update_state && u.update_state !== "idle") {
      const label = u.update_state === "ok" ? "succeeded" : u.update_state === "error" ? "failed" : "running";
      setText("lastUpdate", `Last update (${u.update_target || "?"}) ${label}: ${u.update_message || ""}`);
    }
    if (!running) return;

    const targets = STEP_ORDER[u.update_target] || [u.update_target];
    const done = (u.update_done || "").split(/\s+/).filter(Boolean);
    setText("ubTitle", `Updating ${u.update_target}…`);
    setText("ubSub",
      `${u.update_step || "starting"} · ${u.update_message || ""} · ${human(u.update_bytes)} · ${clock(u.update_elapsed)}`);
    const list = $("ubSteps");
    list.innerHTML = "";
    for (const t of targets) {
      const li = document.createElement("li");
      li.textContent = (done.includes(t) ? "✓ " : t === u.update_step ? "● " : "○ ") + t;
      li.className = done.includes(t) ? "done" : t === u.update_step ? "current" : "";
      list.appendChild(li);
    }
    const idle = Number(u.update_idle_s) || 0;
    const warn = $("ubWarn");
    if (idle >= 45) {
      warn.hidden = false;
      warn.textContent = `No progress for ${clock(idle)}. Slow connections can take a few minutes on large files.`;
    } else {
      warn.hidden = true;
    }
  }

  function renderSettings(values) {
    for (const k of BOOL_KEYS) $(`s_${k}`).checked = values[k] === "1";
    for (const k of TEXT_KEYS) $(`s_${k}`).value = values[k] || "";
    for (const k of SELECT_KEYS) $(`s_${k}`).value = values[k] || "";
  }

  const IFACE_PRESETS = [
    { value: "any", label: "Any network" },
    { value: "wlan0", label: "Wi-Fi (wlan0)" },
    { value: "rmnet_data+", label: "Mobile data (rmnet_data+, most phones)" },
    { value: "ccmni+", label: "Mobile data (ccmni+, MediaTek)" },
  ];

  async function loadInterfaces(current) {
    const r = await ctl("interfaces");
    const detected = r.stdout.split("\n").map((x) => x.trim()).filter(Boolean);
    const sel = $("s_IFACE_SEL");
    sel.innerHTML = "";
    for (const p of IFACE_PRESETS) sel.append(new Option(p.label, p.value));
    for (const n of detected) {
      if (!IFACE_PRESETS.some((p) => p.value === n)) sel.append(new Option(`Detected: ${n}`, n));
    }
    sel.append(new Option("Custom name…", "__custom__"));
    setIfaceValue(current || "any");
  }

  function setIfaceValue(value) {
    const sel = $("s_IFACE_SEL");
    const known = Array.from(sel.options).some((o) => o.value === value);
    if (known) {
      sel.value = value;
      $("s_IFACE_CUSTOM").value = "";
    } else {
      sel.value = "__custom__";
      $("s_IFACE_CUSTOM").value = value;
    }
    syncIfaceCustom();
  }

  function syncIfaceCustom() {
    const custom = $("s_IFACE_SEL").value === "__custom__";
    $("ifaceCustomField").hidden = !custom;
  }

  function ifaceValue() {
    const sel = $("s_IFACE_SEL").value;
    return sel === "__custom__" ? $("s_IFACE_CUSTOM").value.trim() : sel;
  }

  function formValues() {
    const v = {};
    for (const k of BOOL_KEYS) v[k] = $(`s_${k}`).checked ? "1" : "0";
    for (const k of TEXT_KEYS) v[k] = $(`s_${k}`).value.trim();
    for (const k of SELECT_KEYS) v[k] = $(`s_${k}`).value;
    v.IFACE = ifaceValue();
    v.STRATEGY = $("s_STRATEGY").value;
    for (const k of AUTO_STRAT_KEYS) v[k] = $(`s_${k}`).value;
    return v;
  }

  function changedKeys() {
    const v = formValues();
    return Object.keys(v).filter((k) => v[k] !== (S.saved[k] || ""));
  }

  function updateSaveBar() {
    const dirty = changedKeys().length > 0;
    $("savebar").hidden = !dirty || S.tab !== "settings";
  }

  async function loadStrategies(engine, selected) {
    const r = await ctl(`strategies ${engine}`);
    const names = r.stdout.split("\n").map((x) => x.trim()).filter(Boolean);
    const sel = $("s_STRATEGY");
    sel.innerHTML = "";
    if (names.length === 0) {
      sel.append(new Option("(none installed; run Update)", ""));
    }
    for (const n of names) sel.append(new Option(n, n));
    if (selected && names.includes(selected)) sel.value = selected;
  }


  const LOCKED_TABS = ["home", "settings", "components"];

  function applyLock(running) {
    for (const name of LOCKED_TABS) {
      const sec = $(`tab-${name}`);
      sec.classList.toggle("locked", running);
      sec.inert = running;
      const note = sec.querySelector(".lock-note");
      if (note) note.hidden = !running;
    }
    $("savebar").hidden = running || $("savebar").hidden;
    $("openEditorBtn").disabled = running;
  }

  function renderSummary(rows, running) {
    const base = rows.find((l) => l.split("|")[0] === "__baseline__");
    const others = rows.filter((l) => l.split("|")[0] !== "__baseline__");
    const fmt = (l) => {
      const [, score, max, http, tls12, tls13, quic] = l.split("|");
      return `${score}/${max}  (HTTP ${http} · TLS1.2 ${tls12} · TLS1.3 ${tls13} · QUIC ${quic})`;
    };
    $("bcBaseline").textContent = base ? fmt(base) : (running ? "measuring…" : "-");
    const best = others[0];
    $("bcBestText").textContent = best ? `${best.split("|")[0]}  (${fmt(best).split("  ")[0]})` : (running ? "waiting for results…" : "-");
    $("bcTested").textContent = `${others.length} of ${(Number(S.bc.bc_total) || 1) - 1} strategies`;
    $("bcUseBestBtn").disabled = running || !best;
    S.bcBest = best ? best.split("|")[0] : "";
    $("bcSummary").hidden = !base && !best;
  }

  function renderBlockcheck() {
    const b = S.bc || {};
    const running = b.bc_state === "running";
    applyLock(running);
    const total = Number(b.bc_total) || 0;
    const done = Number(b.bc_done) || 0;
    $("bcTargetsCard").hidden = engineInForm() !== "nfqws2";
    $("bcStartBtn").disabled = running;
    $("bcCancelBtn").disabled = !running;
    $("bcProgressCard").hidden = !(running || (b.bc_state && b.bc_state !== "idle"));
    const pct = total ? Math.min(100, Math.round((done / total) * 100)) : 0;
    setText("bcPct", running ? `${pct}%  (${done}/${total})` : b.bc_state === "done" ? "finished" : b.bc_state || "");
    $("bcBarFill").style.width = `${pct}%`;
    setText("bcMsg", running ? `${b.bc_step || ""} · ${b.bc_message || ""} · ${clock(b.bc_elapsed)}`
                             : (b.bc_message || ""));

    const rows = S.bcRows || [];
    renderSummary(rows, running);
    const tbody = $("bcRows");
    const key = rows.join("\n");
    if (tbody.dataset.key === key) return;
    tbody.dataset.key = key;
    tbody.innerHTML = "";
    if (rows.length === 0) {
      const tr = document.createElement("tr");
      tr.innerHTML = '<td colspan="8" class="muted">No results yet. Start Blockcheck.</td>';
      tbody.appendChild(tr);
      setText("bcBest", "");
      return;
    }
    let rank = 0;
    let bestName = "";
    for (const line of rows) {
      const [name, score, max, http, tls12, tls13, quic, note] = line.split("|");
      const isBase = name === "__baseline__";
      const tr = document.createElement("tr");
      if (isBase) tr.className = "base";
      if (!isBase) {
        rank += 1;
        if (rank === 1) { tr.className = "best"; bestName = name; }
      }
      const cell = (text, cls) => {
        const td = document.createElement("td");
        if (cls) td.className = cls;
        td.textContent = text;
        return td;
      };
      tr.appendChild(cell(isBase ? "—" : String(rank)));
      tr.appendChild(cell(isBase ? "baseline (no bypass)" : name, "name"));
      tr.appendChild(cell(`${score}/${max}`));
      tr.appendChild(cell(`${http}`));
      tr.appendChild(cell(`${tls12}`));
      tr.appendChild(cell(`${tls13}`));
      tr.appendChild(cell(`${quic}`));
      const td = document.createElement("td");
      if (!isBase && note === "") {
        const btn = document.createElement("button");
        btn.className = "btn small";
        btn.textContent = "Use";
        btn.addEventListener("click", () => usePreset(name));
        td.appendChild(btn);
      } else if (note) {
        td.className = "muted";
        td.textContent = note;
      }
      tr.appendChild(td);
      tbody.appendChild(tr);
    }
    setText("bcBest", bestName ? `best: ${bestName}` : "");
  }

  async function startBlockcheck() {
    const n = $("bcCount").value;
    S.bc = { ...(S.bc || {}), bc_state: "running" };
    renderBlockcheck();
    const r = await ctl(`blockcheck start ${n}`);
    if (r.errno !== 0) {
      S.bc = { ...S.bc, bc_state: "idle" };
      renderBlockcheck();
      toast((r.stdout || r.stderr).trim() || "Blockcheck could not start", 6000);
      return;
    }
    toast("Blockcheck started. Engines stopped; traffic is interrupted while it runs.", 5000);
    await refresh();
  }

  async function cancelBlockcheck() {
    const r = await ctl("blockcheck cancel");
    toast(r.errno === 0 ? "Blockcheck cancelled" : (r.stderr || r.stdout).trim(), 5000);
    await refresh();
  }

  async function usePreset(name) {
    const engine = (S.bc && S.bc.bc_engine) || "nfqws2";
    const e = await ctl(`set ENGINE ${engine}`);
    const r = await ctl(`set STRATEGY ${quote(name)}`);
    if (e.errno !== 0 || r.errno !== 0) {
      toast((r.stderr || r.stdout || e.stderr).trim(), 6000);
      return;
    }
    toast(`Selected ${engine}: ${name}. Start the firewall to use it.`, 5000);
    await loadSettingsFromDevice();
    await refresh();
  }



  const engineInForm = () => $("s_ENGINE").value || (S.saved.ENGINE || "nfqws2");

  function syncEngineSections() {
    const e = engineInForm();
    $("z1Opts").hidden = e !== "nfqws1";
    $("gfCard").hidden = e !== "nfqws1";
    $("bcTargetsCard").hidden = e !== "nfqws2";
  }

  async function loadZapret1Options() {
    const [mode, lst, fk] = await Promise.all([ctl("ipset get"), ctl("fakes list"), ctl("fakes get")]);
    const modeName = (mode.stdout || "").trim();
    $("ipsetMode").value = ["none", "any", "loaded", "custom"].includes(modeName) ? modeName : "custom";
    const names = lst.stdout.split("\n").map((x) => x.trim()).filter(Boolean);
    const vals = parseKV(fk.stdout);
    for (const [id, key] of [["fakeDiscord", "discord"], ["fakeGame", "game"]]) {
      const sel = $(id);
      sel.innerHTML = "";
      for (const n of names) sel.append(new Option(n, n));
      if (vals[key] && names.includes(vals[key])) sel.value = vals[key];
    }
  }

  async function setIpsetMode() {
    const r = await ctl(`ipset set ${$("ipsetMode").value}`);
    toast(r.errno === 0 ? `ipset mode: ${$("ipsetMode").value}` : (r.stderr || r.stdout).trim(), 4000);
  }

  async function applyFakes() {
    const d = await ctl(`fakes set discord ${quote($("fakeDiscord").value)}`);
    const g = await ctl(`fakes set game ${quote($("fakeGame").value)}`);
    toast(d.errno === 0 && g.errno === 0 ? "Fake packets replaced. Restart the firewall to use them."
          : (d.stderr || d.stdout || g.stderr || g.stdout).trim(), 5000);
  }


  const ED = { tab: "lists", file: "", original: "", busy: false, files: [] };
  const EDITOR_TIMEOUT_MS = 20000;
  let edDiscardArmed = "";
  let closeArmed = false;

  function withTimeout(promise, ms) {
    return Promise.race([
      promise,
      new Promise((resolve) => setTimeout(() => resolve({ errno: -1, stdout: "", stderr: "timed out" }), ms)),
    ]);
  }

  const edCall = (args) => withTimeout(ctl(args), EDITOR_TIMEOUT_MS);

  function utf8ToB64(text) {
    const bytes = new TextEncoder().encode(text);
    let bin = "";
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin);
  }

  function b64ToUtf8(b64) {
    if (!b64) return "";
    const bin = atob(b64);
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  }

  function edRoot() {
    const nfqws1 = engineInForm() === "nfqws1";
    if (ED.tab === "lists") return nfqws1 ? "lists1" : "lists2";
    return nfqws1 ? "strat1" : "strat2";
  }

  function edStatus(msg) { setText("edStatus", msg); }

  function edDirty() { return $("edText").value !== ED.original; }

  function edRefresh() {
    const has = !!ED.file;
    const busy = ED.busy;
    $("edText").disabled = !has || busy;
    $("edSave").disabled = !has || !edDirty() || busy;
    $("edRevert").disabled = !has || !edDirty() || busy;
    $("edCopy").disabled = !has || busy;
    $("edDelete").disabled = !has || ED.tab !== "strats" || busy;
    $("edNew").disabled = busy;
    $("edImportBtn").disabled = busy;
    $("edPathBtn").disabled = busy;
    $("edTitle").textContent = has ? ED.file : "Select a file";
  }

  function edNameRow(mode, suggested) {
    ED.pendingNew = mode;
    $("edNameInput").value = suggested;
    $("edNameRow").hidden = false;
    $("edNameInput").focus();
  }

  function edNameRowClose() {
    ED.pendingNew = null;
    $("edNameRow").hidden = true;
  }

  async function edLoadList() {
    const r = await edCall(`file list ${edRoot()}`);
    ED.files = r.errno === 0 ? r.stdout.split("\n").map((x) => x.trim()).filter(Boolean) : [];
    if (r.errno !== 0) edStatus(`Could not list files: ${(r.stderr || r.stdout).trim()}`);
    edRenderList();
    edRefresh();
  }

  function edRenderList() {
    const q = $("edSearch").value.trim().toLowerCase();
    const ul = $("edFiles");
    ul.innerHTML = "";
    const shown = ED.files.filter((f) => !q || f.toLowerCase().includes(q));
    if (shown.length === 0) {
      const li = document.createElement("li");
      li.textContent = ED.files.length ? "No file matches the filter" : "No files here yet";
      li.style.cursor = "default";
      ul.appendChild(li);
    }
    for (const f of shown) {
      const li = document.createElement("li");
      li.textContent = f;
      if (f === ED.file) li.classList.add("is-on");
      li.addEventListener("click", () => edOpen(f));
      ul.appendChild(li);
    }
  }

  function edMayDiscard(target) {
    if (!edDirty()) return true;
    if (edDiscardArmed === target) {
      edDiscardArmed = "";
      return true;
    }
    edDiscardArmed = target;
    edStatus("Unsaved changes. Tap again to discard them.");
    return false;
  }

  async function edOpen(name) {
    if (!edMayDiscard(name)) return;
    ED.busy = true;
    edStatus("Loading…");
    edRefresh();
    try {
      const r = await edCall(`file read ${edRoot()} ${quote(name)}`);
      if (r.errno !== 0) {
        edStatus((r.stderr || r.stdout).trim() || "Could not open the file");
        return;
      }
      ED.file = name;
      ED.original = b64ToUtf8(r.stdout.trim());
      $("edText").value = ED.original;
      edStatus(`${ED.original.length} characters`);
      edDiscardArmed = "";
      edRenderList();
    } catch (e) {
      edStatus(`Could not open the file: ${e.message || e}`);
    } finally {
      ED.busy = false;
      edRefresh();
    }
  }

  async function uploadText(root, name, text) {
    const b64 = utf8ToB64(text);
    const CHUNK = 3000;
    const chunks = [];
    for (let i = 0; i < b64.length; i += CHUNK) chunks.push(b64.slice(i, i + CHUNK));
    let r = await edCall(`file write-begin ${root} ${quote(name)}`);
    if (r.errno !== 0) throw new Error((r.stderr || r.stdout).trim());
    for (let i = 0; i < chunks.length; i++) {
      edStatus(`Uploading ${i + 1} / ${chunks.length}…`);
      r = await edCall(`file write-chunk ${root} ${quote(name)} ${chunks[i]}`);
      if (r.errno !== 0) throw new Error((r.stderr || r.stdout).trim());
    }
    edStatus("Saving…");
    r = await edCall(`file write-commit ${root} ${quote(name)}`);
    if (r.errno !== 0) throw new Error((r.stderr || r.stdout).trim());
  }

  async function edSave() {
    if (!ED.file || !edDirty()) return;
    const text = $("edText").value;
    ED.busy = true;
    edRefresh();
    try {
      await uploadText(edRoot(), ED.file, text);
      ED.original = text;
      edStatus("Saved.");
      toast(`Saved ${ED.file}`);
    } catch (e) {
      edStatus(`Not saved: ${e.message || e}`);
      toast(`Save failed: ${e.message || e}`, 6000);
    } finally {
      ED.busy = false;
      edRefresh();
    }
  }

  async function edImportFile(file) {
    if (!file) return;
    const ext = edRoot() === "strat1" ? ".bat" : ".txt";
    if (!file.name.toLowerCase().endsWith(ext)) {
      toast(`Pick a ${ext} file for this editor`, 5000);
      return;
    }
    if (file.size > 262144) {
      toast("File is larger than 256 KB", 5000);
      return;
    }
    const text = await file.text();
    if (ED.tab === "strats" && ext === ".txt" && !/^--/m.test(text)) {
      toast("Not a zapret 2 preset: no --option lines", 6000);
      return;
    }
    if (ext === ".bat" && !/winws\.exe/i.test(text)) {
      toast("Not a zapret 1 strategy: no winws.exe line", 6000);
      return;
    }
    const base = file.name.slice(0, -ext.length);
    let name = file.name;
    let n = 2;
    while (ED.files.includes(name)) {
      name = `${base} (${n})${ext}`;
      n++;
    }
    ED.busy = true;
    edRefresh();
    try {
      await uploadText(edRoot(), name, text);
      toast(`Imported ${name}`);
      await edLoadList();
      await edOpen(name);
    } catch (e) {
      edStatus(`Import failed: ${e.message || e}`);
      toast(`Import failed: ${e.message || e}`, 6000);
    } finally {
      ED.busy = false;
      edRefresh();
    }
  }

  async function edImportPath() {
    const src = $("edPathInput").value.trim();
    if (!src) {
      toast("Type the full path of the file first", 4000);
      return;
    }
    ED.busy = true;
    edRefresh();
    try {
      const r = await edCall(`file import ${edRoot()} ${quote(src)}`);
      if (r.errno !== 0) {
        const msg = (r.stderr || r.stdout).trim() || "import failed";
        edStatus(msg);
        toast(msg, 6000);
        return;
      }
      const name = r.stdout.trim().replace(/^imported /, "");
      toast(`Imported ${name}`);
      $("edPathInput").value = "";
      await edLoadList();
      await edOpen(name);
    } catch (e) {
      edStatus(`Import failed: ${e.message || e}`);
    } finally {
      ED.busy = false;
      edRefresh();
    }
  }

  async function fillAutoStrategies(engineKey, stratKey, selected) {
    const engine = $(`s_${engineKey}`).value || "nfqws2";
    const r = await ctl(`strategies ${engine}`);
    const names = r.stdout.split("\n").map((x) => x.trim()).filter(Boolean);
    const sel = $(`s_${stratKey}`);
    sel.innerHTML = "";
    if (names.length === 0) sel.append(new Option("(none installed; run Update)", ""));
    for (const n of names) sel.append(new Option(n, n));
    if (selected && names.includes(selected)) sel.value = selected;
  }

  async function edConfirmName() {
    const name = $("edNameInput").value.trim();
    const mode = ED.pendingNew;
    if (!name || !mode) return;
    edNameRowClose();
    ED.busy = true;
    edRefresh();
    try {
      const r = mode === "new"
        ? await edCall(`file create ${edRoot()} ${quote(name)}`)
        : await edCall(`file copy ${edRoot()} ${quote(ED.file)} ${quote(name)}`);
      toast(r.errno === 0 ? `${mode === "new" ? "Created" : "Copied to"} ${name}` : (r.stderr || r.stdout).trim(), 4000);
      await edLoadList();
    } finally {
      ED.busy = false;
      edRefresh();
    }
  }

  function edNewFile() {
    const suggested = ED.tab === "lists" ? "my-list.txt"
      : (engineInForm() === "nfqws1" ? "my-strategy.bat" : "my-preset.txt");
    edNameRow("new", suggested);
  }

  function edCopyFile() {
    if (!ED.file) return;
    edNameRow("copy", ED.file.replace(/(\.[^.]+)$/, "_copy$1"));
  }

  async function edDeleteFile() {
    if (!ED.file || !armed($("edDelete"))) return;
    ED.busy = true;
    edRefresh();
    try {
      const r = await edCall(`file delete ${edRoot()} ${quote(ED.file)}`);
      toast(r.errno === 0 ? `Deleted ${ED.file}` : (r.stderr || r.stdout).trim(), 4000);
      ED.file = ""; ED.original = "";
      $("edText").value = "";
      await edLoadList();
    } finally {
      ED.busy = false;
      edRefresh();
    }
  }

  function openEditor() {
    if (S.bc && S.bc.bc_state === "running") { toast("Editing is locked while Blockcheck runs"); return; }
    setText("edEngine", engineInForm());
    $("editor").hidden = false;
    ED.file = ""; ED.original = "";
    $("edText").value = "";
    edNameRowClose();
    edStatus("");
    edLoadList();
  }

  function closeEditor() {
    if (edDirty() && !closeArmed) {
      closeArmed = true;
      edStatus("Unsaved changes. Tap Close again to discard them.");
      setTimeout(() => { closeArmed = false; }, 4000);
      return;
    }
    closeArmed = false;
    $("editor").hidden = true;
  }

  for (const b of document.querySelectorAll("[data-ed]")) {
    b.addEventListener("click", async () => {
      if (!edMayDiscard(b.dataset.ed)) return;
      ED.tab = b.dataset.ed;
      for (const c of document.querySelectorAll("[data-ed]")) c.classList.toggle("is-on", c === b);
      ED.file = ""; ED.original = "";
      $("edText").value = "";
      edNameRowClose();
      await edLoadList();
    });
  }


  async function loadTargets() {
    const [avail, sel] = await Promise.all([ctl("bc-available"), ctl("bc-lists show")]);
    const names = avail.stdout.split("\n").map((x) => x.trim()).filter(Boolean);
    const chosen = new Set(sel.stdout.split("\n").map((x) => x.trim()).filter(Boolean));
    const box = $("bcTargets");
    box.innerHTML = "";
    if (names.length === 0) {
      box.textContent = "No domain lists installed. Run Update components first.";
      return;
    }
    for (const n of names) {
      const label = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = n;
      cb.checked = chosen.has(n);
      label.appendChild(cb);
      label.appendChild(document.createTextNode(" " + n));
      box.appendChild(label);
    }
  }

  async function saveTargets() {
    const picked = Array.from($("bcTargets").querySelectorAll("input:checked")).map((c) => c.value);
    if (picked.length === 0) return clearTargets();
    const r = await ctl(`bc-lists set ${quote(picked.join(","))}`);
    toast(r.errno === 0 ? `Blockcheck targets: ${picked.join(", ")}` : (r.stderr || r.stdout).trim(), 5000);
  }

  async function clearTargets() {
    await ctl("bc-lists clear");
    toast("Blockcheck will use the built-in domains");
    await loadTargets();
  }



  async function refresh() {
    if (S.inFlight || document.hidden) return;
    S.inFlight = true;
    try {
      const logLines = S.tab === "logs" ? LOG_LINES : 40;
      const r = await ctl(`snapshot ${logLines}`);
      if (!r.stdout) {
        S.status = { firewall_active: undefined };
        setText("heroState", "Unavailable");
        setText("heroSub", r.stderr || "zapret-ctl did not respond");
        $("powerBtn").disabled = true;
        return;
      }
      const parts = splitSnapshot(r.stdout);
      const digest = r.stdout.length + ":" + r.stdout.slice(-200);
      S.idle = digest === S.lastDigest ? (S.idle || 0) + 1 : 0;
      S.lastDigest = digest;
      S.status = parseKV(parts.status);
      S.update = parseKV(parts.update);
      S.bc = parseKV(parts.bc);
      S.bcRows = parts.bcres.split("\n").map((l) => l.trim()).filter(Boolean);
      S.logText = parts.log.replace(/\n+$/, "");
      renderStatus();
      renderUpdate();
      renderBlockcheck();
      renderLog(false);
      setText("recentLog", S.logText.split("\n").slice(-8).join("\n") || "(no log yet)");
    } finally {
      S.inFlight = false;
      schedule();
    }
  }

  function currentInterval() {
    if (S.update && S.update.update_state === "running") return POLL_UPDATE_MS;
    if (S.bc && S.bc.bc_state === "running") return POLL_UPDATE_MS;
    const idle = S.idle || 0;
    if (S.tab === "logs") return idle > 4 ? 10000 : POLL_LOGS_MS;
    if (idle < 3) return POLL_IDLE_MS;
    if (idle < 9) return 10000;
    return 20000;
  }

  function schedule() {
    clearTimeout(S.timer);
    S.timer = null;
    if (document.hidden) return;
    S.timer = setTimeout(refresh, currentInterval());
  }

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      clearTimeout(S.timer);
      S.timer = null;
    } else {
      refresh();
    }
  });


  async function togglePower() {
    if (!S.status || S.status.firewall_active === undefined) return;
    const active = S.status.firewall_active === "1";
    const action = active ? "stop" : "start";
    S.busyPower = true;
    $("powerBtn").disabled = true;
    $("powerBtn").textContent = active ? "Stopping…" : "Starting…";
    toast(active ? "Stopping…" : "Starting… (nfqws2 can take a few seconds)", 8000);
    try {
      const r = await ctl(action);
      const last = (r.stdout || r.stderr).trim().split("\n").pop() || "";
      if (r.errno === 0) toast(active ? "Firewall stopped" : `Started. ${last}`, 4000);
      else toast(`${action} failed: ${last}`, 7000);
    } finally {
      S.busyPower = false;
      $("powerBtn").disabled = false;
      await refresh();
    }
  }

  async function applySettings() {
    const values = formValues();
    const keys = changedKeys();
    for (const k of keys) {
      if (isStrategyKey(k)) continue;
      if (!VALID_TEXT.test(values[k])) {
        toast(`Invalid value for ${k}`, 4000);
        return;
      }
    }
    if (keys.length === 0) return;
    $("applyBtn").disabled = true;
    try {
      for (const k of keys) {
        const value = isStrategyKey(k) ? quote(values[k]) : values[k];
        const r = await ctl(`set ${k} ${value}`);
        if (r.errno !== 0) {
          toast(`Failed to save ${k}: ${(r.stderr || r.stdout).trim()}`, 6000);
          await loadSettingsFromDevice();
          return;
        }
      }
      S.saved = { ...S.saved, ...values };
      if (S.status && S.status.firewall_active === "1") {
        toast("Saved. Restarting firewall…", 5000);
        const r = await ctl("restart");
        toast(r.errno === 0 ? "Saved and restarted" : `Restart failed: ${(r.stderr || r.stdout).trim()}`, 6000);
      } else {
        toast("Saved. Start the firewall to apply.");
      }
    } finally {
      $("applyBtn").disabled = false;
      updateSaveBar();
      await refresh();
    }
  }

  async function loadSettingsFromDevice() {
    const r = await ctl("dump");
    if (r.errno !== 0 || !r.stdout) {
      toast("Could not read settings");
      return;
    }
    const values = parseKV(r.stdout);
    S.saved = values;
    renderSettings(values);
    await loadInterfaces(values.IFACE || "any");
    await loadStrategies(values.ENGINE || "nfqws2", values.STRATEGY || "");
    syncEngineSections();
    if ((values.ENGINE || "nfqws2") === "nfqws1") await loadZapret1Options();
    await loadTargets();
    for (const [eng, st] of [["WIFI_ENGINE", "WIFI_STRATEGY"], ["MOBILE_ENGINE", "MOBILE_STRATEGY"]]) {
      await fillAutoStrategies(eng, st, values[st] || "");
    }
    updateSaveBar();
  }

  async function onEngineChange() {
    await loadStrategies($("s_ENGINE").value, "");
    syncEngineSections();
    if ($("s_ENGINE").value === "nfqws1") await loadZapret1Options();
    updateSaveBar();
  }

  async function startUpdate(target, label) {
    const r = await ctl(`update ${target}`);
    if (r.errno !== 0) {
      toast((r.stdout || r.stderr).trim() || "Update could not start", 6000);
      return;
    }
    toast(`Downloading ${label}…`, 3000);
    await refresh();
  }

  function armed(btn, ms = 4000) {
    if (btn.dataset.armed === "1") {
      btn.dataset.armed = "";
      btn.classList.remove("armed");
      btn.textContent = btn.dataset.label;
      return true;
    }
    btn.dataset.label = btn.textContent;
    btn.dataset.armed = "1";
    btn.classList.add("armed");
    btn.textContent = "Tap again to confirm";
    setTimeout(() => {
      if (btn.dataset.armed === "1") {
        btn.dataset.armed = "";
        btn.classList.remove("armed");
        btn.textContent = btn.dataset.label;
      }
    }, ms);
    return false;
  }

  async function clearLogs() {
    if (!armed($("clearLogsBtn"))) return;
    const r = await ctl("log clear");
    toast(r.errno === 0 ? "Logs cleared" : (r.stderr || r.stdout).trim(), 4000);
    S.lastDigest = "";
    await refresh();
  }

  async function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch (_) {  }
    }
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.setAttribute("readonly", "");
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand("copy"); } catch (_) { ok = false; }
    document.body.removeChild(ta);
    return ok;
  }

  async function copyLogs() {
    const r = await ctl(`snapshot ${COPY_LINES}`);
    if (!r.stdout) {
      toast("Could not read logs");
      return;
    }
    const { status, log: logs } = splitSnapshot(r.stdout);
    const report = `=== zapret-ksu status ===\n${status.trim()}\n\n=== zapret.log (last ${COPY_LINES} lines) ===\n${logs.trim()}\n`;
    const ok = await copyText(report);
    toast(ok ? "Logs copied to clipboard" : "Copy failed");
  }


  $("powerBtn").addEventListener("click", togglePower);
  $("applyBtn").addEventListener("click", applySettings);
  $("copyLogsBtn").addEventListener("click", copyLogs);
  $("s_ENGINE").addEventListener("change", onEngineChange);
  $("s_IFACE_SEL").addEventListener("change", () => { syncIfaceCustom(); updateSaveBar(); });
  $("s_IFACE_CUSTOM").addEventListener("input", updateSaveBar);
  $("s_STRATEGY").addEventListener("change", updateSaveBar);
  $("autoScroll").addEventListener("change", () => renderLog(true));
  $("logSearch").addEventListener("input", () => renderLog(true));

  for (const b of $$(".nav-btn")) b.addEventListener("click", () => showTab(b.dataset.tab));
  for (const b of $$("[data-goto]")) b.addEventListener("click", () => showTab(b.dataset.goto));
  for (const b of $$(".chip")) {
    b.addEventListener("click", () => {
      S.logFilter = b.dataset.filter;
      for (const c of $$(".chip")) c.classList.toggle("is-on", c === b);
      renderLog(true);
    });
  }

  $("bcStartBtn").addEventListener("click", startBlockcheck);
  $("bcTargetsSave").addEventListener("click", saveTargets);
  $("bcTargetsClear").addEventListener("click", clearTargets);
  $("ipsetMode").addEventListener("change", setIpsetMode);
  $("fakesApplyBtn").addEventListener("click", applyFakes);
  $("openEditorBtn").addEventListener("click", openEditor);
  $("edClose").addEventListener("click", closeEditor);
  $("edSearch").addEventListener("input", edRenderList);
  $("edText").addEventListener("input", edRefresh);
  $("edSave").addEventListener("click", edSave);
  $("edRevert").addEventListener("click", () => {
    $("edText").value = ED.original;
    edRefresh();
    edStatus("Reverted.");
  });
  $("edNew").addEventListener("click", edNewFile);
  $("edCopy").addEventListener("click", edCopyFile);
  $("edDelete").addEventListener("click", edDeleteFile);
  $("edImportBtn").addEventListener("click", () => $("edImportInput").click());
  $("edPathBtn").addEventListener("click", edImportPath);
  $("edPathInput").addEventListener("keydown", (e) => { if (e.key === "Enter") edImportPath(); });
  $("edImportInput").addEventListener("change", (e) => {
    const f = e.target.files[0];
    e.target.value = "";
    edImportFile(f);
  });
  $("s_WIFI_ENGINE").addEventListener("change", () => { fillAutoStrategies("WIFI_ENGINE", "WIFI_STRATEGY", ""); updateSaveBar(); });
  $("s_MOBILE_ENGINE").addEventListener("change", () => { fillAutoStrategies("MOBILE_ENGINE", "MOBILE_STRATEGY", ""); updateSaveBar(); });
  $("edNameOk").addEventListener("click", edConfirmName);
  $("edNameCancel").addEventListener("click", edNameRowClose);
  $("edNameInput").addEventListener("keydown", (e) => { if (e.key === "Enter") edConfirmName(); });
  $("clearLogsBtn").addEventListener("click", clearLogs);
  $("bcUseBestBtn").addEventListener("click", () => S.bcBest && usePreset(S.bcBest));
  $("bcCancelBtn").addEventListener("click", cancelBlockcheck);
  $("updAllBtn").addEventListener("click", () => startUpdate("all", "all components"));
  $("updNfqws2Btn").addEventListener("click", () => startUpdate("nfqws2", "nfqws2"));
  $("updNfqws1Btn").addEventListener("click", () => startUpdate("nfqws1", "nfqws1"));
  $("updPresetsBtn").addEventListener("click", () => startUpdate("presets", "presets"));
  $("updStrategiesBtn").addEventListener("click", () => startUpdate("strategies", "strategies"));

  for (const el of $$("#tab-settings input, #tab-settings select")) {
    el.addEventListener("input", updateSaveBar);
    el.addEventListener("change", updateSaveBar);
  }

  (async () => {
    showTab("home");
    await loadSettingsFromDevice();
    await refresh();
  })();
})();
