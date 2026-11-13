"use strict";
// Overview: is everything up, what's happening now, how the last session went, and what went wrong lately.
App.register("overview", (() => {
  let timer, page;

  async function mount(view) {
    page = el("div", "page");
    view.append(page);
    page.append(el("p", "muted", "Loading…"));
    await refresh();
    timer = setInterval(refresh, 15000);
  }

  function unmount() { clearInterval(timer); }

  async function refresh() {
    const [o, res] = await Promise.all([api("/api/overview"), api("/api/resources?hours=24").catch(() => null)]);
    App.last = o;
    const keep = page.querySelector(".resources");
    page.replaceChildren(statusCards(o), nowCards(o));
    page.append(keep && !res ? keep : resources(res));
    page.append(warnings(o.warnings));
  }

  function statusCards(o) {
    const bot = o.containers.musicbot || {}, coder = o.containers["musicbot-coder"] || {}, vs = o.voice_server || {}, a = o.activity || {};
    const d = o.deployed;
    return el("section", "block", el("h2", null, "Status"), el("div", "grid",
      card("Bot",
        el("div", "big", bot.running ? el("span", "status good", "Running") : el("span", "status critical", bot.status === "missing" ? "Missing" : "Stopped")),
        el("div", "sub", bot.started ? "Up since " + fmt.when(bot.started) + ", " + fmt.ago(bot.started) : ""),
        d ? el("div", "sub", "Built from ", el("a", { href: "https://github.com/regan-young/MusicBot/commit/" + d.sha, target: "_blank", rel: "noopener" }, d.sha), " · " + d.subject) : null,
        o.config_pending ? el("div", "sub", el("span", "status warning", "Config changed"), " ", el("a", { href: "#config" }, "restart to apply")) : null),
      card("Voice",
        el("div", "big", a.live ? el("span", "status good", "Someone's talking") : el("span", "status idle", "Quiet")),
        el("div", "sub", a.last_heard ? "Last heard " + fmt.ago(a.last_heard) + " · " + a.heard_10m + " lines in 10 min" : "Nobody heard in the last 10 minutes"),
        a.recording ? el("div", "sub", "Recording this session") : null),
      card("Speech server",
        el("div", "big", !vs.configured ? el("span", "status idle", "Off") : vs.up ? el("span", "status good", "Up") : el("span", "status critical", "Not answering")),
        el("div", "sub", vs.configured ? (vs.host || "") + (vs.up ? " · answered in " + vs.ms + " ms" : " · " + (vs.error || "")) : "voiceapi isn't set"),
        vs.models ? el("div", "sub", vs.models.map(m => m.split("/").pop()).join(", ")) : null),
      card("Coder sidecar",
        el("div", "big", !coder.running ? el("span", "status critical", "Stopped")
          : coder.health === "healthy" ? el("span", "status good", "Healthy") : el("span", "status warning", coder.health || "Running")),
        el("div", "sub", coder.started ? "Up since " + fmt.when(coder.started) : ""),
        el("div", "sub", "Runs Claude Code when someone asks the bot to change itself"))));
  }

  function nowCards(o) {
    const h = o.history || {}, s = h.last_session, p = h.playing, r = o.review || {};
    const cards = [];
    cards.push(p ? card("Playing now",
      el("div", { style: "font-weight:600;font-size:17px" }, p.uri ? el("a", { href: p.uri, target: "_blank", rel: "noopener" }, p.title) : p.title),
      el("div", "sub", (p.requester ? "Asked for by " + p.requester + " · " : "") + "started " + fmt.ago(p.started)))
      : card("Playing now", el("div", "big", el("span", "status idle", "Nothing")), el("div", "sub", h.week_plays != null ? h.week_plays + " songs in the last 7 days, for " + h.week_requesters + " people" : "")));
    if (s) cards.push(card("Last voice session",
      el("div", "big", fmt.span(s.end - s.start)),
      el("div", "sub", fmt.when(s.start) + " · " + s.people + " people"),
      el("div", "sub", fmt.int(s.lines) + " lines heard, " + s.for_bot + " to the bot, " + s.plays + " songs played"),
      el("div", "sub", el("a", { href: "#metrics" }, "Response times and more"))));
    if (r.clips) cards.push(card("Transcript review",
      el("div", "big", fmt.int(r.reviewed), el("small", null, " of " + fmt.int(r.clips))),
      el("div", "sub", fmt.pct(100 * r.reviewed / r.clips) + " of recorded lines checked"),
      el("div", "sub", el("a", { href: "#transcripts" }, "Carry on reviewing"))));
    return el("section", "block", el("h2", null, "Now"), el("div", "grid", cards));
  }

  function resources(res) {
    const box = el("section", "block resources", el("h2", null, "CPU and memory, last 24 hours"));
    if (!res || (!Object.keys(res.cpu || {}).length && !Object.keys(res.memory || {}).length)) {
      box.append(el("p", "muted", "Prometheus didn't answer" + (res && res.error ? " (" + res.error + ")" : "") + "."));
      return box;
    }
    const names = [["musicbot", "Bot", "var(--s1)"], ["musicbot-coder", "Coder sidecar", "var(--s2)"]];
    const series = key => names.filter(([n]) => res[key][n]).map(([n, label, color]) => ({ name: label, color, points: res[key][n] }));
    box.append(el("div", "grid wide",
      card("CPU, in cores", Charts.line({ series: series("cpu"), yFormat: v => v < 0.1 ? v.toFixed(3) : v.toFixed(2), height: 170 })),
      card("Memory", Charts.line({ series: series("memory"), yFormat: fmt.bytes, height: 170 }))));
    return box;
  }

  function warnings(w) {
    const box = el("section", "block", el("h2", null, "Warnings and errors, last 24 hours"));
    if (!w || w.count == null) { box.append(el("p", "muted", "The logs couldn't be read.")); return box; }
    if (!w.count) { box.append(el("p", null, el("span", "status good", "None"))); return box; }
    box.append(el("p", null, el("span", "status warning", w.count >= 200 ? "200 or more" : String(w.count)), " ",
      el("a", { href: "#logs/warn" }, "See them in Logs")));
    box.append(el("div", "card logs", w.recent.map(e => el("div", "log " + e.level,
      el("span", "ts", fmt.stamp(e.ts)), el("span", "lv " + e.level, e.level), el("span", "lg", e.logger), el("span", "m", e.msg)))));
    return box;
  }

  return { mount, unmount };
})());
