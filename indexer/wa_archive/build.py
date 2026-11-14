"""Turn parsed chats into the archive index the viewer reads.

Output folder (flat, so the viewer can list it with one Drive API call):
    manifest.json                      chat list + per-chat month list
    chat-<chatid>-<YYYY-MM>.json       one month of one chat: messages + media + thumbnails
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import re
import shutil
import sqlite3
import subprocess
import tempfile
import time
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from .msgstore import Chat, Media, Message

THUMB_PX = 240
FORMAT_VERSION = 1
URL_RE = re.compile(r"https?://[^\s<>\"')]+", re.I)

try:
    from PIL import Image, ImageOps
except ImportError:  # thumbnails from files are skipped without Pillow
    Image = None


def chat_id(jid: str) -> str:
    return hashlib.sha1(jid.encode()).hexdigest()[:12]


class ThumbCache:
    """SQLite cache so re-running the indexer doesn't re-read 12 GB of media."""

    def __init__(self, path: Path | None):
        self.con = sqlite3.connect(str(path) if path else ":memory:")
        self.con.execute("CREATE TABLE IF NOT EXISTS t (k TEXT PRIMARY KEY, v BLOB)")

    def get(self, k: str):
        r = self.con.execute("SELECT v FROM t WHERE k=?", (k,)).fetchone()
        return r[0] if r else None

    def put(self, k: str, v: bytes):
        self.con.execute("INSERT OR REPLACE INTO t VALUES (?,?)", (k, v))

    def close(self):
        self.con.commit()
        self.con.close()


def _jpeg(img) -> bytes:
    img = ImageOps.exif_transpose(img)
    if img.mode not in ("RGB", "L"):
        bg = Image.new("RGB", img.size, (255, 255, 255))
        bg.paste(img, mask=img.convert("RGBA").split()[-1])
        img = bg
    img.thumbnail((THUMB_PX, THUMB_PX))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=62, optimize=True)
    return buf.getvalue()


def _thumb_from_image_file(p: Path) -> bytes | None:
    if Image is None:
        return None
    try:
        with Image.open(p) as im:
            im.draft("RGB", (THUMB_PX * 2, THUMB_PX * 2))
            return _jpeg(im)
    except Exception:
        return None


def _thumb_from_video_file(p: Path) -> bytes | None:
    if not shutil.which("ffmpeg"):
        return None
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / "t.jpg"
        for ss in ("1", "0"):
            try:
                subprocess.run(
                    ["ffmpeg", "-loglevel", "error", "-ss", ss, "-i", str(p), "-frames:v", "1",
                     "-vf", f"scale={THUMB_PX}:-2", "-y", str(out)],
                    timeout=60, check=True, capture_output=True)
                if out.exists() and out.stat().st_size:
                    return _thumb_from_image_file(out) or out.read_bytes()
            except Exception:
                continue
    return None


def _normalize_db_thumb(blob: bytes) -> bytes | None:
    if Image is None:
        return blob
    try:
        with Image.open(io.BytesIO(blob)) as im:
            return _jpeg(im)
    except Exception:
        return None


def make_thumb(m: Media, kind: str, media_root: Path | None, cache: ThumbCache) -> tuple[bytes | None, bool]:
    """Returns (jpeg bytes or None, file_exists)."""
    fp = (media_root / m.path) if (media_root and m.path) else None
    exists = bool(fp and fp.is_file())
    if kind in ("image", "sticker", "gif", "video") and exists:
        st = fp.stat()
        key = f"{m.path}|{st.st_size}"
        hit = cache.get(key)
        if hit is not None:
            return (hit or None), True
        is_video = (m.mime or "").startswith("video/") or fp.suffix.lower() in (".mp4", ".3gp", ".mov", ".mkv", ".webm")
        t = _thumb_from_video_file(fp) if is_video else _thumb_from_image_file(fp)
        cache.put(key, t or b"")
        if t:
            return t, True
    if m.db_thumb:
        return _normalize_db_thumb(m.db_thumb), exists
    return None, exists


def _month(ts_ms: int, tz: ZoneInfo) -> str:
    return datetime.fromtimestamp(ts_ms / 1000, tz).strftime("%Y-%m")


def _preview(msg: Message) -> str:
    labels = {"image": "📷 Photo", "video": "🎥 Video", "audio": "🎤 Audio", "document": "📄 Document",
              "sticker": "Sticker", "gif": "GIF", "contact": "👤 Contact", "location": "📍 Location",
              "deleted": "🚫 This message was deleted", "poll": "📊 Poll"}
    if msg.kind == "document" and msg.media and msg.media.name:
        return "📄 " + msg.media.name
    base = labels.get(msg.kind)
    if msg.text:
        return (base + " " if base and msg.kind != "text" else "") + msg.text[:80]
    return base or ""


def build_index(chats: list[Chat], out_dir: str | Path, *, names: dict[str, str] | None = None,
                media_root: str | Path | None = None, drive_ids: dict[str, dict] | None = None,
                tz: str = "Asia/Kolkata", thumb_cache: str | Path | None = None,
                log=print) -> dict:
    names = names or {}
    drive_ids = drive_ids or {}
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    zone = ZoneInfo(tz)
    mroot = Path(media_root) if media_root else None
    cache = ThumbCache(Path(thumb_cache) if thumb_cache else None)

    written: set[str] = {"manifest.json"}
    manifest_chats = []
    t0 = time.time()
    total_media = 0

    for ci, chat in enumerate(sorted(chats, key=lambda c: -c.messages[-1].ts)):
        cid = chat_id(chat.jid)
        name = chat.subject if chat.is_group else names.get(chat.user)
        display = name or ("+" + chat.user if chat.user.isdigit() else chat.user)
        months: dict[str, list] = defaultdict(list)
        counts = defaultdict(int)

        for msg in chat.messages:
            rec: dict = {"i": msg.id, "t": msg.ts, "k": msg.kind}
            if msg.from_me:
                rec["f"] = 1
            if msg.text:
                rec["x"] = msg.text
                if URL_RE.search(msg.text):
                    rec["l"] = 1
                    counts["links"] += 1
            if msg.sender:
                rec["s"] = names.get(msg.sender) or ("+" + msg.sender if msg.sender.isdigit() else msg.sender)
            if msg.media:
                m = msg.media
                thumb, exists = make_thumb(m, msg.kind, mroot, cache)
                md: dict = {}
                if m.path:
                    md["p"] = m.path
                    did = drive_ids.get(m.path)
                    if did:
                        md["d"] = did["id"]
                for k, v in (("mt", m.mime), ("z", m.size), ("n", m.name), ("du", m.duration),
                             ("w", m.width), ("h", m.height)):
                    if v:
                        md[k] = v
                if thumb:
                    md["th"] = base64.b64encode(thumb).decode()
                have_file = bool(md.get("d")) or exists
                if not have_file and (mroot is not None or drive_ids):
                    md["miss"] = 1
                rec["m"] = md
                counts[msg.kind] += 1
                total_media += 1
            months[_month(msg.ts, zone)].append(rec)

        month_list = sorted(months)
        for mon, recs in months.items():
            fn = f"chat-{cid}-{mon}.json"
            (out / fn).write_text(json.dumps({"v": FORMAT_VERSION, "chat": cid, "month": mon, "messages": recs},
                                             ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            written.add(fn)

        media_by_month = {mon: sum(1 for r in recs if "m" in r) for mon, recs in months.items()}
        last = chat.messages[-1]
        manifest_chats.append({
            "id": cid, "name": display, "jid": chat.jid, "g": 1 if chat.is_group else 0,
            "phone": None if chat.is_group else chat.user,
            "last_t": last.ts, "last_x": _preview(last), "last_f": 1 if last.from_me else 0,
            "n": len(chat.messages), "counts": dict(counts), "months": month_list,
            "media_months": {k: v for k, v in media_by_month.items() if v},
        })
        if (ci + 1) % 25 == 0:
            cache.con.commit()
            log(f"  {ci + 1}/{len(chats)} chats indexed ({total_media} media, {time.time() - t0:.0f}s)")

    manifest = {"v": FORMAT_VERSION, "generated_at": int(time.time() * 1000), "tz": tz,
                "chats": manifest_chats}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
                                       encoding="utf-8")
    # remove shards from older runs that no longer exist (e.g. chat deleted)
    for f in out.glob("chat-*.json"):
        if f.name not in written:
            f.unlink()
    cache.close()
    log(f"Done: {len(manifest_chats)} chats, {total_media} media items, {len(written)} files in {out}")
    return manifest
