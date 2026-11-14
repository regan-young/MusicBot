"use strict";
// The shell: helpers every tab uses, and the tabs themselves, one per #hash.
const $ = id => document.getElementById(id);

const App = {
  tabs: {},
  current: null,
  timers: [],

  register(name, tab) { this.tabs[name] = tab; },

  start() {
    addEventListener("hashchange", () => this.route());
    this.route();
    this.status();
    // the top bar keeps up by itself; on Overview the tab paints it from its own answer
    setInterval(() => { if (!document.hidden && this.current?.name !== "overview") this.status(); }, 5000);
    // back on the page after a while: catch up now rather than at the next tick
    addEventListener("visibilitychange", () => {
      if (document.hidden) return;
      if (this.current?.name !== "overview") this.status();
      for (const t of this.timers) t.run();
    });
  },

  // runs fn every ms while this tab is showing and the page is in view; one run at a time, stopped when the tab changes
  every(ms, fn) {
    let busy = false;
    const run = async () => {
      if (busy || document.hidden) return;
      busy = true;
      try { await fn(); } catch { /* the next tick tries again */ } finally { busy = false; }
    };
    this.timers.push({ id: setInterval(run, ms), run });
  },

  // #name or #name/rest; the rest goes to the tab (the transcripts tab keeps its session there)
  route() {
    const [name, ...rest] = location.hash.slice(1).split("/");
    const tab = this.tabs[name] ? name : "overview";
    if (this.current && this.current.name === tab && this.current.tab.reroute) return this.current.tab.reroute(rest.join("/"));
    for (const t of this.timers) clearInterval(t.id);
    this.timers = [];
    if (this.current && this.current.tab.unmount) this.current.tab.unmount();
    for (const a of document.querySelectorAll("nav a")) a.classList.toggle("on", a.dataset.tab === tab);
    const view = $("view");
    view.replaceChildren();
    view.scrollTop = 0;
    scrollTo(0, 0);
    this.current = { name: tab, tab: this.tabs[tab] };
    document.title = "Gunnabot · " + tab[0].toUpperCase() + tab.slice(1);
    Promise.resolve(this.tabs[tab].mount(view, rest.join("/"))).catch(e => view.append(el("p", "page empty", "Could not load: " + e.message)));
  },

  // the dot in the top bar: is the bot up, and is anyone talking to it
  async status() {
    try { this.paintStatus(await api("/api/overview")); } catch { /* the tab shows its own error */ }
  },

  paintStatus(o) {
    App.last = o;
    const bot = o.containers.musicbot || {};
    const s = $("topstatus");
    s.replaceChildren();
    if (!bot.running) s.append(el("span", "status critical", "Bot down"));
    else if (o.activity.live) s.append(el("span", "status good", el("span", "label", "In voice now")));
    else s.append(el("span", "status idle", el("span", "label", "Bot up, quiet")));
    if (o.config_pending) s.append(el("a", { href: "#config", title: "Saved config changes wait for a restart" }, "· restart pending"));
  },
};

// el("div", "class", child...) or el("a", {href: ...}, child...); strings become text, never HTML
function el(tag, attrs, ...children) {
  const e = document.createElement(tag);
  if (typeof attrs === "string") e.className = attrs;
  else if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k === "style") e.style.cssText = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v === true) e.setAttribute(k, "");
    else if (v !== false && v != null) e.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null && c !== false) e.append(c instanceof Node ? c : String(c));
  return e;
}

async function api(path, body) {
  const r = await fetch(path, body !== undefined ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {});
  const j = await r.json().catch(() => ({}));
  if (!r.ok) { const e = new Error(j.error || r.statusText); e.status = r.status; e.body = j; throw e; }
  return j;
}

function toast(msg, good) {
  const t = $("toast");
  t.textContent = msg;
  t.className = good ? "good" : "";
  t.style.display = "block";
  clearTimeout(toast.t);
  toast.t = setTimeout(() => t.style.display = "none", good ? 3000 : 6000);
}

// settings kept in the browser; private windows may refuse, which only means they aren't kept
const Prefs = {
  all: (() => { try { return JSON.parse(localStorage.getItem("gunnabot") || localStorage.getItem("transcripts") || "{}"); } catch { return {}; } })(),
  get(k, d) { return k in this.all ? this.all[k] : d; },
  set(k, v) { this.all[k] = v; try { localStorage.setItem("gunnabot", JSON.stringify(this.all)); } catch { } },
};

const fmt = {
  ms: v => v == null ? "–" : v < 1000 ? Math.round(v) + " ms" : (v / 1000).toFixed(v < 10000 ? 1 : 0) + " s",
  int: v => v == null ? "–" : Math.round(v).toLocaleString("en-NZ"),
  bytes: v => v == null ? "–" : !v ? "0" : v < 1048576 ? (v / 1024).toFixed(0) + " KB" : v < 1073741824 ? (v / 1048576).toFixed(0) + " MB" : (v / 1073741824).toFixed(2) + " GB",
  pct: v => v == null ? "–" : (v < 10 ? v.toFixed(1) : Math.round(v)) + "%",
  time: t => new Date(t).toLocaleTimeString("en-NZ", { hour: "2-digit", minute: "2-digit", hour12: false }),
  stamp: t => new Date(t).toLocaleString("en-NZ", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }),
  day: t => new Date(t).toLocaleDateString("en-NZ", { weekday: "short", day: "numeric", month: "short" }),
  when: t => new Date(t).toLocaleString("en-NZ", { weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }),
  span: ms => { const m = Math.round(ms / 60000); return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0") + " min"; },
  ago: t => {
    const s = (Date.now() - t) / 1000;
    if (s < 60) return "just now";
    if (s < 3600) return Math.round(s / 60) + " min ago";
    if (s < 86400) return Math.round(s / 3600) + " h ago";
    const d = Math.round(s / 86400);
    return d === 1 ? "a day ago" : d + " days ago";
  },
};

// a small table: cols = [[heading, value(row), "n" for numbers]]
function table(rows, cols) {
  const t = el("table", "data");
  t.append(el("thead", null, el("tr", null, cols.map(([h, , k]) => el("th", k || null, h)))));
  t.append(el("tbody", null, rows.map(r => el("tr", null, cols.map(([, f, k]) => {
    const v = f(r);
    return el("td", k || null, v instanceof Node ? v : String(v ?? "–"));
  })))));
  return el("div", "scrollx", t);
}

function card(title, ...body) { return el("div", "card", el("h3", null, title), ...body); }

// a set of buttons where one is on: seg(options [[value, label]], current, onchange)
function seg(options, value, change) {
  const box = el("div", "seg");
  for (const [v, label] of options) {
    const b = el("button", { type: "button", class: v === value ? "on" : "" }, label);
    b.addEventListener("click", () => { for (const x of box.children) x.classList.remove("on"); b.classList.add("on"); change(v); });
    box.append(b);
  }
  return box;
}
