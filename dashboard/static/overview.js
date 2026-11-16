"use strict";
// Overview: is everything up, what's happening now, how the last session went, and what went wrong lately.
App.register("overview", (() => {
  // everything every 5 s (CPU and memory every minute on the longer ranges); a section is only redrawn when what it shows has changed
  let page, shown, res, resAt, replies, hours = Prefs.get("overviewHours", 1);

  async function mount(view) {
    page = el("div", "page");
    view.append(page);
    page.append(loading());
    shown = {}; res = undefined; resAt = 0; replies = undefined;
    await refresh();
    App.every(5000, refresh);
  }

  function range(v) {
    hours = v; Prefs.set("overviewHours", v);
    res = undefined; resAt = 0; replies = undefined;
    refresh().catch(e => toast(e.message));
  }

  async function refresh() {
    const asked = hours, due = Date.now() - resAt > (hours <= 1 ? 4000 : 60000);
    const [o, r, rep] = await Promise.all([api("/api/overview"), due ? api("/api/resources?hours=" + hours).catch(() => null) : undefined,
      Replies.load(hours)]);
    App.paintStatus(o);
    if (asked !== hours) return;
    replies = rep;
    if (due) { resAt = Date.now(); if (r || res === undefined) res = r; }
    if (!Object.keys(shown).length) page.replaceChildren();
    const minute = Math.floor(Date.now() / 60000); // so "5 min ago" moves on
    const { now, ...rest } = o;
    put("status", [rest.containers, rest.deployed, rest.voice_server, rest.activity, rest.config_pending, minute], () => statusCards(o));
    put("now", [rest.history, rest.review, minute], () => nowCards(o));
    put("replies", [hours, replies, minute], () => Replies.render(replies, hours, seg(Replies.RANGES, hours, range)));
    put("resources", [hours, res], () => resources(res));
    put("warnings", rest.warnings, () => warnings(o.warnings));
  }

  function put(name, key, build) {
    const k = JSON.stringify(key);
    if (shown[name] && shown[name].k === k) return;
    const node = build();
    if (shown[name]) shown[name].node.replaceWith(node); else page.append(node);
    shown[name] = { k, node };
  }

  function statusCards(o) {
    const bot = o.containers.musicbot || {}, coder = o.containers["musicbot-coder"] || {}, vs = o.voice_server || {}, a = o.activity || {};
    const d = o.deployed;
    const state = (cls, text) => big(el("span", "status " + cls, text));
    return section("Status", stats([
      stat("Bot", bot.running ? state("good", "Running") : state("critical", bot.status === "missing" ? "Missing" : "Stopped"),
        bot.started ? "Up " + fmt.ago(bot.started).replace(" ago", "") + ", since " + fmt.when(bot.started) : "",
        d ? el("span", null, "Built from ", el("a", { href: "https://github.com/regan-young/MusicBot/commit/" + d.sha, target: "_blank", rel: "noopener", title: d.subject }, d.sha), " · " + d.subject) : null,
        o.config_pending ? el("span", null, el("span", "status warning", "Config changed"), " ", el("a", { href: "#config" }, "restart to apply")) : null),
      stat("Voice", a.live ? state("good", "Someone's talking") : state("idle", "Quiet"),
        a.last_heard ? "Last heard " + fmt.ago(a.last_heard) + " · " + a.heard_10m + " lines in 10 min" : "Nobody heard in the last 10 minutes",
        a.recording ? "Recording this session" : null),
      stat("Speech server", !vs.configured ? state("idle", "Off") : vs.up ? state("good", "Up") : state("critical", "Not answering"),
        vs.configured ? (vs.host || "") + (vs.up ? " · answered in " + vs.ms + " ms" : " · " + (vs.error || "")) : "voiceapi isn't set",
        vs.models ? vs.models.map(m => m.split("/").pop()).join(", ") : null),
      stat("Coder sidecar", !coder.running ? state("critical", "Stopped")
          : coder.health === "healthy" ? state("good", "Healthy") : state("warning", coder.health || "Running"),
        coder.started ? "Up since " + fmt.when(coder.started) : "",
        "Runs Claude Code when someone asks the bot to change itself"),
    ]));
  }

  function nowCards(o) {
    const h = o.history || {}, s = h.last_session, p = h.playing, r = o.review || {};
    const cells = [];
    cells.push(p ? stat("Playing now",
      big(el("span", "song", p.uri ? el("a", { href: p.uri, target: "_blank", rel: "noopener", title: p.title }, p.title) : p.title)),
      (p.requester ? "Asked for by " + p.requester + " · " : "") + "started " + fmt.ago(p.started))
      : stat("Playing now", big(el("span", "status idle", "Nothing")),
        h.week_plays != null ? h.week_plays + " songs in the last 7 days, for " + h.week_requesters + " people" : ""));
    if (s) cells.push(stat("Last voice session", fmt.span(s.end - s.start),
      fmt.when(s.start) + " · " + s.people + " people",
      fmt.int(s.lines) + " lines heard, " + s.for_bot + " to the bot, " + s.plays + " songs played",
      el("a", { href: "#metrics" }, "Response times and more →")));
    if (r.clips) cells.push(stat("Transcript review", big(fmt.int(r.reviewed), el("small", "u", " of " + fmt.int(r.clips))),
      el("span", "meter", el("i", { style: "width:" + Math.min(100, 100 * r.reviewed / r.clips).toFixed(1) + "%" })),
      fmt.pct(100 * r.reviewed / r.clips) + " of recorded lines checked",
      el("a", { href: "#transcripts" }, "Carry on reviewing →")));
    return section("Now", stats(cells));
  }

  function resources(res) {
    const box = section("CPU and memory, last " + Replies.label(hours), { class: "resources" });
    if (!res || (!Object.keys(res.cpu || {}).length && !Object.keys(res.memory || {}).length)) {
      box.append(el("p", "muted", "Prometheus didn't answer" + (res && res.error ? " (" + res.error + ")" : "") + "."));
      return box;
    }
    const names = [["musicbot", "Bot", "var(--s1)"], ["musicbot-coder", "Coder sidecar", "var(--s2)"]];
    const x = hours <= 1 ? t => new Date(t).toLocaleTimeString("en-NZ", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }) : fmt.time;
    const series = key => names.filter(([n]) => res[key][n]).map(([n, label, color]) => ({ name: label, color, points: res[key][n] }));
    box.append(el("div", "grid wide",
      card("CPU, in cores", Charts.line({ series: series("cpu"), yFormat: v => v < 0.1 ? v.toFixed(3) : v.toFixed(2), xFormat: x, height: 170 })),
      card("Memory", Charts.line({ series: series("memory"), yFormat: fmt.bytes, xFormat: x, height: 170 }))));
    return box;
  }

  function warnings(w) {
    const box = section("Warnings and errors, last 24 hours");
    if (!w || w.count == null) { box.append(el("p", "muted", "The logs couldn't be read.")); return box; }
    if (!w.count) { box.append(el("p", null, el("span", "status good", "None"))); return box; }
    box.append(el("p", null, el("span", "status warning", w.count >= 200 ? "200 or more" : String(w.count)), " ",
      el("a", { href: "#logs/warn" }, "See them in Logs")));
    box.append(el("div", "card logs", w.recent.map(e => el("div", "log " + e.level,
      el("span", "ts", fmt.stamp(e.ts)), el("span", "lv " + e.level, e.level), el("span", "lg", e.logger), el("span", "m", e.msg)))));
    return box;
  }

  return { mount };
})());
