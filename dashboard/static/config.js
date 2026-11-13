"use strict";
// Config: the few settings that are safe to change from here, and a restart to apply them.
App.register("config", (() => {
  let page, data;

  async function mount(view) {
    page = el("div", "page");
    view.append(page);
    await load();
  }

  async function load() {
    data = await api("/api/config");
    const groups = [...new Set(data.keys.map(k => k.group))];
    page.replaceChildren(banner(), el("p", "muted", "Changes are saved to config.txt straight away, with a backup of the file before each one. The bot reads its config when it starts, so restart it to apply them. Keys, tokens and addresses aren't shown or changed here."));
    for (const g of groups) page.append(el("section", "block", el("h2", null, g), el("div", "card", data.keys.filter(k => k.group === g).map(setting))));
  }

  function banner() {
    const box = el("div", "banner" + (data.pending ? "" : " ok"));
    if (!data.pending) { box.append(el("span", "status good", "The bot is running with the saved config")); return box; }
    const go = el("button", { type: "button", class: "primary" }, "Restart the bot");
    const msg = el("span", null, el("span", "status warning", "Saved changes are waiting for a restart"));
    go.addEventListener("click", () => restart(go, msg, false));
    box.append(msg, go);
    return box;
  }

  // first click asks; if people are talking to the bot the server refuses, and a second click overrides
  async function restart(go, msg, force) {
    if (!go.dataset.armed) {
      go.dataset.armed = 1;
      go.textContent = "Restart now: it leaves voice for about 20 seconds";
      setTimeout(() => { if (go.isConnected && !go.disabled) { delete go.dataset.armed; go.textContent = "Restart the bot"; } }, 6000);
      return;
    }
    go.disabled = true; go.textContent = "Restarting…";
    try {
      await api("/api/bot/restart", { force });
      toast("The bot is restarting.", true);
      setTimeout(() => { load(); App.status(); }, 8000);
    } catch (e) {
      go.disabled = false;
      if (e.status === 409) {
        msg.replaceChildren(el("span", "status serious", "People are talking to the bot right now"));
        const again = el("button", { type: "button", class: "danger" }, "Restart anyway");
        again.addEventListener("click", () => { again.dataset.armed = 1; restart(again, msg, true); });
        go.replaceWith(again);
      } else { toast("Restart failed: " + e.message); go.textContent = "Restart the bot"; delete go.dataset.armed; }
    }
  }

  function setting(k) {
    const what = el("div", "what", el("b", null, k.label), el("p", null, k.help), el("code", null, k.key));
    const how = el("div", "how");
    let input, read;
    if (k.kind === "text") {
      input = el("textarea", { class: "field", spellcheck: "true" });
      input.value = k.value ?? "";
      read = () => input.value;
    } else if (k.kind === "tags") {
      input = el("div", "chips", k.limits.map(t => el("label", null, el("input", { type: "checkbox", value: t, checked: (k.value || []).includes(t) }), t)));
      read = () => [...input.querySelectorAll("input:checked")].map(i => i.value);
    } else if (k.kind === "int" || k.kind === "float") {
      input = el("input", { type: "number", min: k.limits[0], max: k.limits[1], step: k.kind === "float" ? "0.05" : "1", value: k.value ?? "" });
      read = () => input.value === "" ? "" : Number(input.value);
    } else {
      input = el("input", { type: "text", value: k.value ?? "", spellcheck: "false", placeholder: k.kind === "model?" ? "empty turns it off" : "" });
      read = () => input.value.trim();
    }
    const save = el("button", { type: "button", class: "primary", disabled: true }, "Save");
    const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
    const changed = () => { save.disabled = same(read(), k.value); };
    input.addEventListener("input", changed);
    input.addEventListener("change", changed);
    save.addEventListener("click", async () => {
      save.disabled = true;
      try {
        const got = await api("/api/config", { key: k.key, value: read() });
        k.value = got.value; k.set = true;
        toast(k.label + " saved. Restart the bot to apply it.", true);
        data.pending = true;
        page.querySelector(".banner").replaceWith(banner());
        App.status();
      } catch (e) { toast(e.message); changed(); }
    });
    const row = el("div", "row1", save);
    if (k.default != null && !same(k.value, k.default) && k.kind !== "text") {
      const reset = el("button", { type: "button" }, "Use the default");
      reset.addEventListener("click", () => {
        if (k.kind === "tags") input.querySelectorAll("input").forEach(i => i.checked = k.default.includes(i.value));
        else input.value = k.default;
        changed();
      });
      row.append(reset);
    }
    if (k.kind === "text" && k.default != null && !same(k.value, k.default)) {
      const reset = el("button", { type: "button" }, "Put back the default persona");
      reset.addEventListener("click", () => { input.value = k.default; changed(); });
      row.append(reset);
    }
    const def = k.default == null ? "" : k.kind === "text" ? (k.set ? "" : "Using the default persona.")
      : "Default: " + (Array.isArray(k.default) ? (k.default.join(", ") || "none") : (k.default === "" ? "empty" : k.default));
    how.append(input, row, def ? el("div", "def", def) : null);
    return el("div", "setting", what, how);
  }

  return { mount };
})());
