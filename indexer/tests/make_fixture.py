"""Builds a fake but realistic WhatsApp backup for tests and the viewer demo:
   <out>/WhatsApp/Databases/msgstore.db.crypt15   (encrypted with TEST_KEY)
   <out>/WhatsApp/Media/...                       (images, video, pdf, voice, sticker)
   <out>/contacts.vcf
Run:  python tests/make_fixture.py /tmp/fixture
"""
from __future__ import annotations

import io
import random
import sqlite3
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from PIL import Image, ImageDraw  # noqa: E402

from wa_archive.crypt15 import encrypt_bytes_for_tests, parse_key  # noqa: E402

TEST_KEY = "0123456789abcdef" * 4
IST = ZoneInfo("Asia/Kolkata")

SCHEMA = """
CREATE TABLE jid (_id INTEGER PRIMARY KEY, user TEXT, server TEXT, agent INTEGER, device INTEGER,
                  type INTEGER, raw_string TEXT);
CREATE TABLE jid_map (lid_row_id INTEGER PRIMARY KEY, jid_row_id INTEGER);
CREATE TABLE chat (_id INTEGER PRIMARY KEY, jid_row_id INTEGER UNIQUE, hidden INTEGER, subject TEXT,
                   created_timestamp INTEGER, sort_timestamp INTEGER);
CREATE TABLE message (_id INTEGER PRIMARY KEY, chat_row_id INTEGER, from_me INTEGER, key_id TEXT,
                      sender_jid_row_id INTEGER, status INTEGER, timestamp INTEGER, received_timestamp INTEGER,
                      message_type INTEGER, text_data TEXT, starred INTEGER);
CREATE TABLE message_media (message_row_id INTEGER PRIMARY KEY, chat_row_id INTEGER, file_path TEXT,
                            file_size INTEGER, mime_type TEXT, media_name TEXT, media_duration INTEGER,
                            width INTEGER, height INTEGER, file_hash TEXT);
CREATE TABLE message_thumbnail (message_row_id INTEGER PRIMARY KEY, thumbnail BLOB);
"""


def _img(path: Path, text: str, color, size=(1280, 960)) -> bytes:
    im = Image.new("RGB", size, color)
    d = ImageDraw.Draw(im)
    for i in range(0, size[0], 80):
        d.line([(i, 0), (i + 300, size[1])], fill=tuple(min(255, c + 40) for c in color), width=18)
    d.rectangle([40, size[1] - 200, size[0] - 40, size[1] - 40], fill=(0, 0, 0))
    d.text((70, size[1] - 160), text, fill=(255, 255, 255), font_size=64)
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, quality=85)
    small = io.BytesIO()
    im.resize((100, 75)).save(small, "JPEG", quality=50)
    return small.getvalue()


def build(out: Path) -> dict:
    rnd = random.Random(7)
    wa = out / "WhatsApp"
    media = wa / "Media"
    (wa / "Databases").mkdir(parents=True, exist_ok=True)
    db_path = Path(tempfile.mkdtemp()) / "msgstore.db"
    con = sqlite3.connect(db_path)
    con.executescript(SCHEMA)

    jids = {
        "papa": ("919876543210", "s.whatsapp.net"),
        "mom": ("919812345678", "s.whatsapp.net"),
        "rahul": ("919900011122", "s.whatsapp.net"),
        "family": ("120363025555555555", "g.us"),
        "unknown": ("14155550123", "s.whatsapp.net"),
        "status": ("status", "broadcast"),
        "lid_mom": ("8888777766665555", "lid"),
    }
    jrow = {}
    for i, (k, (u, s)) in enumerate(jids.items(), 1):
        con.execute("INSERT INTO jid (_id,user,server,raw_string) VALUES (?,?,?,?)", (i, u, s, f"{u}@{s}"))
        jrow[k] = i
    con.execute("INSERT INTO jid_map VALUES (?,?)", (jrow["lid_mom"], jrow["mom"]))
    crow = {}
    for i, k in enumerate(["papa", "mom", "rahul", "family", "unknown", "status"], 1):
        con.execute("INSERT INTO chat (_id,jid_row_id,hidden,subject) VALUES (?,?,0,?)",
                    (i, jrow[k], "Family ❤️" if k == "family" else None))
        crow[k] = i

    mid = [0]
    seq = {}

    def ts(dt: datetime) -> int:
        return int(dt.replace(tzinfo=IST).timestamp() * 1000)

    def msg(chat, dt, from_me, mtype, text=None, sender=None):
        mid[0] += 1
        con.execute("INSERT INTO message (_id,chat_row_id,from_me,key_id,sender_jid_row_id,status,timestamp,"
                    "message_type,text_data) VALUES (?,?,?,?,?,?,?,?,?)",
                    (mid[0], crow[chat], from_me, f"K{mid[0]}", jrow.get(sender, 0), 13, ts(dt), mtype, text))
        return mid[0]

    def fname(prefix, dt, ext):
        day = dt.strftime("%Y%m%d")
        seq[(prefix, day)] = seq.get((prefix, day), 0) + 1
        return f"{prefix}-{day}-WA{seq[(prefix, day)]:04d}.{ext}"

    def image(chat, dt, from_me, label, color, caption=None, sender=None, present=True):
        m = msg(chat, dt, from_me, 1, caption, sender)
        sub = "WhatsApp Images/Sent" if from_me else "WhatsApp Images"
        rel = f"Media/{sub}/{fname('IMG', dt, 'jpg')}"
        p = wa / rel
        thumb = _img(p, label, color)
        size = p.stat().st_size
        if not present:
            p.unlink()
        con.execute("INSERT INTO message_media (message_row_id,chat_row_id,file_path,file_size,mime_type,width,height)"
                    " VALUES (?,?,?,?,?,?,?)", (m, crow[chat], rel, size, "image/jpeg", 1280, 960))
        con.execute("INSERT INTO message_thumbnail VALUES (?,?)", (m, thumb))

    def video(chat, dt, from_me, caption=None):
        m = msg(chat, dt, from_me, 3, caption)
        sub = "WhatsApp Video/Sent" if from_me else "WhatsApp Video"
        rel = f"Media/{sub}/{fname('VID', dt, 'mp4')}"
        p = wa / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=15",
                        "-t", "4", "-pix_fmt", "yuv420p", "-y", str(p)], check=True)
        con.execute("INSERT INTO message_media (message_row_id,chat_row_id,file_path,file_size,mime_type,"
                    "media_duration,width,height) VALUES (?,?,?,?,?,?,?,?)",
                    (m, crow[chat], rel, p.stat().st_size, "video/mp4", 4, 640, 360))

    def document(chat, dt, from_me, name, body: bytes, mime):
        m = msg(chat, dt, from_me, 9, None)
        sub = "WhatsApp Documents/Sent" if from_me else "WhatsApp Documents"
        rel = f"Media/{sub}/{name}"
        p = wa / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(body)
        con.execute("INSERT INTO message_media (message_row_id,chat_row_id,file_path,file_size,mime_type,media_name)"
                    " VALUES (?,?,?,?,?,?)", (m, crow[chat], rel, len(body), mime, name))

    def voice(chat, dt, from_me):
        m = msg(chat, dt, from_me, 2)
        rel = f"Media/WhatsApp Voice Notes/{dt:%Y%V}/{fname('PTT', dt, 'opus')}"
        p = wa / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                        "-c:a", "libopus", "-y", str(p)], check=True)
        con.execute("INSERT INTO message_media (message_row_id,chat_row_id,file_path,file_size,mime_type,"
                    "media_duration) VALUES (?,?,?,?,?,?)", (m, crow[chat], rel, p.stat().st_size, "audio/ogg", 3))

    def sticker(chat, dt, from_me):
        m = msg(chat, dt, from_me, 20)
        rel = f"Media/WhatsApp Stickers/STK-{dt:%Y%m%d}-WA{mid[0]:04d}.webp"
        p = wa / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        im = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
        ImageDraw.Draw(im).ellipse([40, 40, 472, 472], fill=(255, 200, 0, 255))
        im.save(p, "WEBP")
        con.execute("INSERT INTO message_media (message_row_id,chat_row_id,file_path,file_size,mime_type)"
                    " VALUES (?,?,?,?,?)", (m, crow[chat], rel, p.stat().st_size, "image/webp"))

    pdf = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"

    # --- Papa: older chatter, the Jan 2025 trip, later docs
    d = datetime(2024, 11, 3, 9, 15)
    msg("papa", d, 0, 0, "Beta, did you reach Pune safely?")
    msg("papa", d + timedelta(minutes=4), 1, 0, "Yes papa, reached. Will call in the evening 🙂")
    image("papa", datetime(2024, 12, 24, 19, 2), 0, "Christmas at home", (140, 30, 40), "Tree is ready 🎄")
    trip = datetime(2025, 1, 21, 10, 30)
    msg("papa", trip, 1, 0, "Sharing the Manali trip photos!")
    colors = [(30, 90, 160), (40, 130, 90), (160, 110, 30), (90, 50, 140), (20, 120, 140), (150, 60, 60)]
    for i, c in enumerate(colors):
        image("papa", trip + timedelta(minutes=1 + i), 1, f"Manali trip #{i + 1}", c,
              "Solang valley ❄️" if i == 0 else None)
    video("papa", trip + timedelta(minutes=9), 1, "Snowfall video")
    msg("papa", trip + timedelta(minutes=30), 0, 0, "Beautiful pictures! Take care in the cold.")
    image("papa", datetime(2025, 2, 2, 8, 0), 0, "Old photo (deleted from phone)", (100, 100, 100),
          "Found this old one", present=False)
    document("papa", datetime(2025, 3, 15, 11, 0), 0, "Insurance_Policy_2025.pdf", pdf, "application/pdf")
    document("papa", datetime(2025, 3, 15, 11, 2), 1, "Aadhaar_scan.pdf", pdf, "application/pdf")
    msg("papa", datetime(2025, 3, 15, 11, 5), 1, 0, "Policy details: https://example.com/policy/123")
    voice("papa", datetime(2025, 4, 1, 21, 0), 0)
    for i in range(40):  # some volume
        dt = datetime(2025, 5, 1, 8) + timedelta(hours=7 * i)
        msg("papa", dt, i % 2, 0, rnd.choice(["Ok", "Good morning 🙏", "Call me when free", "Done 👍",
                                               "Did you eat?", "Haan, coming"]))
    msg("papa", datetime(2025, 6, 2, 9, 0), 0, 15)

    # --- Mom
    msg("mom", datetime(2025, 2, 10, 13, 0), 0, 0, "Send me the recipe photo")
    image("mom", datetime(2025, 2, 10, 13, 5), 1, "Recipe", (200, 120, 40))
    sticker("mom", datetime(2025, 2, 10, 13, 6), 0)

    # --- Rahul
    msg("rahul", datetime(2025, 5, 20, 22, 0), 0, 0, "Bro check this https://github.com/ElDavoo/wa-crypt-tools")
    video("rahul", datetime(2025, 5, 20, 22, 3), 0)

    # --- Family group (sender via LID mapping + direct)
    g = datetime(2025, 1, 26, 9, 0)
    msg("family", g, 0, 0, "Happy Republic Day everyone 🇮🇳", sender="papa")
    image("family", g + timedelta(minutes=2), 0, "Parade on TV", (60, 60, 160), sender="lid_mom")
    msg("family", g + timedelta(minutes=5), 1, 0, "Happy Republic Day! 🇮🇳")
    msg("family", g + timedelta(minutes=6), 0, 0, "Who is coming for lunch?", sender="rahul")

    # --- unknown number (not in contacts)
    msg("unknown", datetime(2025, 3, 3, 10, 0), 0, 0, "Your parcel has been delivered.")
    # --- status broadcast must be ignored
    msg("status", datetime(2025, 3, 3, 10, 0), 0, 0, "status update")

    con.commit()
    con.close()
    enc = encrypt_bytes_for_tests(db_path.read_bytes(), parse_key(TEST_KEY), bytes(range(16)))
    (wa / "Databases" / "msgstore.db.crypt15").write_bytes(enc)
    (out / "contacts.vcf").write_text(
        "BEGIN:VCARD\nVERSION:3.0\nFN:Papa\nTEL;TYPE=CELL:+91 98765 43210\nEND:VCARD\n"
        "BEGIN:VCARD\nVERSION:3.0\nFN:Mom\nTEL:098123 45678\nEND:VCARD\n"
        "BEGIN:VCARD\nVERSION:3.0\nFN:Rahul College\nTEL:9900011122\nEND:VCARD\n")
    return {"wa": wa, "db": db_path, "key": TEST_KEY}


if __name__ == "__main__":
    info = build(Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/fixture"))
    print(info)
