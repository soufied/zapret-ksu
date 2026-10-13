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

  const BOOL_KEYS = ["DOH_AUTO_START", "AUTO_NETWORK", "AUTO_NETWORK_START", "AUTO_STOP_NONE", "AUTO_START", "BACKUP_LISTS", "LOG_ENABLED", "GAME_FILTER_TCP", "GAME_FILTER_UDP", "QUIC_ENABLED", "BYPASS_PRIVATE", "BYPASS_VPN", "CONNTRACK_ACCT"];
  const AUTO_STRAT_KEYS = ["WIFI_STRATEGY", "MOBILE_STRATEGY"];
  const isStrategyKey = (k) => k === "STRATEGY" || AUTO_STRAT_KEYS.includes(k);
  const TEXT_KEYS = ["TCP_PORTS", "UDP_PORTS", "WIFI_IFACE", "MOBILE_IFACE"];
  const SELECT_KEYS = ["BACKEND", "ENGINE", "APP_MODE", "WIFI_ENGINE", "MOBILE_ENGINE"];
  const VALID_TEXT = /^[A-Za-z0-9,._+-]*$/;
  const STEP_ORDER = { all: ["nfqws1", "nfqws2", "presets", "strategies"] };

  const elCache = new Map();
  const $ = (id) => {
    let el = elCache.get(id);
    if (!el) { el = document.getElementById(id); if (el) elCache.set(id, el); }
    return el;
  };
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
  function toastKind(msg) {
    const s = String(msg);
    if (/fail|could not|cannot|can't|error|invalid|refus|unknown|not installed|no such|no provider|larger than|too large|not valid/i.test(s)) return "err";
    if (/tick|type the|give the|must|locked|already running|unchanged|pick /i.test(s)) return "warn";
    if (/saved|done|complete|started|selected|cleared|restored|deleted|copied|created|imported|turned|set to|applied|restarted|written|finished|updated|backup|changed|ready/i.test(s)) return "ok";
    return "info";
  }
  const TOAST_ICON = { ok: "✓", warn: "!", err: "✕", info: "i" };
  function hideToast() {
    clearTimeout(toastTimer);
    $("toast").classList.remove("show");
  }
  function toast(msg, ms = 3200, kind) {
    if (!msg || !String(msg).trim()) return;
    const k = kind || toastKind(msg);
    const el = $("toast");
    el.dataset.kind = k;
    el.innerHTML = "";
    const icon = document.createElement("span");
    icon.className = k === "busy" ? "t-ic spin" : "t-ic";
    icon.setAttribute("aria-hidden", "true");
    if (k !== "busy") icon.textContent = TOAST_ICON[k] || "i";
    const body = document.createElement("div");
    body.className = "t-body";
    const text = document.createElement("div");
    text.className = "t-msg";
    text.textContent = String(msg).trim();
    body.appendChild(text);
    const close = document.createElement("button");
    close.type = "button";
    close.className = "t-close";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";
    close.addEventListener("click", hideToast);
    el.append(icon, body, close);
    if (ms > 0) {
      const bar = document.createElement("i");
      bar.className = "t-timer";
      bar.style.animationDuration = `${ms}ms`;
      el.appendChild(bar);
    }
    el.classList.add("show");
    clearTimeout(toastTimer);
    if (ms > 0) toastTimer = setTimeout(hideToast, ms);
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
    const dnsTxt = s.doh_running === "1"
      ? `DoH · ${s.doh_count || 0} provider${s.doh_count === "1" ? "" : "s"}`
      : s.dns_mode === "hostname" ? `DoT · ${s.dns_host || "?"}` : "Off (system DNS)";
    setText("dnsHomeVal", dnsTxt);
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
    setText("v_dnscrypt", s.dnscrypt_version || "not installed");
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
    for (const btn of document.querySelectorAll(".nav-btn")) {
      if (LOCKED_TABS.includes(btn.dataset.tab)) btn.disabled = running;
    }
    $("savebar").hidden = running || $("savebar").hidden;
    $("openEditorBtn").disabled = running;
  }

  const dnsLabel = (d) => (d === "dot" ? "DoT" : d === "doh" ? "DoH" : "");

  function bcFmt(line) {
    const f = line.split("|");
    return `${f[1]}/${f[2]} · ${f[9] || "-"}/${f[10] || "-"} domains · HTTP ${f[3]} · TLS1.2 ${f[4]} · TLS1.3 ${f[5]} · QUIC ${f[6]}`;
  }

  function renderSummary(rows, running) {
    const base = rows.find((l) => l.split("|")[0] === "__baseline__");
    const baseDns = rows.find((l) => l.split("|")[0] === "__baseline_enc__");
    const others = rows.filter((l) => !l.startsWith("__baseline"));
    const presets = Math.max(0, (Number(S.bc.bc_total) || 0) - 2);
    $("bcBaseline").textContent = base ? bcFmt(base) : (running ? "measuring…" : "-");
    $("bcBaselineDns").textContent = baseDns
      ? `${bcFmt(baseDns)}`
      : (running ? "waiting…" : "not measured");
    setText("bcDnsNote", S.bc.bc_dns || "-");
    const best = others.find((l) => l.split("|")[8] === "");
    $("bcBestText").textContent = best ? `${best.split("|")[0]} · ${bcFmt(best)}` : (running ? "waiting for results…" : "-");
    $("bcTested").textContent = presets ? `${others.length} of ${presets} presets` : `${others.length} presets`;
    $("bcUseBestBtn").disabled = running || !best;
    S.bcBest = best ? best.split("|")[0] : "";
    $("bcSummary").hidden = !base && !best;
  }

  const bcEffectiveEngine = () => $("bcEngine").value || engineInForm();

  function renderBlockcheck() {
    const b = S.bc || {};
    const running = b.bc_state === "running" || S.bcStarting === true;
    applyLock(running);
    const total = Number(b.bc_total) || 0;
    const done = Number(b.bc_done) || 0;
    $("bcTargetsCard").hidden = bcEffectiveEngine() !== "nfqws2";
    $("bcStartBtn").disabled = running;
    $("bcCancelBtn").disabled = !running;
    $("bcProgressCard").hidden = !(running || (b.bc_state && b.bc_state !== "idle"));
    const pct = total ? Math.min(100, Math.round((done / total) * 100)) : 0;
    setText("bcPct", running ? `${pct}%  (${done}/${total})` : b.bc_state === "done" ? "finished" : b.bc_state || "");
    $("bcBarFill").style.width = `${pct}%`;
    setText("bcMsg", running ? `${b.bc_step || ""} · ${b.bc_message || ""} · ${clock(b.bc_elapsed)}`
                             : (b.bc_message || ""));

    const rows = S.bcRows || [];
    setText("bcResCount", rows.length ? `${rows.length} row${rows.length === 1 ? "" : "s"}` : "no results yet");
    renderSummary(rows, running);
    const tbody = $("bcRows");
    const key = rows.join("\n");
    if (tbody.dataset.key === key) return;
    tbody.dataset.key = key;
    tbody.innerHTML = "";
    if (rows.length === 0) {
      const tr = document.createElement("tr");
      tr.innerHTML = '<td colspan="11" class="muted">No results yet. Start Blockcheck.</td>';
      tbody.appendChild(tr);
      setText("bcBest", "");
      return;
    }
    let rank = 0;
    let bestName = "";
    for (const line of rows) {
      const [name, score, max, http, tls12, tls13, quic, avg, note, domOk, domTotal, dns] = line.split("|");
      const isBase = name.startsWith("__baseline");
      const tr = document.createElement("tr");
      if (isBase) {
        tr.className = "base";
      } else {
        rank += 1;
        if (rank === 1) { tr.className = "best"; bestName = name; }
      }
      const cell = (text, cls) => {
        const td = document.createElement("td");
        if (cls) td.className = cls;
        td.textContent = text;
        return td;
      };
      const label = name.startsWith("__baseline") ? "baseline" : name;
      tr.appendChild(cell(isBase ? "—" : String(rank)));
      tr.appendChild(cell(label, "name"));
      tr.appendChild(cell(dns && dns !== "none" ? dnsLabel(dns) : "—", "dns"));
      tr.appendChild(cell(`${score}/${max}`));
      tr.appendChild(cell(`${domOk || "-"}/${domTotal || "-"}`));
      tr.appendChild(cell(http, ""));
      for (const v of [tls12, tls13, quic]) tr.appendChild(cell(v, v === "n/a" ? "muted" : ""));
      tr.appendChild(cell(isBase || avg === "0" || !avg ? "-" : `${avg}`));
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

  const APP_ROW_H = 60;
  const APP_OVERSCAN = 6;
  const appsAll = [];
  let appsShown = [];
  const appsPicked = new Set();
  const appsPool = [];
  let appsRaf = 0;
  let appsRenderedFirst = -1;
  let appsRenderedLast = -1;

  const iconCache = new Map();
  let iconsOn = false;
  const iconQueue = [];
  const iconPending = new Set();
  let iconActive = 0;

  function debounce(fn, ms) {
    let t = null;
    return (...args) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...args), ms);
    };
  }

  async function loadAppCount() {
    const n = (S.saved.APP_LIST || "").split(",").filter(Boolean).length;
    setText("appsSelected", String(n));
  }

  async function loadApps() {
    const r = await ctl("apps-list");
    if (r.errno !== 0) {
      toast((r.stderr || r.stdout).trim() || "Could not read the app list", 6000);
      return false;
    }
    appsAll.length = 0;
    for (const line of r.stdout.split("\n")) {
      const parts = line.trim().split("|");
      if (parts.length < 2 || !parts[0]) continue;
      const [pkg, uid, label, type] = parts;
      appsAll.push({ pkg, uid, label: label || pkg, sys: type === "s", hay: `${label} ${pkg}`.toLowerCase() });
    }
    appsAll.sort((a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: "base" }));
    return true;
  }

  function initPicked() {
    appsPicked.clear();
    const byUid = new Map(appsAll.map((a) => [a.uid, a.pkg]));
    const known = new Set(appsAll.map((a) => a.pkg));
    for (const e of (S.saved.APP_LIST || "").split(",").filter(Boolean)) {
      if (known.has(e)) appsPicked.add(e);
      else if (byUid.has(e)) appsPicked.add(byUid.get(e));
      else appsPicked.add(e);
    }
  }

  function showAppsOverlay() {
    initPicked();
    $("appsOverlay").hidden = false;
    $("appsSearch").value = "";
    $("appsShowSystem").checked = false;
    $("appsIcons").checked = iconsOn;
    $("appsList").scrollTop = 0;
    appsApplyFilter();
  }

  async function appsOpen() {
    const had = appsAll.length > 0;
    if (had) showAppsOverlay();
    else { $("appsOverlay").hidden = false; setText("appsCount", "Loading…"); }
    const ok = await loadApps();
    if (!ok) { if (!had) $("appsOverlay").hidden = true; return; }
    if (!had) showAppsOverlay();
    else if (!$("appsOverlay").hidden) appsApplyFilter();
  }

  function appsApplyFilter() {
    const q = $("appsSearch").value.trim().toLowerCase();
    const showSystem = $("appsShowSystem").checked;
    appsShown = appsAll.filter((a) => (showSystem || !a.sys) && (!q || a.hay.includes(q)));
    $("appsSpacer").style.height = `${appsShown.length * APP_ROW_H}px`;
    appsRenderedFirst = -1;
    appsRenderedLast = -1;
    appsUpdateCounts();
    renderAppsWindow();
  }

  function appsUpdateCounts() {
    setText("appsCount", `${appsShown.length} shown`);
    setText("appsPicked", `${appsPicked.size} selected`);
  }

  function makeAppRow() {
    const row = document.createElement("label");
    row.className = "app-row";
    const cb = document.createElement("input");
    cb.type = "checkbox";
    const ic = document.createElement("span");
    ic.className = "app-ic-wrap";
    const img = document.createElement("img");
    img.className = "app-ic";
    img.alt = "";
    img.hidden = true;
    const ph = document.createElement("span");
    ph.className = "app-ic ph";
    const name = document.createElement("span");
    name.className = "app-name";
    const title = document.createElement("span");
    title.className = "app-title";
    const sub = document.createElement("span");
    sub.className = "app-uid";
    name.append(title, sub);
    ic.append(img, ph);
    row.append(cb, ic, name);
    row._cb = cb; row._img = img; row._ph = ph; row._title = title; row._sub = sub;
    return row;
  }

  function fillAppRow(row, a) {
    row._app = a;
    row.dataset.pkg = a.pkg;
    row._cb.checked = appsPicked.has(a.pkg);
    row._title.textContent = a.label;
    row._sub.textContent = `${a.pkg} · uid ${a.uid}`;
    const url = iconCache.get(a.pkg);
    if (url) {
      if (row._img.dataset.src !== url) { row._img.src = url; row._img.dataset.src = url; }
      row._img.hidden = false;
      row._ph.hidden = true;
    } else {
      row._img.hidden = true;
      row._ph.hidden = false;
      row._ph.textContent = (a.pkg.split(".").pop() || "?").charAt(0).toUpperCase();
    }
  }

  function renderAppsWindow() {
    const list = $("appsList");
    const n = appsShown.length;
    const first = Math.max(0, Math.floor(list.scrollTop / APP_ROW_H) - APP_OVERSCAN);
    const last = Math.min(n, Math.ceil((list.scrollTop + list.clientHeight) / APP_ROW_H) + APP_OVERSCAN);
    if (first === appsRenderedFirst && last === appsRenderedLast) return;
    appsRenderedFirst = first;
    appsRenderedLast = last;
    const win = $("appsWin");
    win.style.transform = `translateY(${first * APP_ROW_H}px)`;
    const count = Math.max(0, last - first);
    while (appsPool.length < count) {
      const row = makeAppRow();
      appsPool.push(row);
    }
    for (let i = 0; i < count; i++) fillAppRow(appsPool[i], appsShown[first + i]);
    win.replaceChildren(...appsPool.slice(0, count));
    queueVisibleIcons(first, last);
  }
  const ICON_BATCH = 8;
  let iconInFlight = false;

  function queueVisibleIcons(first, last) {
    iconQueue.length = 0;
    if (!iconsOn) return;
    for (let i = first; i < last; i++) {
      const a = appsShown[i];
      if (a && !iconCache.has(a.pkg) && !iconPending.has(a.pkg)) iconQueue.push(a.pkg);
    }
    pumpIcons();
  }

  function pumpIcons() {
    if (iconInFlight) return;
    const batch = [];
    while (batch.length < ICON_BATCH && iconQueue.length) {
      const pkg = iconQueue.shift();
      if (iconCache.has(pkg) || iconPending.has(pkg)) continue;
      batch.push(pkg);
      iconPending.add(pkg);
    }
    if (!batch.length) return;
    iconInFlight = true;
    ctl(`apps-icons ${batch.join(" ")}`).then((r) => {
      iconInFlight = false;
      const got = new Map();
      if (r.errno === 0) {
        for (const line of r.stdout.split("\n")) {
          const bar = line.indexOf("|");
          if (bar <= 0) continue;
          const pkg = line.slice(0, bar);
          const rest = line.slice(bar + 1);
          const sp = rest.indexOf(" ");
          if (sp > 0) got.set(pkg, `data:image/${rest.slice(0, sp)};base64,${rest.slice(sp + 1)}`);
        }
      }
      for (const pkg of batch) {
        iconPending.delete(pkg);
        if (r.errno === 0) iconCache.set(pkg, got.get(pkg) || "");
      }
      if (r.errno === 0) {
        for (const row of appsPool) {
          if (row.isConnected && row._app && batch.includes(row._app.pkg)) fillAppRow(row, row._app);
        }
      }
      pumpIcons();
    });
  }

  function appsSetPicked(pkg, on) {
    if (on) appsPicked.add(pkg); else appsPicked.delete(pkg);
  }

  async function appsSave() {
    const final = [...appsPicked].join(",");
    const r = await ctl(`set APP_LIST ${final}`);
    if (r.errno !== 0) {
      toast((r.stderr || r.stdout).trim(), 6000);
      return;
    }
    S.saved.APP_LIST = final;
    $("appsOverlay").hidden = true;
    await loadAppCount();
    toast(`${appsPicked.size} app(s) selected. Restart the firewall to apply.`, 4000);
  }

  async function checkLatest() {
    const btn = $("checkLatestBtn");
    btn.disabled = true;
    try {
      const r = await ctl("latest-versions");
      const v = parseKV(r.stdout);
      const box = $("latestBox");
      box.innerHTML = "";
      const rows = [
        ["nfqws1 (zapret 1)", v.installed_nfqws1, v.latest_nfqws1],
        ["nfqws2 (zapret 2)", v.installed_nfqws2, v.latest_nfqws2],
        ["Strategies (commit)", v.installed_strategies, v.latest_strategies],
      ];
      for (const [name, inst, latest] of rows) {
        const row = document.createElement("div");
        row.className = "res-row";
        const known = latest && latest !== "unknown";
        const upToDate = known && inst === latest;
        const left = document.createElement("span");
        left.textContent = name;
        const right = document.createElement("span");
        right.textContent = !known ? "latest unknown (offline?)"
          : upToDate ? `${latest} · up to date`
          : `${latest} · update (installed ${inst || "none"})`;
        row.append(left, right);
        box.appendChild(row);
      }
    } finally {
      btn.disabled = false;
    }
  }

  async function backupExport() {
    const r = await ctl("backup-export");
    toast(r.errno === 0 ? r.stdout.trim().split("\n")[0] : (r.stderr || r.stdout).trim(), 6000);
  }

  async function backupImport() {
    const p = $("backupPath").value.trim();
    if (!p) {
      toast("Type the full path of the backup file", 4000);
      return;
    }
    const r = await ctl(`backup-import ${quote(p)}`);
    toast(r.errno === 0 ? r.stdout.trim().split("\n")[0] : (r.stderr || r.stdout).trim(), 6000);
    if (r.errno === 0) await loadSettingsFromDevice();
  }

  async function startBlockcheck() {
    const n = $("bcCount").value;
    const dns = $("bcDns").value;
    const eng = $("bcEngine").value;
    S.bcStarting = true;
    S.bc = { ...(S.bc || {}), bc_state: "running", bc_message: "starting…" };
    renderBlockcheck();
    try {
      const r = await ctl(`blockcheck start ${n} ${dns} ${eng}`);
      if (r.errno !== 0) {
        toast((r.stdout || r.stderr).trim() || "Blockcheck could not start", 6000, "err");
        return;
      }
      toast("Blockcheck started. Engines stopped; traffic is interrupted while it runs.", 5000);
    } finally {
      S.bcStarting = false;
      await refresh();
    }
  }

  async function copyReport() {
    const r = await ctl("bc-report");
    const ok = await copyText(r.stdout || r.stderr || "");
    toast(ok ? "Blockcheck report copied" : "Copy failed", 4000);
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

  function modalOpen() {
    return !$("appsOverlay").hidden || !$("editor").hidden;
  }

  async function refresh() {
    if (S.inFlight || document.hidden) return;
    if (modalOpen()) { schedule(); return; }
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
      const bcState = parseKV(parts.bc).bc_state;
      if (!S.bcRows.length && bcState && bcState !== "idle") {
        const r = await ctl("bc-results");
        S.bcRows = (r.stdout || "").split("\n").map((l) => l.trim()).filter(Boolean);
      }
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
      renderSettingsMenu();
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
    loadAppCount();
    renderSettingsMenu();
    await Promise.all([
      loadInterfaces(values.IFACE || "any"),
      loadStrategies(values.ENGINE || "nfqws2", values.STRATEGY || ""),
      (values.ENGINE || "nfqws2") === "nfqws1" ? loadZapret1Options() : null,
      loadTargets(),
      fillAutoStrategies("WIFI_ENGINE", "WIFI_STRATEGY", values.WIFI_STRATEGY || ""),
      fillAutoStrategies("MOBILE_ENGINE", "MOBILE_STRATEGY", values.MOBILE_STRATEGY || ""),
    ]);
    syncEngineSections();
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
  $("netRestoreBtn").addEventListener("click", async () => {
    const r = await ctl("emergency");
    const ok = r.errno === 0;
    toast(ok ? "Network restored: module rules, DNS redirect and engines removed" : ((r.stderr || r.stdout).trim() || "Restore failed; see Logs"), 5000, ok ? "ok" : "err");
    await refresh();
  });
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
  $("bcEngine").addEventListener("change", renderBlockcheck);
  $("appsBtn").addEventListener("click", appsOpen);
  $("appsClose").addEventListener("click", () => { $("appsOverlay").hidden = true; });
  $("appsSearch").addEventListener("input", debounce(appsApplyFilter, 120));
  $("appsShowSystem").addEventListener("change", appsApplyFilter);
  $("appsIcons").addEventListener("change", (e) => {
    iconsOn = e.target.checked;
    try { localStorage.setItem("zapret.icons", iconsOn ? "1" : "0"); } catch (_) {}
    if (!iconsOn) iconQueue.length = 0;
    appsRenderedFirst = -1;
    renderAppsWindow();
  });
  try { iconsOn = localStorage.getItem("zapret.icons") === "1"; } catch (_) {}
  $("appsAll").addEventListener("click", () => {
    for (const a of appsShown) appsPicked.add(a.pkg);
    renderAppsWindow();
    appsUpdateCounts();
  });
  $("appsNone").addEventListener("click", () => {
    appsPicked.clear();
    renderAppsWindow();
    appsUpdateCounts();
  });
  $("appsList").addEventListener("change", (e) => {
    const row = e.target.closest && e.target.closest(".app-row");
    if (!row || !e.target.matches("input[type=checkbox]")) return;
    appsSetPicked(row.dataset.pkg, e.target.checked);
    appsUpdateCounts();
  });
  $("appsList").addEventListener("scroll", () => {
    if (appsRaf) return;
    appsRaf = requestAnimationFrame(() => { appsRaf = 0; renderAppsWindow(); });
  }, { passive: true });
  $("appsSave").addEventListener("click", appsSave);
  $("checkLatestBtn").addEventListener("click", checkLatest);
  $("backupExportBtn").addEventListener("click", backupExport);
  $("backupImportBtn").addEventListener("click", backupImport);
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
  $("bcReportBtn").addEventListener("click", copyReport);
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
    refresh();
    await loadSettingsFromDevice();
  })();
  const SETTINGS_TITLES = {
    engine: "Engine and preset",
    network: "Interception and bypass",
    auto: "Startup and auto profile",
    dns: "Encrypted DNS",
    tools: "Tools, backup and lists",
  };

  let settingsGroup = null;

  function showSettingsGroup(g) {
    settingsGroup = g;
    for (const c of document.querySelectorAll("#tab-settings [data-group]")) {
      c.classList.toggle("group-off", c.dataset.group !== g);
    }
    $("settingsMenu").hidden = !!g;
    $("settingsHead").hidden = !g;
    setText("settingsTitle", g ? SETTINGS_TITLES[g] : "");
    for (const p of document.querySelectorAll("#tab-settings > p.hint")) p.hidden = !!g;
    if (g === "dns") { S.dnsSynced = false; dnsRefresh(); }
    window.scrollTo(0, 0);
  }

  function renderSettingsMenu() {
    const v = S.saved || {};
    setText("miEngine", `${v.ENGINE || "-"} · ${v.STRATEGY || "-"}`);
    setText("miNetwork", `${v.IFACE || "any"} · apps ${v.APP_MODE || "disabled"}`);
    setText("miAuto", `auto profile ${v.AUTO_NETWORK === "1" ? "on" : "off"} · start ${v.AUTO_START === "1" ? "on" : "off"}`);
    setText("miTools", `logging ${v.LOG_ENABLED === "0" ? "off" : "on"}`);
  }

  let dnsProviderList = null;

  async function dnsLoadProviders() {
    if (dnsProviderList) return dnsProviderList;
    const r = await ctl("dns-providers");
    dnsProviderList = r.stdout.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
      const [id, label, doh, dot] = l.split("|");
      return { id, label, doh, dot: dot || "" };
    });
    return dnsProviderList;
  }

  function dnsGroup(title) {
    const sec = document.createElement("section");
    sec.className = "prov-group";
    const head = document.createElement("header");
    const name = document.createElement("b");
    name.textContent = title;
    const count = document.createElement("span");
    count.className = "prov-count";
    head.append(name, count);
    const list = document.createElement("div");
    list.className = "prov-list";
    sec.append(head, list);
    return { sec, list, count };
  }

  function dnsUpdateCounts() {
    for (const sec of $("dnsProviders").querySelectorAll(".prov-group")) {
      const boxes = sec.querySelectorAll("input");
      const on = sec.querySelectorAll("input:checked").length;
      sec.querySelector(".prov-count").textContent = `${on} of ${boxes.length} ticked`;
    }
  }

  async function dnsRenderProviders() {
    const box = $("dnsProviders");
    if (box.childElementCount || box.dataset.loading) return;
    box.dataset.loading = "1";
    const v4 = dnsGroup("IPv4");
    const v6 = dnsGroup("IPv6");
    for (const p of await dnsLoadProviders()) {
      const g = p.doh.includes("[") ? v6 : v4;
      const label = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = p.id;
      cb.dataset.block = /malware|ads/.test(p.label) && !/client subnet/.test(p.label) ? "1" : "0";
      const text = document.createElement("span");
      text.className = "prov";
      const name = document.createElement("b");
      name.textContent = p.label;
      const host = document.createElement("small");
      host.textContent = `${p.doh.replace(/^https:\/\//, "").replace(/:443/, "")}${p.dot ? "  · Private DNS ok" : ""}`;
      text.append(name, host);
      label.append(cb, text);
      g.list.appendChild(label);
    }
    for (const g of [v4, v6]) {
      if (!g.list.childElementCount) {
        const none = document.createElement("p");
        none.className = "hint";
        none.textContent = "No providers in this family.";
        g.list.appendChild(none);
      }
      g.sec.dataset.family = g.sec === v6.sec ? "6" : "4";
      box.appendChild(g.sec);
    }
    dnsUpdateCounts();
  }

  function dnsSetChecked(pred) {
    for (const c of $("dnsProviders").querySelectorAll("input")) c.checked = pred(c);
    dnsUpdateCounts();
  }

  function dnsSelected() {
    return Array.from($("dnsProviders").querySelectorAll("input:checked")).map((c) => c.value).join(" ");
  }

  async function dnsRefresh() {
    await dnsRenderProviders();
    const r = await ctl("dns-status");
    const v = parseKV(r.stdout);
    const running = v.doh_running === "1";
    const port = v.doh_port || "5354";
    const count = (v.doh_providers || "").split(/[ ,]+/).filter(Boolean).length;
    const btn = $("dohToggleBtn");
    if (running) {
      const queries = Number(v.doh_queries || 0).toLocaleString();
      const errs = Number(v.doh_errors || 0);
      setText("dohNow", `on · ${count} provider${count === 1 ? "" : "s"} · ${queries} DNS queries${errs ? ` · ${errs} recent errors` : ""}`);
      btn.textContent = "Stop DoH proxy";
      btn.classList.remove("primary");
    } else {
      setText("dohNow", v.doh_binary === "no" ? "dnscrypt-proxy not installed" : "off");
      btn.textContent = "Start DoH proxy";
      btn.classList.add("primary");
    }
    btn.disabled = v.doh_binary === "no" && !running;
    if (!S.dnsSynced) {
      S.dnsSynced = true;
      const saved = new Set((v.doh_providers || "").split(/[ ,]+/).filter(Boolean));
      for (const c of $("dnsProviders").querySelectorAll("input")) c.checked = saved.has(c.value);
    }
    const mode = v.private_dns_mode || "unset";
    const now = mode === "hostname" ? `on · ${v.private_dns_host || "?"}` : mode;
    setText("dnsNow", now);
    setText("miDns", running ? "DoH proxy on" : now === "off" ? "off" : now);
  }

  let dnsSaveTimer = null;
  function dnsSaveSelection() {
    clearTimeout(dnsSaveTimer);
    dnsSaveTimer = setTimeout(async () => {
      const ids = dnsSelected().split(" ").filter(Boolean).join(",");
      const r = await ctl(`set DOH_PROVIDERS ${ids}`);
      if (r.errno !== 0) { toast((r.stderr || r.stdout).trim(), 6000, "err"); return; }
      S.saved.DOH_PROVIDERS = ids;
      const running = $("dohNow").textContent.startsWith("on");
      toast(running ? "Providers saved. Restart the DoH proxy to apply them." : `${ids ? ids.split(",").length : 0} provider(s) saved`, 3000, "ok");
    }, 400);
  }

  async function dohToggle() {
    const btn = $("dohToggleBtn");
    btn.disabled = true;
    try {
      const running = $("dohNow").textContent.startsWith("on");
      let r;
      if (running) {
        r = await ctl("doh-stop");
      } else {
        const ids = dnsSelected().split(" ").filter(Boolean);
        if (!ids.length) { toast("Tick at least one provider", 4000); return; }
        const set = await ctl(`set DOH_PROVIDERS ${ids.join(",")}`);
        if (set.errno !== 0) { toast((set.stderr || set.stdout).trim(), 6000); return; }
        r = await ctl("doh-start");
      }
      const msg = (r.stdout || r.stderr).trim().split("\n").pop() || (r.errno === 0 ? "Done" : "Failed");
      toast(msg, r.errno === 0 ? 4000 : 7000);
      await dnsRefresh();
      refresh();
    } finally {
      btn.disabled = false;
    }
  }

  const mk = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  };

  const dnsShort = (label) => String(label || "").replace(/\s*\([^)]*\)\s*$/, "");

  const dnsHost = (doh) => String(doh || "")
    .replace(/^https:\/\//, "")
    .replace(/:443(?=\/|$)/, "")
    .replace(/\/dns-query.*$/, "");

  function dnsParseRows(out) {
    return out.split("\n").map((l) => l.trim()).filter(Boolean)
      .map((l) => l.split("|"))
      .filter((f) => f.length >= 5)
      .map(([id, avg, ok, total, status, pick]) => ({
        id, ms: Number(avg) || 0, ok: Number(ok) || 0, total: Number(total) || 0,
        status, pick: pick === "1",
      }));
  }

  async function dnsProviderMap() {
    return new Map((await dnsLoadProviders()).map((p) => [p.id, p]));
  }

  function dnsRenderRows(rows, providers) {
    const box = $("dnsResults");
    box.innerHTML = "";
    const answering = rows.filter((r) => r.status !== "failed");
    const fastest = answering.length ? Math.min(...answering.map((r) => r.ms)) : 0;
    let rank = 0;
    rows.forEach((r, i) => {
      const p = providers.get(r.id);
      const ok = r.status !== "failed";
      const li = mk("li", `sp-row${r.pick ? " pick" : ""}`);
      li.dataset.state = r.status;
      li.style.setProperty("--i", String(Math.min(i, 12)));
      if (ok) rank += 1;
      li.appendChild(mk("span", "sp-rank", ok ? String(rank) : "–"));
      const main = mk("div", "sp-main");
      main.append(mk("b", "", p ? dnsShort(p.label) : r.id), mk("small", "", p ? dnsHost(p.doh) : r.id));
      const tags = mk("div", "sp-tags");
      tags.appendChild(mk("span", "sp-avail", `${r.ok}/${r.total}`));
      if (r.pick) tags.appendChild(mk("em", "sp-pick", "best 3"));
      const meter = mk("div", "sp-meter");
      if (ok) {
        li.dataset.speed = r.ms < 120 ? "fast" : r.ms < 300 ? "mid" : "slow";
        const track = mk("div", "sp-track");
        const fill = mk("i");
        fill.style.width = `${Math.max(6, Math.round((fastest / Math.max(r.ms, 1)) * 100))}%`;
        track.appendChild(fill);
        meter.append(track, mk("span", "sp-ms", `${r.ms} ms`));
      } else {
        meter.appendChild(mk("span", "sp-ms sp-none", "no answer"));
      }
      li.append(main, tags, meter);
      box.appendChild(li);
    });
    return answering.length;
  }

  function dnsBusy(on, text) {
    $("dnsTestBtn").disabled = on;
    $("dnsBest3Btn").disabled = on;
    $("spProgress").hidden = !on;
    if (text) setText("spProgressText", text);
  }

  function dnsSetFoot(text) {
    setText("spFoot", text || "");
    $("spFoot").hidden = !text;
  }

  async function dnsRunJob(mode, ids) {
    const count = ids ? ids.split(",").length : (await dnsLoadProviders()).length;
    dnsBusy(true, mode === "best3"
      ? `Testing all providers, 3 queries each…`
      : `Testing ${count} provider${count === 1 ? "" : "s"}, 3 queries each…`);
    try {
      const start = await ctl(`dns-job start ${mode} ${ids}`);
      if (start.errno !== 0 || !/started/.test(start.stdout)) {
        toast((start.stderr || start.stdout).trim() || "The provider test could not start", 6000, "err");
        return null;
      }
      const deadline = Date.now() + 10 * 60 * 1000;
      while (Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 1000));
        const s = await ctl("dns-job status");
        const state = (s.stdout.match(/^job_state=(\S+)/m) || [])[1];
        if (state === "done") return dnsParseRows(s.stdout);
        if (state === "error") {
          const msg = s.stdout.split("\n").filter((l) => l && !l.includes("=") && !l.includes("|")).pop();
          toast(msg || "The provider test failed; see Logs", 6000, "err");
          return null;
        }
      }
      toast("The provider test is still running; open this tab again later", 6000, "warn");
      return null;
    } finally {
      dnsBusy(false);
    }
  }

  async function dnsTest() {
    const ids = dnsSelected().split(" ").filter(Boolean).join(",");
    if (!ids) {
      toast("Tick at least one provider", 4000);
      return;
    }
    const rows = await dnsRunJob("test", ids);
    if (!rows) return;
    const providers = await dnsProviderMap();
    const answering = dnsRenderRows(rows, providers);
    setText("spSummary", `${answering} of ${rows.length} answered`);
    const top = rows.find((x) => x.status !== "failed");
    dnsSetFoot(top ? `Lowest ping: ${dnsShort((providers.get(top.id) || { label: top.id }).label)}, ${top.ms} ms` : "");
  }

  async function dnsBest3() {
    const rows = await dnsRunJob("best3", "");
    if (!rows) return;
    const providers = await dnsProviderMap();
    const picked = rows.filter((x) => x.pick);
    if (!picked.length) {
      toast("No provider answered; DoH providers unchanged", 6000, "err");
      return;
    }
    const chosen = new Set(picked.map((x) => x.id));
    dnsSetChecked((c) => chosen.has(c.value));
    dnsRenderRows(rows, providers);
    const answering = rows.filter((x) => x.status !== "failed").length;
    setText("spSummary", `${answering} of ${rows.length} answered`);
    const names = picked.map((x) => dnsShort((providers.get(x.id) || { label: x.id }).label));
    dnsSetFoot(`Best 3 by ping, saved: ${names.join(", ")}`);
    toast(`Best 3 saved: ${names.join(", ")}`, 5000, "ok");
    await dnsRefresh();
  }

  async function dnsFastest() {
    const ids = dnsSelected();
    if (!ids) {
      toast("Tick at least one provider", 4000);
      return;
    }
    $("dnsFastestBtn").disabled = true;
    try {
      const r = await ctl(`dns-fastest ${quote(ids)}`);
      toast((r.stdout || r.stderr).trim().split("\n").pop() || "Done", 5000);
      await dnsRefresh();
    } finally {
      $("dnsFastestBtn").disabled = false;
    }
  }

  async function dnsOff() {
    const r = await ctl("dns-apply off");
    toast((r.stdout || r.stderr).trim().split("\n").pop() || "Done", 4000);
    await dnsRefresh();
  }

  for (const b of document.querySelectorAll("[data-open]")) {
    b.addEventListener("click", () => showSettingsGroup(b.dataset.open));
  }
  $("settingsBack").addEventListener("click", () => showSettingsGroup(null));
  $("dnsTestBtn").addEventListener("click", dnsTest);
  $("dnsBest3Btn").addEventListener("click", dnsBest3);
  $("dnsFastestBtn").addEventListener("click", dnsFastest);
  $("dnsOffBtn").addEventListener("click", dnsOff);
  $("dohToggleBtn").addEventListener("click", dohToggle);
  $("dnsProviders").addEventListener("change", () => { dnsUpdateCounts(); dnsSaveSelection(); });
  $("provBlock").addEventListener("click", () => dnsSetChecked((c) => c.dataset.block === "1"));
  $("provAll").addEventListener("click", () => dnsSetChecked(() => true));
  $("provNone").addEventListener("click", () => dnsSetChecked(() => false));
  $("updDnsBtn").addEventListener("click", () => startUpdate("dnscrypt", "dnscrypt-proxy"));
  showSettingsGroup(null);

})();
