"use strict";
// Logs: the bot's (or the coder sidecar's) log, live or searched back through Loki. Keys and tokens arrive masked.
App.register("logs", (() => {
  const RANGES = [["live", "Live"], ["1", "1 h"], ["6", "6 h"], ["24", "24 h"], ["168", "7 days"], ["720", "30 days"]];
  const MAX = 3000;
  let page, list, info, timer, entries = [], newest = 0, busy = false;
  const st = { container: "musicbot", levels: ["INFO", "WARN", "ERROR"], q: "", range: "live" };

  function mount(view, rest) {
    if (rest === "warn") { st.levels = ["WARN", "ERROR"]; st.range = "24"; }
    page = el("div", "page");
    list = el("div", "logs");
    info = el("span", "muted");
    const search = el("input", { type: "search", placeholder: "Find text", value: st.q });
    let typing;
    search.addEventListener("input", () => { clearTimeout(typing); typing = setTimeout(() => { st.q = search.value.trim(); reload(); }, 300); });
    const source = el("select", null, el("option", { value: "musicbot" }, "Bot"), el("option", { value: "musicbot-coder" }, "Coder sidecar"));
    source.value = st.container;
    source.addEventListener("change", () => { st.container = source.value; reload(); });
    const levels = ["INFO", "WARN", "ERROR"].map(l => {
      const box = el("input", { type: "checkbox", checked: st.levels.includes(l) });
      box.addEventListener("change", () => { st.levels = [...page.querySelectorAll(".lvl input")].filter(b => b.checked).map(b => b.dataset.l); reload(); });
      box.dataset.l = l;
      return el("label", "lvl", box, l === "INFO" ? "Info" : l === "WARN" ? "Warnings" : "Errors");
    });
    page.append(el("div", "filters", source, seg(RANGES, st.range, v => { st.range = v; reload(); }), ...levels, search, info), list);
    view.append(page);
    list.addEventListener("click", e => { if (e.target.classList.contains("more")) older(); });
    reload();
  }

  function unmount() { clearInterval(timer); }

  function query(extra) {
    const p = new URLSearchParams({ container: st.container, q: st.q, limit: 500, ...extra });
    // all levels ticked means no filter, so lines without a level (stack traces) show too
    if (st.levels.length < 3) p.set("levels", st.levels.join(","));
    return "/api/logs?" + p;
  }

  async function reload() {
    clearInterval(timer);
    entries = []; newest = 0;
    list.replaceChildren(el("p", "muted", "Loading…"));
    const end = Date.now();
    const start = st.range === "live" ? end - 3600_000 : end - +st.range * 3600_000;
    try {
      const got = await api(query({ start, end }));
      entries = got.entries;
      newest = entries.length ? entries[0].ts : end - 5000;
      draw(got.source);
    } catch (e) { list.replaceChildren(el("p", "muted", "Couldn't read the logs: " + e.message)); return; }
    if (st.range === "live") timer = setInterval(poll, 2000);
  }

  async function poll() {
    if (busy || document.hidden) return;
    busy = true;
    try {
      const got = await api(query({ start: newest + 1, end: Date.now() }));
      if (got.entries.length) {
        newest = got.entries[0].ts;
        entries = got.entries.concat(entries).slice(0, MAX);
        const stick = (list.getBoundingClientRect().top >= 0);
        list.prepend(...got.entries.map(line));
        while (list.children.length > MAX + 1) list.lastElementChild.previousElementSibling.remove();
        if (stick) $("view").scrollTop = 0;
        status(got.source);
      }
    } catch { } finally { busy = false; }
  }

  async function older() {
    if (!entries.length) return;
    const last = entries[entries.length - 1].ts;
    const got = await api(query({ start: last - 30 * 86400_000, end: last - 1 }));
    entries = entries.concat(got.entries);
    list.querySelector(".more")?.remove();
    list.append(...got.entries.map(line));
    if (got.entries.length >= 500) list.append(moreButton());
  }

  function moreButton() { return el("p", null, el("button", { type: "button", class: "more" }, "Older lines")); }

  function draw(source) {
    list.replaceChildren(...entries.map(line));
    if (!entries.length) list.append(el("p", "empty", "No lines match."));
    else if (entries.length >= 500) list.append(moreButton());
    status(source);
  }

  function status(source) {
    info.replaceChildren(st.range === "live" ? el("span", "live-dot") : "", " " + fmt.int(entries.length) + " lines" +
      (source === "docker" ? " · Loki didn't answer, so only since the last restart" : ""));
  }

  function line(e) {
    const m = el("span", "m");
    if (st.q) {
      const lower = e.msg.toLowerCase(), q = st.q.toLowerCase();
      let at = 0, i;
      while ((i = lower.indexOf(q, at)) >= 0) { m.append(e.msg.slice(at, i), el("mark", null, e.msg.slice(i, i + q.length))); at = i + q.length; }
      m.append(e.msg.slice(at));
    } else m.textContent = e.msg;
    return el("div", "log " + e.level, el("span", "ts", fmt.stamp(e.ts)), el("span", "lv " + e.level, e.level || ""),
      el("span", { class: "lg", title: e.thread ? e.logger + " · " + e.thread : e.logger }, e.logger), m);
  }

  return { mount, unmount };
})());
