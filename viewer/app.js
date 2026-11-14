/* Chat Archive viewer - reads the index built by wa_archive from Google Drive (or a local
   folder in demo mode) and shows it like WhatsApp. No server; everything runs in the browser. */
"use strict";

// ---------------------------------------------------------------- helpers
const $ = (sel, root = document) => root.querySelector(sel);
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "html") el.innerHTML = v; // only used with trusted, static SVG strings
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid instanceof Node ? kid : String(kid));
  return el;
}
const ICON = {
  back: '<svg viewBox="0 0 24 24"><path d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z"/></svg>',
  search: '<svg viewBox="0 0 24 24"><path d="M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 9.5 16a6.5 6.5 0 0 0 4.2-1.6l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"/></svg>',
  cal: '<svg viewBox="0 0 24 24"><path d="M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 16H5V9h14v11zM7 11h5v5H7z"/></svg>',
  media: '<svg viewBox="0 0 24 24"><path d="M22 16V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2zm-11-4 2 2.7L16 11l4 5H8l3-4zM2 6v14a2 2 0 0 0 2 2h14v-2H4V6H2z"/></svg>',
  play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  cam: '<svg viewBox="0 0 24 24"><path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z"/></svg>',
  dl: '<svg viewBox="0 0 24 24"><path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/></svg>',
  left: '<svg viewBox="0 0 24 24"><path d="M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12z"/></svg>',
  right: '<svg viewBox="0 0 24 24"><path d="M10 6 8.6 7.4 13.2 12l-4.6 4.6L10 18l6-6z"/></svg>',
  group: '<svg viewBox="0 0 24 24"><path d="M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm-8 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm0 2c-2.3 0-7 1.2-7 3.5V19h14v-2.5C15 14.2 10.3 13 8 13zm8 0h-.9c1.1.8 1.9 1.9 1.9 3.5V19h6v-2.5c0-2.3-4.7-3.5-7-3.5z"/></svg>',
  gear: '<svg viewBox="0 0 24 24"><path d="M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.3 7.3 0 0 0-1.7-1L15 3h-4l-.4 2.9a7.3 7.3 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1L11 21h4l.4-2.9c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5L19.4 13zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"/></svg>',
  mic: '<svg viewBox="0 0 24 24"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11h-2z"/></svg>',
};
const icon = (name) => h("span", { html: ICON[name], style: "display:contents" });
const avatarColors = ["#e17076", "#7bc862", "#e5ca77", "#65aadd", "#a695e7", "#ee7aae", "#6ec9cb", "#faa774"];
function avatar(chat, small) {
  const c = avatarColors[parseInt(chat.id.slice(0, 6), 16) % avatarColors.length];
  const letter = (chat.name.replace(/^\+/, "").match(/\p{L}|\d/u) || ["?"])[0].toUpperCase();
  return h("div", { class: "avatar" + (small ? " sm" : ""), style: `background:${c}` },
    chat.g ? icon("group") : letter);
}
const senderColors = ["#1f7aec", "#e542a3", "#d88e00", "#06a888", "#7f66ff", "#e26a00", "#029d00", "#c2186b"];
const senderColor = (s) => senderColors[[...s].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % senderColors.length];

const fmtTime = (t) => new Date(t).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const dayKey = (t) => { const d = new Date(t); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; };
function fmtDay(t) {
  const d = new Date(t), now = new Date();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (dayKey(t) === dayKey(+now)) return "Today";
  if (dayKey(t) === dayKey(+y)) return "Yesterday";
  return d.toLocaleDateString([], { day: "numeric", month: "long", year: "numeric" });
}
function fmtListDate(t) {
  const d = new Date(t), now = new Date();
  if (dayKey(t) === dayKey(+now)) return fmtTime(t);
  if (now - d < 6 * 864e5) return d.toLocaleDateString([], { weekday: "long" });
  return d.toLocaleDateString([], { day: "2-digit", month: "2-digit", year: "2-digit" });
}
const fmtMonth = (m) => new Date(+m.slice(0, 4), +m.slice(5) - 1, 1).toLocaleDateString([], { month: "long", year: "numeric" });
const fmtSize = (b) => !b ? "" : b < 1024 ? b + " B" : b < 1048576 ? (b / 1024).toFixed(0) + " kB" : (b / 1048576).toFixed(1) + " MB";
const fmtDur = (s) => !s ? "" : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
const baseName = (p) => (p || "").split("/").pop();
const fileName = (m) => (m.m && (m.m.n || baseName(m.m.p))) || `file-${m.i}`;
const thumbSrc = (m) => m.m && m.m.th ? "data:image/jpeg;base64," + m.m.th : null;
const isVisual = (k) => k === "image" || k === "video" || k === "gif";
function toast(text, ms = 2600) {
  const t = $("#toast"); t.textContent = text; t.hidden = false;
  clearTimeout(toast._t); toast._t = setTimeout(() => (t.hidden = true), ms);
}
const URL_RE = /https?:\/\/[^\s<>"')]+/gi;
function richText(text, query) {
  // Linkify + optional highlight, built with DOM nodes (never innerHTML of user text).
  const frag = document.createDocumentFragment();
  let last = 0;
  const pushText = (s) => {
    if (!query) return frag.append(s);
    const q = query.toLowerCase(); let i = 0, j;
    while ((j = s.toLowerCase().indexOf(q, i)) >= 0) {
      frag.append(s.slice(i, j), h("mark", { class: "hl" }, s.slice(j, j + q.length))); i = j + q.length;
    }
    frag.append(s.slice(i));
  };
  for (const m of text.matchAll(URL_RE)) {
    pushText(text.slice(last, m.index));
    frag.append(h("a", { href: m[0], target: "_blank", rel: "noopener noreferrer" }, m[0]));
    last = m.index + m[0].length;
  }
  pushText(text.slice(last));
  return frag;
}

// ---------------------------------------------------------------- data sources
const settings = (() => {
  let s = {};
  try { s = JSON.parse(localStorage.getItem("wa-settings") || "{}"); } catch {}
  return {
    get: (k) => s[k] || (window.WA_CONFIG || {})[k] || "",
    set: (k, v) => { s[k] = v; try { localStorage.setItem("wa-settings", JSON.stringify(s)); } catch {} },
  };
})();

async function readWithProgress(res, onProgress) {
  const total = +res.headers.get("content-length") || 0;
  if (!res.body || !onProgress) return res.blob();
  const reader = res.body.getReader(); const chunks = []; let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value); got += value.length; onProgress(got, total);
  }
  return new Blob(chunks, { type: res.headers.get("content-type") || "" });
}

class LocalSource { // ?demo=path/ -> path/index/*.json and path/raw/<media path>
  constructor(base) { this.base = base.endsWith("/") ? base : base + "/"; this.kind = "local"; }
  async init() {}
  async getJSON(name) {
    const r = await fetch(this.base + "index/" + name);
    if (!r.ok) throw new Error(`${name}: ${r.status}`);
    return r.json();
  }
  async getMedia(m, onProgress) {
    const r = await fetch(this.base + "raw/" + m.p.split("/").map(encodeURIComponent).join("/"));
    if (!r.ok) throw new Error("File not found in backup");
    return readWithProgress(r, onProgress);
  }
}

class DriveSource {
  constructor(clientId, folderName) {
    this.clientId = clientId; this.folderName = folderName; this.kind = "drive";
    this.files = new Map(); this.token = null;
    try { const t = JSON.parse(sessionStorage.getItem("wa-token") || "null"); if (t && t.exp > Date.now() + 60e3) this.token = t; } catch {}
  }
  static loadGIS() {
    if (window.google && google.accounts) return Promise.resolve();
    return new Promise((ok, fail) => {
      const s = document.createElement("script");
      s.src = "https://accounts.google.com/gsi/client"; s.onload = ok; s.onerror = () => fail(new Error("Could not load Google sign-in"));
      document.head.append(s);
    });
  }
  async signIn(interactive) {
    await DriveSource.loadGIS();
    return new Promise((ok, fail) => {
      const client = google.accounts.oauth2.initTokenClient({
        client_id: this.clientId,
        scope: "https://www.googleapis.com/auth/drive.readonly",
        callback: (r) => {
          if (r.error) return fail(new Error(r.error_description || r.error));
          this.token = { v: r.access_token, exp: Date.now() + (r.expires_in - 60) * 1000 };
          try { sessionStorage.setItem("wa-token", JSON.stringify(this.token)); } catch {}
          ok();
        },
        error_callback: (e) => fail(new Error(e.message || e.type || "Sign-in cancelled")),
      });
      client.requestAccessToken({ prompt: interactive ? "" : "none" });
    });
  }
  get signedIn() { return this.token && this.token.exp > Date.now(); }
  async api(url, opts = {}) {
    if (!this.signedIn) await this.signIn(false).catch(() => { throw new NeedSignIn(); });
    let r = await fetch(url, { ...opts, headers: { Authorization: "Bearer " + this.token.v } });
    if (r.status === 401) { this.token = null; await this.signIn(false).catch(() => { throw new NeedSignIn(); });
      r = await fetch(url, { ...opts, headers: { Authorization: "Bearer " + this.token.v } }); }
    if (!r.ok) throw new Error(`Google Drive error ${r.status}: ${(await r.text()).slice(0, 200)}`);
    return r;
  }
  async query(q, fields = "files(id,name,size,modifiedTime,mimeType),nextPageToken") {
    const out = []; let page = "";
    do {
      const u = new URL("https://www.googleapis.com/drive/v3/files");
      u.search = new URLSearchParams({ q, fields, pageSize: "1000", spaces: "drive", ...(page ? { pageToken: page } : {}) });
      const j = await (await this.api(u)).json();
      out.push(...j.files); page = j.nextPageToken;
    } while (page);
    return out;
  }
  async init() {
    const esc = (s) => s.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
    const roots = await this.query(`name='${esc(this.folderName)}' and mimeType='application/vnd.google-apps.folder' and trashed=false`);
    if (!roots.length) throw new Error(`No folder named "${this.folderName}" in your Google Drive.`);
    for (const root of roots) {
      const idx = await this.query(`name='index' and '${root.id}' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false`);
      if (!idx.length) continue;
      const files = await this.query(`'${idx[0].id}' in parents and trashed=false`);
      for (const f of files) this.files.set(f.name, f);
      if (this.files.has("manifest.json")) return;
    }
    throw new Error(`Found "${this.folderName}" but no index/manifest.json inside. Run the Colab indexer first.`);
  }
  async getJSON(name) {
    const f = this.files.get(name);
    if (!f) throw new Error(`${name} is missing from the index`);
    const key = `https://cache.local/${f.id}/${f.modifiedTime}`;
    let cache = null;
    try { cache = await caches.open("wa-json"); const hit = await cache.match(key); if (hit) return hit.json(); } catch {}
    const r = await this.api(`https://www.googleapis.com/drive/v3/files/${f.id}?alt=media`);
    const text = await r.text();
    try { await cache?.put(key, new Response(text, { headers: { "content-type": "application/json" } })); } catch {}
    return JSON.parse(text);
  }
  async getMedia(m, onProgress) {
    let id = m.d;
    if (!id) { // fall back to looking the file up by name (and size, when names repeat)
      const name = baseName(m.p).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
      const hits = await this.query(`name='${name}' and trashed=false`);
      const pick = hits.find((f) => m.z && +f.size === m.z) || hits[0];
      if (!pick) throw new Error("This file was never backed up to Drive.");
      id = m.d = pick.id;
    }
    const r = await this.api(`https://www.googleapis.com/drive/v3/files/${id}?alt=media`);
    return readWithProgress(r, onProgress);
  }
}
class NeedSignIn extends Error { constructor() { super("Please sign in to Google Drive"); } }

// ---------------------------------------------------------------- app state
const App = { src: null, manifest: null, chats: new Map(), monthCache: new Map() };

async function loadMonth(chatId, month) {
  const key = chatId + "/" + month;
  if (!App.monthCache.has(key)) {
    App.monthCache.set(key, App.src.getJSON(`chat-${chatId}-${month}.json`).then((j) => j.messages)
      .catch((e) => { App.monthCache.delete(key); throw e; }));
  }
  return App.monthCache.get(key);
}

function render(...nodes) { const app = $("#app"); app.replaceChildren(...nodes); }

// ---------------------------------------------------------------- chat list
function viewChatList() {
  const input = h("input", { type: "search", placeholder: "Search chats", "aria-label": "Search chats", value: viewChatList.q || "" });
  const list = h("div", { class: "list" });
  const draw = () => {
    const q = (viewChatList.q = input.value.trim().toLowerCase());
    const rows = App.manifest.chats.filter((c) => !q || c.name.toLowerCase().includes(q) || (c.phone || "").includes(q.replace(/\D/g, "") || "~"));
    list.replaceChildren(...rows.map((c) => {
      const media = (c.counts.image || 0) + (c.counts.video || 0);
      return h("button", { class: "row", onclick: () => go(`#/c/${c.id}`) },
        avatar(c),
        h("div", { class: "body" },
          h("div", { class: "top" }, h("span", { class: "name" }, c.name), h("span", { class: "date" }, fmtListDate(c.last_t))),
          h("div", { class: "bot" }, h("span", { class: "prev" }, (c.last_f ? "You: " : "") + c.last_x),
            media ? h("span", { class: "cnt" }, `${media} 📷`) : null)));
    }));
    if (!rows.length) list.append(h("div", { class: "empty" }, "No chats match"));
  };
  input.addEventListener("input", draw);
  render(
    h("div", { class: "bar" }, h("h1", {}, "Chat Archive"),
      h("button", { class: "icon-btn", "aria-label": "Settings", onclick: () => go("#/settings") }, icon("gear"))),
    h("div", { class: "search" }, input), list);
  draw();
}

// ---------------------------------------------------------------- message bubbles
function mediaNode(msg, onOpen) {
  const m = msg.m || {};
  const src = thumbSrc(msg);
  if (msg.k === "sticker") return h("div", {}, src ? h("img", { src, alt: "Sticker" }) : "Sticker");
  if (isVisual(msg.k)) {
    return h("button", { class: "thumb" + (m.miss ? " missing" : ""), onclick: onOpen, "aria-label": "Open " + msg.k },
      src ? h("img", { src, alt: "", loading: "lazy" }) : h("div", { class: "noimg" }, msg.k === "image" ? "📷 Photo" : "🎥 Video"),
      msg.k !== "image" ? h("span", { class: "play" }, h("i", { html: ICON.play })) : null,
      msg.k !== "image" && m.du ? h("span", { class: "dur" }, fmtDur(m.du)) : null,
      m.miss ? h("span", { class: "missing-tag" }, "Not in backup") : null);
  }
  if (msg.k === "document") return docNode(msg, onOpen);
  if (msg.k === "audio") {
    const box = h("div", { class: "voice" });
    const btn = h("button", { class: "pbtn", "aria-label": "Play voice message", html: ICON.play });
    btn.onclick = async () => {
      btn.disabled = true;
      try {
        const blob = await App.src.getMedia(m);
        const a = h("audio", { controls: true, autoplay: true, src: URL.createObjectURL(blob) });
        box.replaceChildren(a);
      } catch (e) { btn.disabled = false; toast(e.message); }
    };
    box.append(btn, h("span", {}, (m.miss ? "Voice message (not in backup) " : "Voice message ") + fmtDur(m.du)));
    return box;
  }
  return h("span", {}, msg.k);
}
function docIcon(name) {
  const ext = (name.split(".").pop() || "").toUpperCase().slice(0, 4);
  const cls = /PDF/.test(ext) ? "" : /XLS|CSV/.test(ext) ? " x" : /DOC|TXT/.test(ext) ? " w" : " o";
  return h("div", { class: "ico" + cls }, ext || "FILE");
}
function docNode(msg, onOpen) {
  const name = fileName(msg);
  return h("button", { class: "doc", onclick: onOpen },
    docIcon(name),
    h("div", { class: "dn" }, h("b", {}, name), h("small", {}, [fmtSize(msg.m.z), msg.m.miss ? "not in backup" : ""].filter(Boolean).join(" · "))),
    h("span", { html: ICON.dl, style: "width:22px;fill:var(--muted);display:inline-block" }));
}
const LABELS = { contact: "👤 Contact card", location: "📍 Location", poll: "📊 Poll", other: "Unsupported message" };

function bubble(msg, prev, chat, ctx) {
  const first = !prev || prev.f !== msg.f || prev.s !== msg.s || dayKey(prev.t) !== dayKey(msg.t);
  const isMedia = !!msg.m && msg.k !== "audio" && msg.k !== "document";
  const b = h("div", { class: "bubble" + (isMedia ? " media" : "") + (msg.k === "sticker" ? " sticker" : "") });
  if (chat.g && !msg.f && first && msg.s) b.append(h("div", { class: "sender", style: `color:${senderColor(msg.s)}` }, msg.s));
  if (msg.m) b.append(mediaNode(msg, () => ctx.open(msg)));
  if (msg.k === "deleted") b.append(h("span", { class: "deleted" }, "🚫 This message was deleted"));
  else if (LABELS[msg.k] && !msg.x) b.append(h("span", { class: "deleted" }, LABELS[msg.k]));
  const meta = h("span", { class: "meta" }, fmtTime(msg.t));
  if (msg.x) b.append(h("span", { class: "text" }, richText(msg.x, ctx.query)), meta);
  else b.append(meta);
  return h("div", { class: `msg ${msg.f ? "out" : "in"}${first ? " first" : ""}`, id: "m" + msg.i }, b);
}

// ---------------------------------------------------------------- chat view
async function viewChat(chat, startMonth, focusId, query) {
  const months = chat.months;
  let hi = startMonth ? months.indexOf(startMonth) : months.length - 1;
  if (hi < 0) hi = months.length - 1;
  let lo = hi + 1; // loaded range is [lo, hi]
  const body = h("div", { class: "chat" });
  const topS = h("div", { class: "sentinel" }), botS = h("div", { class: "sentinel" });
  const content = h("div");
  body.append(topS, content, botS);
  let visible = []; // messages in DOM order, used by the lightbox
  const ctx = { query, open: (msg) => openMedia(msg, visible.filter((x) => x.m && (isVisual(x.k) || x.k === "document")), chat) };
  const sub = `${chat.n.toLocaleString()} messages · since ${fmtMonth(months[0])}`;

  render(
    h("div", { class: "bar" },
      h("button", { class: "icon-btn", "aria-label": "Back", onclick: () => go("#/") }, icon("back")),
      avatar(chat, true),
      h("div", { class: "title" }, h("b", {}, chat.name), h("small", {}, sub)),
      h("button", { class: "icon-btn", "aria-label": "Search in chat", onclick: () => openSearch(chat) }, icon("search")),
      h("button", { class: "icon-btn", "aria-label": "Jump to month", onclick: () => pickMonth(chat) }, icon("cal")),
      h("button", { class: "icon-btn", "aria-label": "Media, docs and links", onclick: () => go(`#/c/${chat.id}/media`) }, icon("media"))),
    body);

  const drawAll = (msgsByMonth) => {
    const all = msgsByMonth.flat();
    visible = all;
    const nodes = []; let prev = null;
    for (const msg of all) {
      if (!prev || dayKey(prev.t) !== dayKey(msg.t)) nodes.push(h("div", { class: "daychip" }, h("span", {}, fmtDay(msg.t))));
      nodes.push(bubble(msg, prev, chat, ctx)); prev = msg;
    }
    content.replaceChildren(...nodes);
  };
  const loaded = new Map();
  let busy = false;
  async function extend(dir) {
    const idx = dir < 0 ? lo - 1 : hi + 1;
    if (busy || idx < 0 || idx >= months.length) return;
    busy = true;
    try {
      loaded.set(idx, await loadMonth(chat.id, months[idx]));
      if (dir < 0) lo = idx; else hi = idx;
      const before = body.scrollHeight, top = body.scrollTop;
      drawAll([...loaded.keys()].sort((a, b) => a - b).map((k) => loaded.get(k)));
      if (dir < 0) body.scrollTop = top + (body.scrollHeight - before);
    } catch (e) { handleError(e); } finally { busy = false; }
  }
  // initial: load the start month (+ older ones until the screen is filled)
  lo = hi;
  try { loaded.set(hi, await loadMonth(chat.id, months[hi])); } catch (e) { return handleError(e); }
  drawAll([loaded.get(hi)]);
  while (body.scrollHeight <= body.clientHeight + 50 && lo > 0) await extend(-1);
  if (focusId) {
    const el = $("#m" + focusId, content);
    if (el) { el.scrollIntoView({ block: "center" }); el.querySelector(".bubble").style.outline = "2px solid var(--accent)"; }
  } else if (startMonth) {
    const firstOfMonth = $("#m" + loaded.get(hi)[0].i, content);
    if (firstOfMonth) { body.scrollTop = 0; body.scrollTop = firstOfMonth.offsetTop - 60; }
  }
  else body.scrollTop = body.scrollHeight;
  const io = new IntersectionObserver((ents) => ents.forEach((e) => {
    if (!e.isIntersecting) return;
    if (e.target === topS && lo > 0) extend(-1);
    if (e.target === botS && hi < months.length - 1) extend(1);
  }), { root: body, rootMargin: "600px 0px" });
  setTimeout(() => { io.observe(topS); io.observe(botS); }, 300);
}

function modal(title, items) {
  const close = () => m.remove();
  const m = h("div", { class: "modal", onclick: (e) => e.target === m && close() },
    h("div", { class: "sheet", role: "dialog", "aria-label": title }, h("h3", {}, title),
      ...items.map(([label, right, fn]) => h("button", { class: "opt", onclick: () => { close(); fn(); } }, h("span", {}, label), h("small", {}, right)))));
  document.body.append(m);
}
function pickMonth(chat) {
  const items = [...chat.months].reverse().map((mo) => [fmtMonth(mo),
    chat.media_months[mo] ? `${chat.media_months[mo]} media` : "", () => go(`#/c/${chat.id}?month=${mo}`)]);
  modal("Jump to month", items);
}

async function openSearch(chat) {
  const input = h("input", { type: "search", placeholder: "Search messages, captions, file names", autofocus: true });
  const results = h("div", { class: "list" });
  render(
    h("div", { class: "bar" },
      h("button", { class: "icon-btn", "aria-label": "Back", onclick: () => go(`#/c/${chat.id}`) }, icon("back")),
      h("div", { class: "title" }, h("b", {}, "Search in " + chat.name))),
    h("div", { class: "search" }, input), results);
  input.focus();
  let all = null;
  const run = async () => {
    const q = input.value.trim().toLowerCase();
    if (q.length < 2) return results.replaceChildren(h("div", { class: "empty" }, "Type at least 2 letters"));
    if (!all) {
      results.replaceChildren(h("div", { class: "loading" }, "Loading all messages…"));
      try { all = (await Promise.all(chat.months.map((mo) => loadMonth(chat.id, mo).then((ms) => ms.map((x) => [mo, x]))))).flat(); }
      catch (e) { return handleError(e); }
    }
    const hits = all.filter(([, x]) => (x.x || "").toLowerCase().includes(q) || (x.m && (x.m.n || "").toLowerCase().includes(q))).reverse().slice(0, 300);
    results.replaceChildren(...hits.map(([mo, x]) => h("button", { class: "lrow", onclick: () => go(`#/c/${chat.id}?month=${mo}&focus=${x.i}&q=${encodeURIComponent(q)}`) },
      h("div", { class: "dn" }, h("b", {}, richText(x.x || (x.m && x.m.n) || "", q)),
        h("small", {}, `${fmtDay(x.t)} · ${x.f ? "You" : x.s || chat.name}`)))));
    if (!hits.length) results.append(h("div", { class: "empty" }, "No results"));
  };
  let t; input.addEventListener("input", () => { clearTimeout(t); t = setTimeout(run, 250); });
}

// ---------------------------------------------------------------- media / docs / links screen
async function viewMedia(chat, tab) {
  const tabs = [["media", "Media"], ["docs", "Docs"], ["links", "Links"]];
  const list = h("div", { class: "list" });
  const selected = new Map();
  const selbar = h("div", { class: "selbar", hidden: true });
  const updateSel = () => {
    selbar.hidden = !selected.size;
    list.classList.toggle("selecting", selected.size > 0);
    selbar.replaceChildren(h("span", {}, `${selected.size} selected`),
      h("button", { class: "pill ghost", onclick: () => { selected.clear(); list.querySelectorAll(".sel").forEach((e) => e.classList.remove("sel")); updateSel(); } }, "Clear"),
      h("button", { class: "pill", onclick: () => downloadMany([...selected.values()]) }, "Download"));
  };
  render(
    h("div", { class: "bar" },
      h("button", { class: "icon-btn", "aria-label": "Back", onclick: () => go(`#/c/${chat.id}`) }, icon("back")),
      h("div", { class: "title" }, h("b", {}, chat.name), h("small", {}, [[chat.counts.image, "photo"], [chat.counts.video, "video"], [chat.counts.document, "doc"]].map(([n, w]) => `${n || 0} ${w}${n === 1 ? "" : "s"}`).join(" · ")))),
    h("div", { class: "tabs", role: "tablist" }, ...tabs.map(([k, label]) =>
      h("button", { class: k === tab ? "on" : "", role: "tab", "aria-selected": String(k === tab), onclick: () => go(`#/c/${chat.id}/media?tab=${k}`, true) }, label))),
    list, selbar);

  const months = tab === "media" ? Object.keys(chat.media_months).sort().reverse() : [...chat.months].reverse();
  const filter = tab === "media" ? (x) => x.m && isVisual(x.k)
    : tab === "docs" ? (x) => x.k === "document"
    : (x) => x.l;
  let i = 0, shown = 0, busy = false;
  const sentinel = h("div", { class: "loading" }, "Loading…");
  list.append(sentinel);
  const allItems = [];
  const toggle = (el, x) => {
    if (selected.has(x.i)) { selected.delete(x.i); el.classList.remove("sel"); } else { selected.set(x.i, x); el.classList.add("sel"); }
    updateSel();
  };
  const press = (el, x, open) => { // tap opens (or toggles while selecting); long-press selects
    let timer, long = false;
    el.addEventListener("pointerdown", () => { long = false; timer = setTimeout(() => { long = true; toggle(el, x); }, 450); });
    ["pointerup", "pointerleave", "pointercancel"].forEach((ev) => el.addEventListener(ev, () => clearTimeout(timer)));
    el.addEventListener("contextmenu", (e) => e.preventDefault());
    el.addEventListener("click", () => { if (long) return; selected.size ? toggle(el, x) : open(); });
  };
  async function more() {
    if (busy) return; busy = true;
    try {
      while (i < months.length) {
        const mo = months[i++];
        const items = (await loadMonth(chat.id, mo)).filter(filter).reverse();
        if (!items.length) continue;
        allItems.push(...items);
        const sec = [h("div", { class: "month-h" }, fmtMonth(mo))];
        if (tab === "media") {
          const grid = h("div", { class: "grid" });
          for (const x of items) {
            const src = thumbSrc(x);
            const el = h("div", { class: "tile" + (x.m.miss ? " missing" : ""), role: "button", tabindex: "0", "aria-label": `${x.k} ${fmtDay(x.t)}` },
              src ? h("img", { src, alt: "", loading: "lazy" }) : h("div", { class: "noimg" }, x.k),
              x.k !== "image" ? h("span", { class: "dur" }, h("span", { html: ICON.cam, style: "display:contents" }), fmtDur(x.m.du)) : null,
              x.m.miss ? h("span", { class: "missing-tag" }, "Not in backup") : null,
              h("span", { class: "chk" }));
            press(el, x, () => openMedia(x, allItems, chat));
            grid.append(el);
          }
          sec.push(grid);
        } else if (tab === "docs") {
          for (const x of items) {
            const name = fileName(x);
            const el = h("div", { class: "lrow", role: "button", tabindex: "0" }, h("span", { class: "chk" }), docIcon(name),
              h("div", { class: "dn" }, h("b", {}, name), h("small", {}, [fmtSize(x.m.z), fmtDay(x.t), x.f ? "You" : x.s || chat.name].filter(Boolean).join(" · "))));
            press(el, x, () => download(x));
            sec.push(el);
          }
        } else {
          for (const x of items) {
            for (const url of x.x.match(URL_RE) || []) {
              sec.push(h("a", { class: "lrow", href: url, target: "_blank", rel: "noopener noreferrer", style: "text-decoration:none;color:inherit" },
                h("div", { class: "dn" }, h("b", { style: "color:var(--link)" }, url), h("small", {}, `${fmtDay(x.t)} · ${x.x.slice(0, 80)}`))));
            }
          }
        }
        list.insertBefore(h("section", {}, ...sec), sentinel);
        shown += items.length;
        if (list.scrollHeight > list.clientHeight * 2) break;
      }
      if (i >= months.length) sentinel.textContent = shown ? "" : `No ${tab} in this chat`;
    } catch (e) { handleError(e); } finally { busy = false; }
  }
  await more();
  new IntersectionObserver((e) => e[0].isIntersecting && more(), { root: list, rootMargin: "800px" }).observe(sentinel);
}

// ---------------------------------------------------------------- lightbox + downloads
const blobCache = new Map(); // msg id -> object URL (kept small)
async function fetchBlob(x, onProgress) {
  if (blobCache.has(x.i)) return blobCache.get(x.i);
  const blob = await App.src.getMedia(x.m, onProgress);
  const url = URL.createObjectURL(blob);
  blobCache.set(x.i, url);
  if (blobCache.size > 12) { const [k, v] = blobCache.entries().next().value; URL.revokeObjectURL(v); blobCache.delete(k); }
  return url;
}
async function download(x) {
  if (x.m.miss && App.src.kind !== "drive") return toast("This file is not in the backup");
  toast("Downloading " + fileName(x) + "…", 60000);
  try {
    const url = await fetchBlob(x, (g, t) => t && toast(`Downloading ${fileName(x)} ${Math.round((g / t) * 100)}%`, 60000));
    h("a", { href: url, download: fileName(x) }).click();
    toast("Saved " + fileName(x));
  } catch (e) { handleError(e); }
}
async function downloadMany(items) {
  for (let n = 0; n < items.length; n++) {
    toast(`Downloading ${n + 1} of ${items.length}…`, 60000);
    try {
      const url = await fetchBlob(items[n]);
      h("a", { href: url, download: fileName(items[n]) }).click();
      await new Promise((r) => setTimeout(r, 400)); // browsers throttle rapid downloads
    } catch (e) { toast(`${fileName(items[n])}: ${e.message}`); }
  }
  toast(`Done: ${items.length} files`);
}
function openMedia(x, list, chat) {
  if (x.k === "document") return download(x);
  list = list.filter((y) => isVisual(y.k));
  let pos = Math.max(0, list.findIndex((y) => y.i === x.i));
  const stage = h("div", { class: "stage" });
  const title = h("div", { class: "title" });
  const cap = h("div", { class: "cap" });
  const close = () => { lb.remove(); removeEventListener("keydown", onKey); };
  const show = async () => {
    const cur = list[pos];
    title.replaceChildren(h("b", {}, cur.f ? "You" : cur.s || chat.name), h("small", {}, `${fmtDay(cur.t)}, ${fmtTime(cur.t)}`));
    cap.textContent = cur.x || ""; cap.hidden = !cur.x;
    const src = thumbSrc(cur);
    const prog = h("div", { class: "progress" }, cur.m.miss && App.src.kind !== "drive" ? "Not in backup — only the preview survived" : "Loading…");
    stage.replaceChildren(src ? h("img", { class: "preview", src, alt: "" }) : "", prog);
    if (pos > 0) stage.append(h("button", { class: "nav prev", "aria-label": "Previous", html: ICON.left, onclick: () => { pos--; show(); } }));
    if (pos < list.length - 1) stage.append(h("button", { class: "nav next", "aria-label": "Next", html: ICON.right, onclick: () => { pos++; show(); } }));
    if (cur.m.miss && App.src.kind !== "drive") return;
    try {
      const url = await fetchBlob(cur, (g, t) => (prog.textContent = t ? `Loading ${Math.round((g / t) * 100)}%` : `Loading ${fmtSize(g)}`));
      if (list[pos] !== cur) return;
      const el = cur.k === "image" ? h("img", { src: url, alt: "" }) : h("video", { src: url, controls: true, autoplay: true, playsinline: true, loop: cur.k === "gif" });
      stage.replaceChildren(el, ...stage.querySelectorAll(".nav"));
    } catch (e) { prog.textContent = e.message; }
  };
  const onKey = (e) => { if (e.key === "Escape") close(); if (e.key === "ArrowLeft" && pos > 0) { pos--; show(); } if (e.key === "ArrowRight" && pos < list.length - 1) { pos++; show(); } };
  let sx = null;
  stage.addEventListener("touchstart", (e) => (sx = e.touches[0].clientX), { passive: true });
  stage.addEventListener("touchend", (e) => {
    if (sx == null) return; const dx = e.changedTouches[0].clientX - sx; sx = null;
    if (dx > 60 && pos > 0) { pos--; show(); } else if (dx < -60 && pos < list.length - 1) { pos++; show(); }
  });
  const lb = h("div", { class: "lb", role: "dialog", "aria-label": "Media viewer" },
    h("div", { class: "bar" },
      h("button", { class: "icon-btn", "aria-label": "Close", onclick: close }, icon("close")), title,
      h("button", { class: "icon-btn", "aria-label": "Download", onclick: () => download(list[pos]) }, icon("dl"))),
    stage, cap);
  document.body.append(lb);
  addEventListener("keydown", onKey);
  show();
}

// ---------------------------------------------------------------- settings / sign-in
function viewSettings(error) {
  const cid = h("input", { value: settings.get("clientId"), placeholder: "xxxx.apps.googleusercontent.com", autocomplete: "off" });
  const folder = h("input", { value: settings.get("folderName") || "WhatsAppArchive" });
  const canBack = !!App.manifest;
  render(
    h("div", { class: "bar" },
      canBack ? h("button", { class: "icon-btn", "aria-label": "Back", onclick: () => go("#/") }, icon("back")) : null,
      h("h1", {}, canBack ? "Settings" : "Chat Archive")),
    h("div", { class: "page" },
      h("p", {}, "Your WhatsApp archive is read straight from your own Google Drive. Nothing is sent anywhere else."),
      error ? h("div", { class: "err" }, error) : null,
      h("label", {}, "Google OAuth client ID"), cid,
      h("label", {}, "Drive folder name"), folder,
      h("button", { class: "pill", onclick: async () => {
        settings.set("clientId", cid.value.trim()); settings.set("folderName", folder.value.trim() || "WhatsAppArchive");
        App.src = new DriveSource(settings.get("clientId"), settings.get("folderName")); App.manifest = null; App.monthCache.clear();
        try { await App.src.signIn(true); await boot(); } catch (e) { viewSettings(e.message); }
      } }, App.src && App.src.kind === "drive" && App.src.signedIn ? "Save & reload" : "Sign in with Google"),
      h("p", { style: "margin-top:24px;font-size:13px" }, "Setup guide: docs/SETUP.md in the project. Index built: ",
        App.manifest ? new Date(App.manifest.generated_at).toLocaleString() : "—")));
}

function handleError(e) {
  console.error(e);
  if (e instanceof NeedSignIn) return viewSettings("Your Google sign-in expired. Tap “Sign in with Google”.");
  toast(e.message || String(e), 5000);
}

// ---------------------------------------------------------------- router + boot
function go(hash, replace) {
  if (replace) { history.replaceState(null, "", hash); return route(); }
  if (location.hash === hash) return route();
  location.hash = hash; // fires hashchange -> route()
}
async function route() {
  document.querySelectorAll(".modal,.lb").forEach((e) => e.remove());
  const [path, qs] = location.hash.replace(/^#/, "").split("?");
  const params = new URLSearchParams(qs || "");
  if (path === "/settings") return viewSettings();
  if (!App.manifest) return boot();
  const m = path.match(/^\/c\/([0-9a-f]+)(\/media)?$/);
  const chat = m && App.chats.get(m[1]);
  if (chat && m[2]) return viewMedia(chat, params.get("tab") || "media");
  if (chat) return viewChat(chat, params.get("month"), params.get("focus"), params.get("q"));
  viewChatList();
}
async function boot() {
  const demo = new URLSearchParams(location.search).get("demo");
  if (!App.src) {
    if (demo) App.src = new LocalSource(demo);
    else if (settings.get("clientId")) App.src = new DriveSource(settings.get("clientId"), settings.get("folderName") || "WhatsAppArchive");
    else return viewSettings();
  }
  if (App.src.kind === "drive" && !App.src.signedIn) return viewSettings();
  render(h("div", { class: "bar" }, h("h1", {}, "Chat Archive")), h("div", { class: "loading", style: "padding:40px" }, "Loading your archive…"));
  try {
    await App.src.init();
    App.manifest = await App.src.getJSON("manifest.json");
    App.chats = new Map(App.manifest.chats.map((c) => [c.id, c]));
  } catch (e) {
    App.manifest = null;
    if (e instanceof NeedSignIn) return viewSettings("Please sign in again.");
    return demo ? render(h("div", { class: "page" }, h("div", { class: "err" }, e.message))) : viewSettings(e.message);
  }
  route();
}
addEventListener("hashchange", route);
if ("serviceWorker" in navigator && location.protocol === "https:") navigator.serviceWorker.register("sw.js").catch(() => {});
route();
