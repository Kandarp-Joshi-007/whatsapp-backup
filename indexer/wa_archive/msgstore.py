"""Read a decrypted msgstore.db (WhatsApp's 2022+ schema: tables chat / message / jid /
message_media / message_thumbnail) into plain Python objects.

Every column is looked up defensively, because WhatsApp adds and renames columns often.
"""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

# message.message_type -> our kind
KIND_BY_TYPE = {
    0: "text", 1: "image", 2: "audio", 3: "video", 4: "contact", 5: "location",
    7: "system", 9: "document", 13: "gif", 15: "deleted", 16: "location",
    20: "sticker", 42: "image", 43: "video", 64: "deleted", 66: "poll", 82: "audio",
}
MEDIA_KINDS = {"image", "video", "audio", "document", "gif", "sticker"}


@dataclass
class Media:
    path: str | None            # relative to the WhatsApp folder, e.g. "Media/WhatsApp Images/IMG-...jpg"
    mime: str | None = None
    size: int | None = None
    name: str | None = None     # original document name
    duration: int | None = None
    width: int | None = None
    height: int | None = None
    db_thumb: bytes | None = None


@dataclass
class Message:
    id: int
    ts: int                     # ms since epoch, UTC
    from_me: bool
    kind: str
    text: str | None = None
    sender: str | None = None   # phone digits / jid user for group senders
    media: Media | None = None


@dataclass
class Chat:
    row_id: int
    jid: str
    user: str
    is_group: bool
    subject: str | None
    messages: list[Message] = field(default_factory=list)


class SchemaError(Exception):
    pass


def _cols(con: sqlite3.Connection, table: str) -> set[str]:
    return {r[1] for r in con.execute(f"PRAGMA table_info({table})")}


def _pick(cols: set[str], *names: str) -> str:
    for n in names:
        if n in cols:
            return n
    return "NULL"


def normalize_media_path(p: str | None) -> str | None:
    if not p:
        return None
    p = p.replace("\\", "/")
    i = p.find("Media/")
    return p[i:] if i >= 0 else p.lstrip("/")


def read_msgstore(db_path: str) -> list[Chat]:
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "message" not in tables or "chat" not in tables:
        if "messages" in tables:
            raise SchemaError("This backup uses WhatsApp's pre-2022 database layout, which is not supported. "
                              "Update WhatsApp and make a new backup.")
        raise SchemaError("This does not look like a WhatsApp msgstore.db")

    jid_cols = _cols(con, "jid")
    jids: dict[int, tuple[str, str, str]] = {}
    for rid, raw, user, server in con.execute(
        f"SELECT _id, {_pick(jid_cols, 'raw_string')}, {_pick(jid_cols, 'user')}, "
        f"{_pick(jid_cols, 'server')} FROM jid"
    ):
        raw = raw or (f"{user}@{server}" if user else "")
        jids[rid] = (raw, user or raw.split("@")[0], server or (raw.split("@")[1] if "@" in raw else ""))

    # LID (privacy id) -> phone jid
    if "jid_map" in tables:
        for lid, real in con.execute("SELECT lid_row_id, jid_row_id FROM jid_map"):
            if real in jids and lid in jids:
                jids[lid] = jids[real]

    chat_cols = _cols(con, "chat")
    chats: dict[int, Chat] = {}
    for rid, jrid, subject, hidden in con.execute(
        f"SELECT _id, jid_row_id, {_pick(chat_cols, 'subject')}, {_pick(chat_cols, 'hidden')} FROM chat"
    ):
        if jrid not in jids:
            continue
        raw, user, server = jids[jrid]
        if server in ("broadcast", "status") or raw.startswith("status@"):
            continue
        chats[rid] = Chat(rid, raw, user, server == "g.us", subject)

    media: dict[int, Media] = {}
    if "message_media" in tables:
        mc = _cols(con, "message_media")
        q = (f"SELECT message_row_id, {_pick(mc, 'file_path')}, {_pick(mc, 'mime_type')}, "
             f"{_pick(mc, 'file_size', 'file_length')}, {_pick(mc, 'media_name')}, "
             f"{_pick(mc, 'media_duration')}, {_pick(mc, 'width')}, {_pick(mc, 'height')} FROM message_media")
        for mid, path, mime, size, name, dur, w, h in con.execute(q):
            media[mid] = Media(normalize_media_path(path), mime, size, name, dur, w, h)

    if "message_thumbnail" in tables:
        for mid, blob in con.execute("SELECT message_row_id, thumbnail FROM message_thumbnail"):
            if mid in media and blob:
                media[mid].db_thumb = bytes(blob)
            elif blob:
                media[mid] = Media(None, db_thumb=bytes(blob))

    mcols = _cols(con, "message")
    q = (f"SELECT _id, chat_row_id, {_pick(mcols, 'from_me')}, {_pick(mcols, 'timestamp')}, "
         f"{_pick(mcols, 'message_type')}, {_pick(mcols, 'text_data')}, "
         f"{_pick(mcols, 'sender_jid_row_id')} FROM message ORDER BY chat_row_id, timestamp, _id")
    for mid, crid, from_me, ts, mtype, text, sender_rid in con.execute(q):
        chat = chats.get(crid)
        if chat is None or not ts or ts <= 0:
            continue
        kind = KIND_BY_TYPE.get(mtype or 0, "other")
        if kind == "system":
            continue
        m = media.get(mid)
        if kind not in MEDIA_KINDS:
            m = None
        elif m is None:
            m = Media(None)
        sender = None
        if chat.is_group and not from_me and sender_rid in jids:
            sender = jids[sender_rid][1]
        if kind == "text" and not text:
            continue
        chat.messages.append(Message(mid, int(ts), bool(from_me), kind, text, sender, m))

    con.close()
    return [c for c in chats.values() if c.messages]
