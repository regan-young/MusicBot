"use strict";
// Memory: what Gunnabot remembers about each person and about the group, which she brings up in conversation.
App.register("memory", (() => {
  let page;

  async function mount(view) {
    page = el("div", "page");
    view.append(page);
    page.append(el("p", "muted", "Loading…"));
    render(await api("/api/memory"));
  }

  function render(m) {
    const people = m.people.filter(p => p.notes.length);
    page.replaceChildren(
      el("p", { class: "note", style: "margin:0 0 18px" }, "She saves these herself: facts people tell her, moments the group would want remembered, and up to three highlights when a ?chat session ends. "
        + "She sees the shared memories and the facts about whoever's in voice every time she replies. Changes here apply on her next reply, no restart needed."),
      el("section", "block", el("h2", null, "Shared memories", el("small", "muted", " " + m.group.length + " of 40, oldest first; the newest 20 are in her context")),
        entry("group", m.group, "Something the group would want her to remember")),
      el("section", "block", el("h2", null, "About people"),
        people.length ? el("div", "grid wide", people.map(p => card(p.name, entry(p.id, p.notes, "Something about " + p.name))))
          : el("p", "empty", "Nobody yet.")));
  }

  // one person's notes, or the group's: each editable in place, plus a box to add one
  function entry(who, notes, placeholder) {
    const box = el("div", "memories");
    notes.forEach((n, i) => box.append(row(who, i, n)));
    if (!notes.length) box.append(el("p", "muted", "Nothing yet."));
    const add = el("input", { type: "text", placeholder, maxlength: 300 });
    const go = el("button", { type: "button", class: "primary" }, "Add");
    const submit = () => add.value.trim() && save({ who, index: -1, text: add.value }, "Remembered.");
    go.addEventListener("click", submit);
    add.addEventListener("keydown", e => { if (e.key === "Enter") submit(); });
    box.append(el("div", "memory add", add, go));
    return box;
  }

  function row(who, index, before) {
    const input = el("textarea", { rows: 1, maxlength: 300, "aria-label": "Memory" });
    input.value = before;
    requestAnimationFrame(() => grow(input));
    const keep = el("button", { type: "button", disabled: true }, "Save");
    let armed;
    const drop = el("button", { type: "button", class: "danger" }, "Forget");
    input.addEventListener("input", () => { grow(input); keep.disabled = !input.value.trim() || input.value === before; });
    input.addEventListener("keydown", e => {
      if (e.key === "Enter") { e.preventDefault(); if (!keep.disabled) keep.click(); }
      if (e.key === "Escape") { input.value = before; keep.disabled = true; grow(input); }
    });
    keep.addEventListener("click", () => save({ who, index, before, text: input.value }, "Saved."));
    drop.addEventListener("click", () => {
      if (!armed) { armed = setTimeout(() => { armed = null; drop.textContent = "Forget"; }, 3000); drop.textContent = "Sure?"; return; }
      save({ who, index, before, text: "" }, "Forgotten.");
    });
    return el("div", "memory", input, keep, drop);
  }

  // a box as tall as its text, so a long memory reads in full
  function grow(t) { t.style.height = "auto"; t.style.height = t.scrollHeight + 2 + "px"; }

  async function save(body, done) {
    try {
      render(await api("/api/memory", body));
      toast(done, true);
    } catch (e) {
      toast(e.message);
      if (e.status === 409) render(await api("/api/memory"));
    }
  }

  return { mount };
})());
