"use strict";
// Reply times, one point per thing said to the bot, for a short recent range: shared by Overview and Metrics.
// The steps keep the colours they have in Metrics' "Where a reply's time goes".
const Replies = (() => {
  const RANGES = [[0.25, "15 min"], [1, "1 h"], [6, "6 h"], [24, "24 h"]];
  const STEPS = [
    ["turn", "Waiting for them to finish", "var(--s1)"],
    ["stt", "Speech recognition", "var(--s2)"],
    ["router", "Router", "var(--s3)"],
    ["llm_first", "Chat model, first sentence", "var(--s4)"],
    ["tts", "Speech, first sentence", "var(--s5)"],
  ];

  const GAP = 10 * 60000; // replies further apart than this are separate conversations, not one line
  const label = h => h < 1 ? Math.round(h * 60) + " min" : h < 24 ? h + (h === 1 ? " hour" : " hours") : h / 24 + (h === 24 ? " day" : " days");
  const pct = (v, p) => { if (!v.length) return null; const s = v.slice().sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.round(p / 100 * (s.length - 1)))]; };
  const clock = t => new Date(t).toLocaleTimeString("en-NZ", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

  function load(hours) {
    return api("/api/replies?hours=" + hours).catch(e => ({ replies: [], error: e.message }));
  }

  function render(r, hours) {
    const box = el("section", "block", el("h2", null, "Reply times, last " + label(hours)));
    if (!r) { box.append(el("p", "muted", "Loading…")); return box; }
    if (r.error) { box.append(el("p", "muted", "Loki didn't answer (" + r.error + "), so reply times can't be shown.")); return box; }
    if (!r.replies.length) { box.append(el("p", "empty", "Nobody has said anything to her in this time.")); return box; }
    const answered = r.replies.filter(x => x.response != null);
    const times = answered.map(x => x.response), last = answered[answered.length - 1];
    const quiet = r.replies.length - answered.length;
    box.append(el("div", "grid",
      card("Last reply", el("div", "big", last ? fmt.ms(last.response) : "–"),
        el("div", "sub", last ? last.who + " · " + fmt.ago(last.at) : "No spoken replies in this time")),
      card("Typical", el("div", "big", fmt.ms(pct(times, 50))), el("div", "sub", "From when someone stops talking to her first word")),
      card("Slow", el("div", "big", fmt.ms(pct(times, 90))), el("div", "sub", "One reply in ten takes this long or longer")),
      card("Said to her", el("div", "big", fmt.int(r.replies.length)),
        el("div", "sub", fmt.int(answered.length) + " answered out loud" + (quiet ? ", " + quiet + " done without speaking" : "")))));
    const x = hours <= 1 ? clock : fmt.time;
    const steps = STEPS.map(([k, name, color]) => ({ name, color,
      points: r.replies.map(p => [p.at, k === "llm_first" ? p.llm_first ?? p.llm : p[k]]).filter(p => p[1] != null) }));
    box.append(el("div", "grid wide",
      card("Whole response, per reply", Charts.line({ series: [{ name: "Response", color: "var(--accent)", points: answered.map(p => [p.at, p.response]) }],
        yFormat: fmt.ms, xFormat: x, height: 190, gap: GAP })),
      card("Each step, per reply", Charts.area({ series: steps, yFormat: fmt.ms, xFormat: x, height: 190, gap: GAP, totalLabel: "All steps" }))));
    const recent = r.replies.slice(-15).reverse();
    box.append(el("div", { class: "card", style: "margin-top:14px" }, el("h3", null, "Latest"), table(recent, [
      ["Time", p => clock(p.at)], ["Who", p => p.who],
      ["Said", p => el("span", { title: p.said }, p.said), "title"], ["Did", p => p.action],
      ["Waiting", p => fmt.ms(p.turn), "n"], ["Speech rec.", p => fmt.ms(p.stt), "n"], ["Router", p => fmt.ms(p.router), "n"],
      ["Chat model", p => fmt.ms(p.llm_first ?? p.llm), "n"], ["Speech", p => fmt.ms(p.tts), "n"],
      ["Response", p => p.response == null ? "–" : el("b", null, fmt.ms(p.response)), "n"],
    ]), el("p", "note", "Chat model is the time to its first sentence, which is when speaking can start. "
      + "Response runs from when they stopped talking to her first word, so it covers every step plus any wait in between.")));
    return box;
  }

  return { RANGES, label, load, render };
})();
