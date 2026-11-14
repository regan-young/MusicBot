"use strict";
// Plain SVG charts: thin marks, recessive grid, one y axis, a legend when there is more than one series,
// and a hover readout on every chart. Colours come from the validated dark categorical steps in app.css.
const Charts = (() => {
  const NS = "http://www.w3.org/2000/svg";
  const svg = (tag, attrs = {}) => { const e = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); return e; };
  const M = { l: 52, r: 14, t: 10, b: 24 };

  function niceTicks(max, count = 4) {
    if (!(max > 0)) return [0, 1];
    const raw = max / count, mag = 10 ** Math.floor(Math.log10(raw));
    const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= raw);
    const out = [];
    for (let v = 0; v <= max + step * 0.001; v += step) out.push(v);
    if (out[out.length - 1] < max) out.push(out[out.length - 1] + step);
    return out;
  }

  // redraws on resize, so the chart is always crisp at its real width
  function responsive(box, draw) {
    let w = 0;
    const ro = new ResizeObserver(() => { if (box.clientWidth && box.clientWidth !== w) { w = box.clientWidth; draw(w); } });
    ro.observe(box);
    return box;
  }

  function tipAt(tip, box, x, y, rows, title) {
    tip.replaceChildren();
    if (title) tip.append(el("div", "t", title));
    for (const r of rows) {
      const k = el("div", "k");
      if (r.color) { const i = el("i"); i.style.background = r.color; k.append(i); }
      k.append(el("b", null, r.value), el("span", "muted", r.label));
      tip.append(k);
    }
    tip.style.display = "block";
    const bw = box.clientWidth, tw = tip.offsetWidth;
    tip.style.left = Math.max(0, Math.min(bw - tw, x + 14 > bw - tw ? x - tw - 14 : x + 14)) + "px";
    tip.style.top = Math.max(0, y - tip.offsetHeight - 8) + "px";
  }

  function legend(series, kind) {
    if (series.length < 2) return null;
    return el("div", "legend", series.map(s => { const i = el("i", kind === "box" ? "box" : ""); i.style.background = s.color; return el("span", null, i, s.name); }));
  }

  // series: [{name, color, points: [[x, y]]}]; x is a time in ms. gap: points further apart than this aren't joined
  function line(opts) {
    const { series, height = 190, yFormat = String, xFormat = fmt.time, yMax, gap = Infinity } = opts;
    const box = el("div", "chart"), tip = el("div", "tip");
    const wrap = el("div", null, legend(series), box);
    box.append(tip);
    const xs = [...new Set(series.flatMap(s => s.points.map(p => p[0])))].sort((a, b) => a - b);
    if (!xs.length) { box.append(el("div", "empty", "No data in this range.")); return wrap; }
    responsive(box, w => {
      box.querySelector("svg")?.remove();
      const s = svg("svg", { viewBox: `0 0 ${w} ${height}`, height });
      const x0 = xs[0], x1 = xs[xs.length - 1] === x0 ? x0 + 1 : xs[xs.length - 1];
      const ticks = niceTicks(yMax ?? Math.max(...series.flatMap(s => s.points.map(p => p[1])), 0));
      const top = ticks[ticks.length - 1] || 1;
      const X = v => M.l + (xs.length === 1 ? (w - M.l - M.r) / 2 : (v - x0) / (x1 - x0) * (w - M.l - M.r));
      const Y = v => M.t + (1 - v / top) * (height - M.t - M.b);
      for (const t of ticks) {
        s.append(svg("line", { class: "grid-line", x1: M.l, x2: w - M.r, y1: Y(t), y2: Y(t) }));
        const label = svg("text", { class: "axis-text", x: M.l - 8, y: Y(t) + 4, "text-anchor": "end" }); label.textContent = yFormat(t); s.append(label);
      }
      const n = Math.max(2, Math.min(6, Math.floor((w - M.l - M.r) / 110)));
      for (let i = 0; i < n && xs.length > 1; i++) {
        const v = x0 + (x1 - x0) * i / (n - 1);
        const t = svg("text", { class: "axis-text", x: X(v), y: height - 6, "text-anchor": i === 0 ? "start" : i === n - 1 ? "end" : "middle" });
        t.textContent = xFormat(v); s.append(t);
      }
      if (xs.length === 1) { const t = svg("text", { class: "axis-text", x: X(x0), y: height - 6, "text-anchor": "middle" }); t.textContent = xFormat(x0); s.append(t); }
      for (const se of series) {
        const pts = se.points.filter(p => p[1] != null);
        if (pts.length > 1) s.append(svg("path", { d: pts.map((p, i) => (i && p[0] - pts[i - 1][0] <= gap ? "L" : "M") + X(p[0]).toFixed(1) + "," + Y(p[1]).toFixed(1)).join(""),
                                                   fill: "none", stroke: se.color, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }));
        // dots when there are few points, and always for a point standing alone between gaps
        for (const [i, p] of pts.entries()) if (pts.length <= 40 || ((!i || p[0] - pts[i - 1][0] > gap) && (i === pts.length - 1 || pts[i + 1][0] - p[0] > gap))) s.append(svg("circle", { cx: X(p[0]), cy: Y(p[1]), r: 4, fill: se.color, stroke: "var(--panel)", "stroke-width": 2 }));
      }
      const hair = svg("line", { class: "hair", y1: M.t, y2: height - M.b, visibility: "hidden" });
      const dots = series.map(se => { const c = svg("circle", { r: 4.5, fill: se.color, stroke: "var(--panel)", "stroke-width": 2, visibility: "hidden" }); s.append(c); return c; });
      s.append(hair);
      const hit = svg("rect", { x: M.l, y: 0, width: w - M.l - M.r, height, fill: "transparent" });
      s.append(hit);
      hit.addEventListener("pointermove", e => {
        const r = s.getBoundingClientRect(), px = (e.clientX - r.left) * (w / r.width);
        let best = xs[0];
        for (const v of xs) if (Math.abs(X(v) - px) < Math.abs(X(best) - px)) best = v;
        hair.setAttribute("x1", X(best)); hair.setAttribute("x2", X(best)); hair.setAttribute("visibility", "visible");
        const rows = [];
        series.forEach((se, i) => {
          const p = se.points.find(p => p[0] === best);
          if (p && p[1] != null) { dots[i].setAttribute("cx", X(best)); dots[i].setAttribute("cy", Y(p[1])); dots[i].setAttribute("visibility", "visible"); rows.push({ color: se.color, value: yFormat(p[1]), label: se.name }); }
          else dots[i].setAttribute("visibility", "hidden");
        });
        tipAt(tip, box, X(best) * r.width / w, Math.min(...rows.map(() => e.clientY - r.top)), rows, xFormat(best));
      });
      hit.addEventListener("pointerleave", () => { hair.setAttribute("visibility", "hidden"); dots.forEach(d => d.setAttribute("visibility", "hidden")); tip.style.display = "none"; });
      box.prepend(s);
    });
    return wrap;
  }

  // stacked filled areas: series [{name, color, points: [[x, y]]}], first at the bottom; a missing value counts as 0.
  // gap: points further apart than this aren't joined, and a point on its own is drawn as a thin bar
  function area(opts) {
    const { series, height = 190, yFormat = String, xFormat = fmt.time, gap = Infinity, totalLabel = "Total" } = opts;
    const box = el("div", "chart"), tip = el("div", "tip");
    const wrap = el("div", null, legend(series, "box"), box);
    box.append(tip);
    const xs = [...new Set(series.flatMap(s => s.points.map(p => p[0])))].sort((a, b) => a - b);
    if (!xs.length) { box.append(el("div", "empty", "No data in this range.")); return wrap; }
    // value of each series at each x, and the running totals that make the layers
    const at = series.map(s => new Map(s.points.map(p => [p[0], p[1] || 0])));
    const tops = [];
    let below = xs.map(() => 0);
    for (const m of at) { const top = xs.map((x, i) => below[i] + (m.get(x) || 0)); tops.push([below, top]); below = top; }
    const totals = below;
    // runs of points close enough together to join
    const runs = [];
    xs.forEach((x, i) => { if (i && x - xs[i - 1] <= gap) runs[runs.length - 1].push(i); else runs.push([i]); });
    responsive(box, w => {
      box.querySelector("svg")?.remove();
      const s = svg("svg", { viewBox: `0 0 ${w} ${height}`, height });
      const x0 = xs[0], x1 = xs[xs.length - 1] === x0 ? x0 + 1 : xs[xs.length - 1];
      const ticks = niceTicks(Math.max(...totals, 0));
      const top = ticks[ticks.length - 1] || 1;
      const X = v => M.l + (xs.length === 1 ? (w - M.l - M.r) / 2 : (v - x0) / (x1 - x0) * (w - M.l - M.r));
      const Y = v => M.t + (1 - v / top) * (height - M.t - M.b);
      for (const t of ticks) {
        s.append(svg("line", { class: "grid-line", x1: M.l, x2: w - M.r, y1: Y(t), y2: Y(t) }));
        const label = svg("text", { class: "axis-text", x: M.l - 8, y: Y(t) + 4, "text-anchor": "end" }); label.textContent = yFormat(t); s.append(label);
      }
      const n = Math.max(2, Math.min(6, Math.floor((w - M.l - M.r) / 110)));
      for (let i = 0; i < n && xs.length > 1; i++) {
        const v = x0 + (x1 - x0) * i / (n - 1);
        const t = svg("text", { class: "axis-text", x: X(v), y: height - 6, "text-anchor": i === 0 ? "start" : i === n - 1 ? "end" : "middle" });
        t.textContent = xFormat(v); s.append(t);
      }
      series.forEach((se, k) => {
        const [lo, hi] = tops[k];
        for (const run of runs) {
          let d;
          if (run.length === 1) {
            const i = run[0], x = X(xs[i]);
            if (hi[i] <= lo[i]) continue;
            d = `M${x - 2},${Y(lo[i])}H${x + 2}V${Y(hi[i])}H${x - 2}Z`;
          } else {
            d = run.map((i, j) => (j ? "L" : "M") + X(xs[i]).toFixed(1) + "," + Y(hi[i]).toFixed(1)).join("")
              + run.slice().reverse().map(i => "L" + X(xs[i]).toFixed(1) + "," + Y(lo[i]).toFixed(1)).join("") + "Z";
          }
          // a thin line of the panel colour on each layer keeps neighbouring fills apart
          s.append(svg("path", { d, fill: se.color, stroke: "var(--panel)", "stroke-width": 1, "stroke-linejoin": "round" }));
        }
      });
      const hair = svg("line", { class: "hair", y1: M.t, y2: height - M.b, visibility: "hidden" });
      s.append(hair);
      const hit = svg("rect", { x: M.l, y: 0, width: w - M.l - M.r, height, fill: "transparent" });
      s.append(hit);
      hit.addEventListener("pointermove", e => {
        const r = s.getBoundingClientRect(), px = (e.clientX - r.left) * (w / r.width);
        let best = 0;
        xs.forEach((v, i) => { if (Math.abs(X(v) - px) < Math.abs(X(xs[best]) - px)) best = i; });
        hair.setAttribute("x1", X(xs[best])); hair.setAttribute("x2", X(xs[best])); hair.setAttribute("visibility", "visible");
        // top layer first, as they're stacked on screen
        const rows = series.map((se, k) => ({ color: se.color, value: yFormat(at[k].get(xs[best]) || 0), label: se.name })).reverse();
        rows.push({ value: yFormat(totals[best]), label: totalLabel });
        tipAt(tip, box, X(xs[best]) * r.width / w, e.clientY - r.top, rows, xFormat(xs[best]));
      });
      hit.addEventListener("pointerleave", () => { hair.setAttribute("visibility", "hidden"); tip.style.display = "none"; });
      box.prepend(s);
    });
    return wrap;
  }

  // bars: [{label, value, tip?}] in order; one colour
  function bars(opts) {
    const { data, height = 170, color = "var(--s1)", yFormat = String, name = "" } = opts;
    const box = el("div", "chart"), tip = el("div", "tip");
    box.append(tip);
    if (!data.length) { box.append(el("div", "empty", "No data in this range.")); return box; }
    responsive(box, w => {
      box.querySelector("svg")?.remove();
      const s = svg("svg", { viewBox: `0 0 ${w} ${height}`, height });
      const ticks = niceTicks(Math.max(...data.map(d => d.value), 0));
      const top = ticks[ticks.length - 1] || 1;
      const Y = v => M.t + (1 - v / top) * (height - M.t - M.b);
      for (const t of ticks) {
        s.append(svg("line", { class: "grid-line", x1: M.l, x2: w - M.r, y1: Y(t), y2: Y(t) }));
        const label = svg("text", { class: "axis-text", x: M.l - 8, y: Y(t) + 4, "text-anchor": "end" }); label.textContent = yFormat(t); s.append(label);
      }
      const slot = (w - M.l - M.r) / data.length, bw = Math.max(2, Math.min(36, slot - 2));
      const every = Math.ceil(data.length / Math.max(1, Math.floor((w - M.l - M.r) / 70)));
      data.forEach((d, i) => {
        const x = M.l + i * slot + (slot - bw) / 2, y = Y(d.value), h = height - M.b - y, r = Math.min(4, bw / 2, h);
        if (h > 0) s.append(svg("path", { fill: color, d: `M${x},${height - M.b}V${y + r}Q${x},${y} ${x + r},${y}H${x + bw - r}Q${x + bw},${y} ${x + bw},${y + r}V${height - M.b}Z` }));
        if (i % every === 0) { const t = svg("text", { class: "axis-text", x: x + bw / 2, y: height - 6, "text-anchor": "middle" }); t.textContent = d.label; s.append(t); }
        const hit = svg("rect", { x: M.l + i * slot, y: M.t, width: slot, height: height - M.t - M.b, fill: "transparent" });
        hit.addEventListener("pointermove", e => {
          const r = s.getBoundingClientRect();
          tipAt(tip, box, (x + bw / 2) * r.width / w, (y) * r.height / height, [{ color, value: yFormat(d.value), label: name }].concat(d.extra || []), d.tip || d.label);
        });
        hit.addEventListener("pointerleave", () => tip.style.display = "none");
        s.append(hit);
      });
      box.prepend(s);
    });
    return box;
  }

  // one horizontal bar split into parts: [{name, value, color}], labelled underneath
  function stack(opts) {
    const { parts, format = String, height = 26 } = opts;
    const shown = parts.filter(p => p.value > 0);
    const total = shown.reduce((a, p) => a + p.value, 0);
    const box = el("div", "chart"), tip = el("div", "tip");
    box.append(tip);
    if (!total) { box.append(el("div", "empty", "No data in this range.")); return box; }
    responsive(box, w => {
      box.querySelector("svg")?.remove();
      const s = svg("svg", { viewBox: `0 0 ${w} ${height}`, height });
      let x = 0;
      shown.forEach((p, i) => {
        const pw = Math.max(1, p.value / total * w - (i < shown.length - 1 ? 2 : 0));
        const rect = svg("rect", { x, y: 0, width: pw, height, rx: 4, fill: p.color });
        rect.addEventListener("pointermove", e => {
          const r = s.getBoundingClientRect();
          tipAt(tip, box, e.clientX - r.left, 0, [{ color: p.color, value: format(p.value), label: p.name }], Math.round(100 * p.value / total) + "% of the total");
        });
        rect.addEventListener("pointerleave", () => tip.style.display = "none");
        s.append(rect);
        x += pw + 2;
      });
      box.prepend(s);
    });
    return el("div", null, box, el("div", { class: "legend", style: "margin-top:8px" },
      shown.map(p => { const i = el("i", "box"); i.style.background = p.color; return el("span", null, i, p.name + " ", el("b", { style: "color:var(--text)" }, format(p.value))); })));
  }

  return { line, area, bars, stack };
})();
