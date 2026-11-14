"use strict";
// Metrics: how quickly the bot answers and where the time goes, what it heard, what got played, and how well it hears each person.
App.register("metrics", (() => {
  let page, last, days = Prefs.get("metricsDays", 30);

  async function mount(view) {
    page = el("div", "page");
    view.append(page);
    await load();
    App.every(30000, () => load(true));
  }

  // quiet: a refresh in the background, which redraws only if the numbers have changed
  async function load(quiet) {
    if (!quiet) { last = ""; page.replaceChildren(filters(), el("p", "muted", "Loading…")); }
    const asked = days, m = await api("/api/metrics?days=" + days);
    const k = JSON.stringify(m);
    if (asked !== days || (quiet && k === last)) return;
    last = k;
    page.replaceChildren(filters(), ...render(m));
  }

  function filters() {
    return el("div", "filters", el("span", "muted", "Range"),
      seg([[7, "7 days"], [30, "30 days"], [90, "90 days"], [0, "All"]], days, v => { days = v; Prefs.set("metricsDays", v); load(); }));
  }

  function render(m) {
    const ss = m.sessions, st = m.stages || {};
    if (!ss.length && !m.plays_by_day.length) return [el("p", "empty", "Nothing recorded in this range.")];
    const out = [];
    const plays = m.plays_by_day.reduce((a, d) => a + d[1], 0);
    out.push(el("div", "grid",
      card("Typical response", el("div", "big", fmt.ms(st.response_p50)), el("div", "sub", "From when someone stops talking to the bot's first word, over " + fmt.int(st.replies) + " replies")),
      card("Slow responses", el("div", "big", fmt.ms(st.response_p90)), el("div", "sub", "One reply in ten takes this long or longer")),
      card("Voice sessions", el("div", "big", fmt.int(ss.length)), el("div", "sub", fmt.int(ss.reduce((a, s) => a + s.lines, 0)) + " lines heard, " + fmt.int(ss.reduce((a, s) => a + s.for_bot, 0)) + " to the bot")),
      card("Songs played", el("div", "big", fmt.int(plays)), el("div", "sub", "On " + m.plays_by_day.length + (m.plays_by_day.length === 1 ? " day" : " days")))));

    // where a reply's time goes
    const parts = [
      { name: "Waiting for them to finish", value: st.end_of_turn, color: "var(--s1)" },
      { name: "Speech recognition", value: st.stt, color: "var(--s2)" },
      { name: "Router", value: st.router, color: "var(--s3)" },
      { name: "Chat model", value: st.llm, color: "var(--s4)" },
      { name: "First sentence of speech", value: st.tts, color: "var(--s5)" },
    ];
    out.push(el("section", "block", el("h2", null, "Where a reply's time goes"),
      el("div", "card", Charts.stack({ parts, format: fmt.ms }),
        el("p", "note", "Medians of each step for lines said to the bot. They add up to roughly the typical response; the rest is queueing and playback starting."))));

    if (ss.filter(s => s.p50 != null).length > 1) {
      const series = [
        { name: "Typical (median)", color: "var(--s1)", points: ss.filter(s => s.p50 != null).map(s => [s.start, s.p50]) },
        { name: "Slow (1 in 10)", color: "var(--s2)", points: ss.filter(s => s.p90 != null).map(s => [s.start, s.p90]) },
      ];
      out.push(el("section", "block", el("h2", null, "Response time by session"),
        el("div", "card", Charts.line({ series, yFormat: fmt.ms, xFormat: fmt.day, yMax: undefined }))));
    }

    out.push(el("section", "block", el("h2", null, "Sessions"), el("div", "card", table(ss.slice().reverse(), [
      ["Started", s => fmt.when(s.start)], ["Length", s => fmt.span(s.end - s.start), "n"], ["People", s => s.people, "n"],
      ["Lines", s => fmt.int(s.lines), "n"], ["To the bot", s => s.for_bot, "n"], ["Commands", s => s.commands, "n"], ["Songs", s => s.plays, "n"],
      ["Replies", s => s.replies, "n"], ["Typical", s => fmt.ms(s.p50), "n"], ["Slow", s => fmt.ms(s.p90), "n"],
      ["Speech rec.", s => fmt.ms(s.stt), "n"], ["Router", s => fmt.ms(s.router), "n"],
    ]), ss.some(s => s.timings === false) ? el("p", "note", "Loki didn't answer, so reply times are missing.") : null)));

    // music
    const music = el("section", "block", el("h2", null, "Music"));
    if (m.plays_by_day.length) music.append(el("div", { class: "card", style: "margin-bottom:14px" }, el("h3", null, "Songs played per day"),
      Charts.bars({ data: m.plays_by_day.map(([d, n, radio]) => ({ label: d.slice(5).replace("-", "/"), value: n, tip: fmt.day(d + "T12:00"),
        extra: radio ? [{ value: String(radio), label: "radio" }] : [] })), name: "songs", yFormat: v => String(Math.round(v)) })));
    music.append(el("div", "grid wide",
      card("Most played", table(m.top_songs, [
        ["Song", r => el("span", { title: r[0] }, r[4] ? el("a", { href: r[4], target: "_blank", rel: "noopener" }, r[0]) : r[0]), "title"],
        ["Plays", r => r[1], "n"], ["Cut short", r => r[2] || "", "n"], ["Last", r => fmt.ago(r[3]), "n"]])),
      card("Who asks for music", table(m.requesters, [["Person", r => r[0]], ["Songs", r => r[1], "n"], ["Minutes played", r => fmt.int(r[2]), "n"]]))));
    out.push(music);

    // speech recognition per person
    out.push(el("section", "block", el("h2", null, "Speech recognition by person"), el("div", "card", table(m.speech, [
      ["Person", p => p.name], ["Lines", p => fmt.int(p.lines), "n"], ["Minutes", p => p.minutes, "n"], ["Heard nothing", p => p.heard_nothing, "n"],
      ["Avg time", p => fmt.ms(p.stt_ms), "n"], ["Reviewed", p => p.reviewed, "n"],
      ["Lines wrong", p => p.reviewed ? fmt.pct(100 * p.wrong / p.reviewed) : "–", "n"], ["Word error rate", p => fmt.pct(p.wer), "n"],
    ]), el("p", "note", "Lines wrong and word error rate come from the lines checked in Transcripts. Review so far has gone mostly to lines flagged as likely errors, so they read high until more ordinary lines are checked."))));
    return out;
  }

  return { mount };
})());
