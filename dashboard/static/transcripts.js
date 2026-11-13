"use strict";
// Transcripts: a recorded session line by line, with its clip, what the cloud models heard, and a box to put it right.
// Every line is on the page at once (the browser skips drawing the ones out of sight); a line only becomes a text box
// while you're on it. The strip down the side is the whole session: click or drag it to go anywhere.
App.register("transcripts", (() => {
  const COLOURS = ["#7cc4ff", "#f0b354", "#6fcf8e", "#e39cf5", "#ff9d8a", "#8fe0d6", "#d6d36a", "#a9b4ff", "#f58fb5"];
  const audio = new Audio();
  const drafts = new Map();       // clip -> text typed but not saved (a save that failed)
  let S = null;                   // the open session: {id, lines, corrections, reference}
  let view = [];                  // indexes into S.lines, in the order shown
  let rows = [];                  // the row elements, same order
  let colour = {}, root, list, strip, canvas, tip, ui = {}, frame = 0, dragging = false;

  const tidy = s => s.split(/\s+/).filter(Boolean).join(" ");
  const plain = s => s.toLowerCase().replace(/['’]/g, "").replace(/[^a-z0-9]+/g, " ").split(" ").filter(Boolean);
  const fit = t => { t.style.height = "auto"; t.style.height = t.scrollHeight + 2 + "px"; };
  const phone = () => matchMedia("(max-width: 700px)").matches;
  const lineOf = r => S.lines[r.dataset.i];
  const kept = r => { const l = lineOf(r), c = S.corrections[l.clip]; return c && c.status === "fixed" ? c.text : l.text; };
  const valueOf = r => { const t = r.querySelector("textarea"); return t ? t.value : drafts.get(lineOf(r).clip) ?? kept(r); };

  // ---- the tab ----
  function mount(viewEl, rest) {
    const opt = (v, t) => el("option", { value: v }, t);
    ui.session = el("select", { id: "txSession", title: "Voice session" });
    ui.speaker = el("select", { id: "txSpeaker", title: "Who spoke" });
    ui.show = el("select", { id: "txShow", title: "Which lines" }, opt("all", "All lines"), opt("likely", "Likely errors to review"),
      opt("flag", "All disagreements to review"), opt("todo", "Not reviewed"), opt("done", "Reviewed"), opt("fixed", "Fixed"));
    ui.forbot = el("input", { type: "checkbox" });
    ui.search = el("input", { type: "search", placeholder: "Find words" });
    ui.autoplay = el("input", { type: "checkbox" });
    ui.autocheck = el("input", { type: "checkbox" });
    ui.order = el("select", { title: "Order of the lines" }, opt("old", "Oldest first"), opt("new", "Newest first"));
    ui.speed = el("select", { title: "Playback speed" }, opt("1", "1×"), opt("1.25", "1.25×"), opt("1.5", "1.5×"), opt("0.75", "0.75×"));
    ui.count = el("span", { id: "txCount" });
    ui.bar = el("div");
    list = el("div", "tx-list");
    canvas = el("canvas");
    tip = el("div", "tip");
    strip = el("div", { class: "tx-timeline", title: "" }, canvas, tip);
    root = el("div", "tx",
      el("div", "tx-bar", ui.session, ui.speaker, ui.show,
        el("label", { title: "Lines that named the bot or that it acted on" }, ui.forbot, "To the bot"), ui.search,
        el("label", null, ui.autoplay, "Play on focus"),
        el("label", { title: "A line you stayed on long enough to hear and then left unchanged is marked as heard right" }, ui.autocheck, "Leaving marks right"),
        ui.order, ui.speed, ui.count),
      el("div", "tx-progress", ui.bar),
      el("div", "tx-body", list, strip),
      el("div", "tx-keys",
        el("span", null, el("kbd", null, "Enter"), " save and next (unchanged = heard right; so is leaving a line you've heard)"),
        el("span", null, el("kbd", null, "Tab"), " / ", el("kbd", null, "Shift"), "+", el("kbd", null, "Tab"), " move without marking"),
        el("span", null, el("kbd", null, "Ctrl"), "+", el("kbd", null, "Space"), " play again"),
        el("span", null, el("kbd", null, "Alt"), "+", el("kbd", null, "U"), " use the cloud's version"),
        el("span", null, el("kbd", null, "Alt"), "+", el("kbd", null, "J"), " junk"),
        el("span", null, el("kbd", null, "Esc"), " put the text back"),
        el("span", "lg", "Timeline:", el("i", { style: "background:#4a505c" }), "talk", el("i", { style: "background:var(--s1)" }), "to the bot",
          el("i", { style: "background:var(--flag)" }), "likely errors left", el("i", { style: "background:var(--ok)" }), "reviewed")));
    viewEl.append(root);

    ui.show.value = Prefs.get("show", "all");
    ui.forbot.checked = !!Prefs.get("forbot", false);
    ui.autoplay.checked = Prefs.get("autoplay", true) !== false;
    ui.autocheck.checked = Prefs.get("autocheck", true) !== false;
    ui.speed.value = Prefs.get("speed", "1");
    ui.order.value = Prefs.get("order", "old");
    ui.session.addEventListener("change", () => openSession(ui.session.value).catch(e => toast(e.message)));
    for (const k of ["speaker", "show", "forbot", "order"]) ui[k].addEventListener("change", () => { save_prefs(); draw(); });
    for (const k of ["autoplay", "autocheck", "speed"]) ui[k].addEventListener("change", save_prefs);
    let typing;
    ui.search.addEventListener("input", () => { clearTimeout(typing); typing = setTimeout(draw, 200); });

    list.addEventListener("focusin", focusIn);
    list.addEventListener("focusout", focusOut);
    list.addEventListener("input", e => { if (e.target.tagName === "TEXTAREA") { const r = e.target.closest(".row"); fit(e.target); r.classList.toggle("dirty", tidy(e.target.value) !== kept(r)); } });
    list.addEventListener("keydown", keys);
    list.addEventListener("click", clicks);
    // a tap on a button leaves the line being typed in focused, so the keyboard stays up and the edit isn't saved twice
    list.addEventListener("mousedown", e => { if (e.target.closest("button")) e.preventDefault(); });
    list.addEventListener("scroll", repaintSoon, { passive: true });
    addEventListener("scroll", repaintSoon, { passive: true });
    addEventListener("resize", repaintSoon);
    timelineEvents();
    return loadSessions(decodeURIComponent(rest || ""));
  }

  function unmount() {
    audio.pause();
    removeEventListener("scroll", repaintSoon);
    removeEventListener("resize", repaintSoon);
    S = null;
  }

  function reroute(rest) { const id = decodeURIComponent(rest || ""); if (id && (!S || id !== S.id)) openSession(id); }

  function save_prefs() {
    for (const k of ["show", "order", "speed"]) Prefs.set(k, ui[k].value);
    for (const k of ["forbot", "autoplay", "autocheck"]) Prefs.set(k, ui[k].checked);
  }

  // ---- sessions ----
  async function loadSessions(want) {
    list.replaceChildren(el("div", "tx-empty", "Loading…"));
    const all = await api("/api/sessions");
    ui.session.replaceChildren(...all.map(s => el("option", { value: s.id },
      (s.started ? fmt.when(s.started) : s.id) + " · " + (s.channel || s.guild) + " · " + s.reviewed + "/" + s.clips)));
    if (!all.length) { list.replaceChildren(el("div", "tx-empty", "No recorded sessions yet. They appear here once ?chat has been used.")); return; }
    ui.session.value = all.some(s => s.id === want) ? want : all[0].id;
    await openSession(ui.session.value);
  }

  async function openSession(id) {
    list.replaceChildren(el("div", "tx-empty", "Loading…"));
    S = await api("/api/session/" + encodeURIComponent(id));
    ui.session.value = id;
    history.replaceState(null, "", "#transcripts/" + encodeURIComponent(id));
    for (const l of S.lines) l.t = Date.parse(l.time);
    const people = new Map();
    for (const l of S.lines) if (!l.bot) people.set(l.user_id, l.name);
    colour = {};
    [...people.keys()].sort().forEach((u, i) => colour[u] = COLOURS[i % COLOURS.length]);
    ui.speaker.replaceChildren(el("option", { value: "" }, "Everyone"),
      ...[...people].sort((a, b) => a[1].localeCompare(b[1])).map(([u, n]) => el("option", { value: u }, n)));
    draw();
  }

  // ---- the list ----
  function draw() {
    if (!S) return;
    const who = ui.speaker.value, show = ui.show.value, forbot = ui.forbot.checked, words = tidy(ui.search.value).toLowerCase();
    const everything = !who && !words && show === "all" && !forbot;
    view = [];
    S.lines.forEach((l, i) => {
      if (l.bot) { if (everything) view.push(i); return; }
      const c = S.corrections[l.clip];
      if (who && l.user_id !== who) return;
      if (forbot && !(l.named || l.for_bot)) return;
      if (show === "likely" && (c || l.flag !== "likely")) return;
      if (show === "flag" && (c || !l.flag)) return;
      if (show === "todo" && c) return;
      if (show === "done" && !c) return;
      if (show === "fixed" && !(c && c.status === "fixed")) return;
      if (words && !(l.text + " " + (c && c.text || "") + " " + (l.opinions || []).map(o => o.text).join(" ")).toLowerCase().includes(words)) return;
      view.push(i);
    });
    if (ui.order.value === "new") view.reverse();
    rows = view.map(row);
    list.replaceChildren(...rows);
    if (!rows.length) list.append(el("div", "tx-empty", "Nothing matches."));
    counts();
    list.scrollTop = 0;
    if (phone() && scrollY > root.offsetTop) scrollTo(0, root.offsetTop);
    repaintSoon();
  }

  function row(i) {
    const l = S.lines[i], r = el("div", "row");
    r.dataset.i = i;
    r.append(el("span", "time", l.time.slice(11, 19)));
    if (l.bot) {
      r.classList.add("bot");
      r.append(el("span", "who", "Gunnabot"), el("div", "said", l.text), el("span"), el("span"));
      return r;
    }
    const who = el("span", { class: "who", title: l.name, style: "color:" + colour[l.user_id] }, l.name);
    const said = el("div", "said", el("div", { class: "text", tabindex: "0" }), el("div", "was"));
    if (l.flag) {
      // what the cloud models heard, with the words the bot's model didn't have picked out
      r.classList.add(l.flag);
      const had = new Set(plain(l.text));
      const shown = l.flag === "likely" ? [{ label: "2 models agree", text: l.opinions[0].text }]
        : l.opinions.map(o => ({ label: o.model.split("/").pop().split("-")[0], text: o.text }));
      for (const o of shown) {
        const text = el("span");
        for (const w of o.text.split(/\s+/).filter(Boolean)) {
          const bare = plain(w)[0];
          text.append(bare && !had.has(bare) ? el("mark", null, w) : w, " ");
        }
        if (!o.text) text.append("(heard nothing)");
        said.append(el("div", "ref", el("b", { title: l.opinions.map(x => x.model).join(", ") }, o.label), text,
          o.text ? el("button", { type: "button", class: "use", tabindex: "-1", "data-text": o.text, title: "Put this in the box" }, "Use") : null));
      }
    }
    const tags = el("span", "tags", el("span", null, (l.audio_ms / 1000).toFixed(1) + "s"));
    if (l.for_bot) { const a = l.outcome + (l.args ? ": " + l.args : ""); tags.append(el("span", { class: "tag cmd", title: a }, a)); }
    else if (l.named) tags.append(el("span", "tag", "named"));
    const acts = el("span", "acts", [["play", "▶", "Play"], ["save", "✓", "Save and go to the next line"], ["junkb", "Junk", "Not worth learning from"], ["undo", "Undo", "Take the review back"]]
      .map(([cls, text, title]) => el("button", { type: "button", class: cls, title, tabindex: "-1" }, text)));
    r.append(who, said, tags, acts);
    paint(r);
    return r;
  }

  // shows a row as its review stands
  function paint(r) {
    const l = lineOf(r), c = S.corrections[l.clip], was = r.querySelector(".was"), text = r.querySelector(".text");
    r.classList.remove("fixed", "ok", "junk");
    if (c) r.classList.add(c.status);
    r.classList.toggle("dirty", drafts.has(l.clip));
    if (text) text.textContent = drafts.get(l.clip) ?? kept(r);
    was.replaceChildren();
    if (c && c.status === "fixed") was.append("heard: ", el("s", null, l.text));
  }

  function counts() {
    let total = 0, done = 0, fixed = 0, likely = 0, unsure = 0;
    for (const l of S.lines) if (!l.bot) {
      total++;
      const c = S.corrections[l.clip];
      if (c) { done++; if (c.status === "fixed") fixed++; } else if (l.flag === "likely") likely++; else if (l.flag) unsure++;
    }
    const shown = view.filter(i => !S.lines[i].bot).length;
    ui.count.textContent = `${shown} shown · ${done} of ${total} reviewed · ${fixed} fixed`
      + (S.reference.length ? ` · ${likely} likely errors and ${unsure} unsure left` : " · no second opinion yet");
    ui.count.title = S.reference.length ? "Second opinions from " + S.reference.join(" and ") : "Run reference.py on this session to flag likely errors";
    ui.bar.style.width = (total ? 100 * done / total : 0) + "%";
  }

  // ---- editing: a line becomes a text box while you're on it ----
  function edit(r) {
    const old = r.querySelector(".text");
    if (!old) return r.querySelector("textarea");
    const t = el("textarea", { rows: "1", spellcheck: "true", enterkeyhint: "next" });
    t.value = drafts.get(lineOf(r).clip) ?? kept(r);
    old.replaceWith(t);
    fit(t);
    t.focus();
    t.setSelectionRange(t.value.length, t.value.length);
    return t;
  }

  function unedit(r) {
    const t = r.querySelector("textarea");
    if (!t) return;
    const l = lineOf(r);
    if (tidy(t.value) !== kept(r) && tidy(t.value)) drafts.set(l.clip, t.value);
    t.replaceWith(el("div", { class: "text", tabindex: "0" }));
    paint(r);
  }

  function focusIn(e) {
    const r = e.target.closest(".row");
    if (!r) return;
    if (e.target.classList.contains("text")) { edit(r); return; }
    if (e.target.tagName !== "TEXTAREA") return;
    for (const c of list.querySelectorAll(".current")) c.classList.remove("current");
    r.classList.add("current");
    r.dataset.since = Date.now();
    delete r.dataset.undone;
    if (ui.autoplay.checked) play(r);
    repaintSoon();
  }

  // long enough on a line to have heard it: as long as the clip, but no less than 0.8 s and no more than 3 s
  const heardIt = r => Date.now() - +r.dataset.since >= Math.min(Math.max(lineOf(r).audio_ms, 800), 3000);

  function focusOut(e) {
    if (e.target.tagName !== "TEXTAREA") return;
    const r = e.target.closest(".row"), t = e.target, text = t.value;
    // back to plain text once focus has really gone elsewhere (not just to another window)
    setTimeout(() => { if (r.isConnected && document.hasFocus() && document.activeElement !== t) unedit(r); }, 0);
    if (!r.isConnected || r.dataset.saving) return;
    // an edit left behind is kept, not lost
    if (tidy(text) !== kept(r)) return save(r, "fixed", text);
    // and a line heard and left as it was is taken as right, unless it's been reviewed, just undone, or is empty
    const l = lineOf(r);
    if (!ui.autocheck.checked || S.corrections[l.clip] || r.dataset.undone || !tidy(text) || !heardIt(r)) return;
    setTimeout(() => { if (document.hasFocus() && S && !S.corrections[l.clip]) save(r, "ok", text); }, 0);
  }

  async function save(r, status, text = valueOf(r)) {
    const l = lineOf(r);
    text = tidy(text);
    if (status === "fixed" && !text) status = "junk";
    try {
      const got = await api("/api/session/" + encodeURIComponent(S.id), { clip: l.clip, status, text });
      if (got.correction) S.corrections[l.clip] = got.correction; else delete S.corrections[l.clip];
      drafts.delete(l.clip);
      paint(r);
      counts();
      repaintSoon();
    } catch (e) {
      if (status !== null) drafts.set(l.clip, text);
      r.classList.add("dirty");
      toast("Not saved: " + e.message);
    }
  }

  async function mark(r, status) {
    r.dataset.saving = 1;
    const going = save(r, status, valueOf(r));
    next(r);
    await going;
    delete r.dataset.saving;
  }

  function useRef(r, text) {
    const l = lineOf(r);
    text = text || ((l.opinions || []).find(o => o.text) || {}).text;
    if (!text) return;
    const t = edit(r) || r.querySelector("textarea");
    t.value = text;
    fit(t);
    r.classList.toggle("dirty", tidy(t.value) !== kept(r));
    t.focus();
  }

  function next(r, back) {
    let n = r;
    do n = back ? n.previousElementSibling : n.nextElementSibling;
    while (n && !n.querySelector(".text, textarea"));
    if (!n) return;
    n.scrollIntoView({ block: "center", behavior: "smooth" });
    (n.querySelector(".text") || n.querySelector("textarea")).focus({ preventScroll: true });
  }

  function keys(e) {
    if (e.target.tagName !== "TEXTAREA") return;
    const r = e.target.closest(".row");
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); mark(r, "fixed"); }
    else if (e.altKey && e.key.toLowerCase() === "j") { e.preventDefault(); mark(r, "junk"); }
    else if (e.altKey && e.key.toLowerCase() === "u") { e.preventDefault(); useRef(r); }
    else if (e.ctrlKey && e.code === "Space") { e.preventDefault(); play(r); }
    else if (e.key === "Escape") { e.preventDefault(); e.target.value = kept(r); drafts.delete(lineOf(r).clip); r.classList.remove("dirty"); fit(e.target); }
    else if (e.altKey && e.key === "ArrowDown") { e.preventDefault(); next(r); }
    else if (e.altKey && e.key === "ArrowUp") { e.preventDefault(); next(r, true); }
  }

  function clicks(e) {
    const r = e.target.closest(".row");
    if (!r || r.classList.contains("bot")) return;
    const b = e.target.closest("button");
    if (!b) return;
    if (b.classList.contains("play")) { play(r); }
    else if (b.classList.contains("use")) useRef(r, b.dataset.text);
    else if (b.classList.contains("save")) mark(r, "fixed");
    else if (b.classList.contains("junkb")) save(r, "junk");
    else if (b.classList.contains("undo")) { r.dataset.undone = 1; drafts.delete(lineOf(r).clip); save(r, null); }
  }

  function play(r) {
    const l = lineOf(r);
    audio.src = "/clip/" + encodeURIComponent(S.id) + "/" + encodeURIComponent(l.clip.slice(6));
    audio.playbackRate = +ui.speed.value;
    audio.play().catch(() => { });
  }

  // ---- the timeline ----
  function repaintSoon() { if (!frame) frame = requestAnimationFrame(() => { frame = 0; paintTimeline(); }); }

  function span() {
    const ts = S.lines.map(l => l.t).filter(Number.isFinite);
    return [Math.min(...ts), Math.max(...ts) + 1000];
  }

  // where on the strip a time is, and back; newest first puts the end of the session at the top
  function ypos(t, t0, t1, h) { const f = (t - t0) / (t1 - t0); return (ui.order.value === "new" ? 1 - f : f) * h; }
  function tpos(y, t0, t1, h) { let f = Math.max(0, Math.min(1, y / h)); if (ui.order.value === "new") f = 1 - f; return t0 + f * (t1 - t0); }

  // the rows on screen: [first, last] indexes into rows
  function onScreen() {
    if (!rows.length) return null;
    const top = phone() ? 48 : list.getBoundingClientRect().top, bottom = phone() ? innerHeight : list.getBoundingClientRect().bottom;
    const first = search(r => r.getBoundingClientRect().bottom > top);
    const last = search(r => r.getBoundingClientRect().top >= bottom) - 1;
    return [Math.min(first, rows.length - 1), Math.max(first, Math.min(last, rows.length - 1))];
  }

  // the first row for which test is true, given it's false then true down the list
  function search(test) {
    let lo = 0, hi = rows.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (test(rows[mid])) hi = mid; else lo = mid + 1; }
    return lo;
  }

  function paintTimeline() {
    if (!S || !canvas.isConnected) return;
    const dpr = devicePixelRatio || 1, w = strip.clientWidth, h = strip.clientHeight;
    if (!w || !h) return;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const g = canvas.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const [t0, t1] = span();
    const narrow = w < 50;
    const cols = narrow ? { talk: [0, 12], bot: [13, 3], flag: [17, 6], done: [24, 5] } : { talk: [36, 24], bot: [63, 5], flag: [71, 8], done: [82, 6] };
    const bins = Math.max(1, Math.floor(h / 2)), bh = h / bins;
    const talk = new Array(bins).fill(0), bot = new Array(bins).fill(0), flag = new Array(bins).fill(0), done = new Array(bins).fill(0);
    const bin = t => Math.max(0, Math.min(bins - 1, Math.floor(ypos(t, t0, t1, h) / bh)));
    for (const l of S.lines) if (!l.bot && Number.isFinite(l.t)) talk[bin(l.t)]++;
    for (const i of view) {
      const l = S.lines[i];
      if (l.bot || !Number.isFinite(l.t)) continue;
      const b = bin(l.t), c = S.corrections[l.clip];
      if (l.for_bot || l.named) bot[b]++;
      if (c) done[b]++; else if (l.flag === "likely") flag[b]++;
    }
    const most = Math.max(1, ...talk);
    // hour and half-hour marks
    if (!narrow) {
      g.font = "10px system-ui, sans-serif";
      g.fillStyle = "#8b93a1"; g.strokeStyle = "#2a2e37";
      const step = (t1 - t0) > 4 * 3600_000 ? 3600_000 : 1800_000;
      for (let t = Math.ceil(t0 / step) * step; t < t1; t += step) {
        const y = ypos(t, t0, t1, h);
        g.beginPath(); g.moveTo(32, y + .5); g.lineTo(w, y + .5); g.stroke();
        if (y > 8 && y < h - 4) g.fillText(fmt.time(t), 2, y + 3.5);
      }
    }
    for (let b = 0; b < bins; b++) {
      const y = b * bh;
      if (talk[b]) { g.fillStyle = "#4a505c"; g.fillRect(cols.talk[0], y, Math.max(1, cols.talk[1] * talk[b] / most), Math.max(1, bh - .5)); }
      if (bot[b]) { g.fillStyle = "#3987e5"; g.fillRect(cols.bot[0], y, cols.bot[1], Math.max(1.5, bh)); }
      if (flag[b]) { g.fillStyle = "#f58fb5"; g.fillRect(cols.flag[0], y, cols.flag[1], Math.max(1.5, bh)); }
      if (done[b]) { g.fillStyle = "#6fcf8e"; g.fillRect(cols.done[0], y, cols.done[1], Math.max(1.5, bh)); }
    }
    // the part of the session on screen
    const seen = onScreen();
    if (seen) {
      let a = ypos(S.lines[view[seen[0]]].t, t0, t1, h), z = ypos(S.lines[view[seen[1]]].t, t0, t1, h);
      if (a > z) [a, z] = [z, a];
      z = Math.max(z, a + 4);
      g.fillStyle = "rgba(124,196,255,.13)"; g.fillRect(0, a, w, z - a);
      g.strokeStyle = "#7cc4ff"; g.lineWidth = 1.5; g.strokeRect(.75, a + .75, w - 1.5, z - a - 1.5);
    }
    // the line you're on
    const cur = list.querySelector(".row.current");
    if (cur) { const y = ypos(lineOf(cur).t, t0, t1, h); g.fillStyle = "#e6e8ec"; g.fillRect(0, y - 1, w, 2); }
    strip.bins = { talk, bot, flag, done, bh, t0, t1, h };
  }

  function timelineEvents() {
    const at = e => { const r = strip.getBoundingClientRect(); return e.clientY - r.top; };
    strip.addEventListener("pointerdown", e => { if (!S) return; dragging = true; strip.setPointerCapture(e.pointerId); jump(at(e), false); });
    strip.addEventListener("pointermove", e => {
      if (!S) return;
      if (dragging) jump(at(e), false);
      hover(at(e));
    });
    const stop = e => { if (dragging) { dragging = false; jump(at(e), true); } };
    strip.addEventListener("pointerup", stop);
    strip.addEventListener("pointercancel", () => dragging = false);
    strip.addEventListener("pointerleave", () => { tip.style.display = "none"; });
  }

  // goes to the row nearest a point on the strip
  function jump(y, done) {
    if (!rows.length) return;
    const [t0, t1] = span(), t = tpos(y, t0, t1, strip.clientHeight);
    const newest = ui.order.value === "new";
    let k = search(r => newest ? lineOf(r).t <= t : lineOf(r).t >= t);
    k = Math.min(k, rows.length - 1);
    const r = rows[k];
    if (phone()) scrollTo(0, scrollY + r.getBoundingClientRect().top - 48 - innerHeight * 0.3);
    else list.scrollTop += r.getBoundingClientRect().top - list.getBoundingClientRect().top - list.clientHeight * 0.3;
    if (done) { r.classList.remove("flash"); void r.offsetWidth; r.classList.add("flash"); }
    repaintSoon();
  }

  function hover(y) {
    const d = strip.bins;
    if (!d || phone()) return;
    const b = Math.max(0, Math.min(d.talk.length - 1, Math.floor(y / d.bh)));
    // a few bins either side, so the numbers mean something
    let talk = 0, bot = 0, flag = 0, done = 0;
    for (let i = Math.max(0, b - 3); i <= Math.min(d.talk.length - 1, b + 3); i++) { talk += d.talk[i]; bot += d.bot[i]; flag += d.flag[i]; done += d.done[i]; }
    const t = tpos(y, d.t0, d.t1, d.h);
    tip.replaceChildren(el("div", "t", fmt.time(t)), el("div", null, el("b", null, talk), " lines"),
      bot ? el("div", null, el("b", null, bot), " to the bot") : null, flag ? el("div", null, el("b", null, flag), " likely errors left") : null,
      done ? el("div", null, el("b", null, done), " reviewed") : null);
    tip.style.display = "block";
    tip.style.top = Math.max(0, Math.min(d.h - tip.offsetHeight, y - tip.offsetHeight / 2)) + "px";
  }

  return { mount, unmount, reroute };
})());
