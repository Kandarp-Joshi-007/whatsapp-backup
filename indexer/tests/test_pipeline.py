import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from make_fixture import TEST_KEY, build  # noqa: E402
from wa_archive.cli import main  # noqa: E402
from wa_archive.contacts import normalize_phone  # noqa: E402
from wa_archive.crypt15 import DecryptError, decrypt_file, parse_key  # noqa: E402
from wa_archive.msgstore import read_msgstore  # noqa: E402


@pytest.fixture(scope="module")
def fx(tmp_path_factory):
    return build(tmp_path_factory.mktemp("fx"))


def test_key_parsing_accepts_spaces():
    spaced = " ".join(TEST_KEY[i:i + 4] for i in range(0, 64, 4))
    assert parse_key(spaced) == bytes.fromhex(TEST_KEY)
    with pytest.raises(DecryptError):
        parse_key("1234")


def test_decrypt_roundtrip(fx, tmp_path):
    out = decrypt_file(fx["wa"] / "Databases/msgstore.db.crypt15", TEST_KEY, tmp_path / "m.db")
    assert out.read_bytes() == fx["db"].read_bytes()


def test_wrong_key_fails_cleanly(fx, tmp_path):
    with pytest.raises(DecryptError):
        decrypt_file(fx["wa"] / "Databases/msgstore.db.crypt15", "f" * 64, tmp_path / "m.db")


def test_phone_normalization():
    assert normalize_phone("+91 98765 43210") == "919876543210"
    assert normalize_phone("098765 43210") == "919876543210"
    assert normalize_phone("9876543210") == "919876543210"
    assert normalize_phone("+1 (415) 555-0123") == "14155550123"


def test_read_msgstore(fx):
    chats = {c.user: c for c in read_msgstore(str(fx["db"]))}
    assert "status" not in chats
    papa = chats["919876543210"]
    imgs = [m for m in papa.messages if m.kind == "image"]
    assert len(imgs) == 8
    assert imgs[1].media.path.startswith("Media/WhatsApp Images/Sent/IMG-20250121-")
    fam = chats["120363025555555555"]
    assert fam.is_group and fam.subject == "Family ❤️"
    # LID sender resolved to Mom's phone number
    assert any(m.sender == "919812345678" for m in fam.messages)


def test_build_end_to_end(fx, tmp_path, monkeypatch):
    monkeypatch.setenv("WA_KEY", TEST_KEY)
    out = tmp_path / "index"
    main(["build", "--backup", str(fx["wa"] / "Databases/msgstore.db.crypt15"), "--out", str(out),
          "--media-root", str(fx["wa"]), "--contacts", str(fx["wa"].parent / "contacts.vcf")])
    man = json.loads((out / "manifest.json").read_text())
    names = [c["name"] for c in man["chats"]]
    assert {"Papa", "Mom", "Rahul College", "Family ❤️", "+14155550123"} <= set(names)
    papa = next(c for c in man["chats"] if c["name"] == "Papa")
    assert "2025-01" in papa["months"] and papa["counts"]["image"] == 8
    jan = json.loads((out / f"chat-{papa['id']}-2025-01.json").read_text())
    photos = [m for m in jan["messages"] if m["k"] == "image"]
    assert len(photos) == 6 and all("th" in m["m"] for m in photos)
    vid = next(m for m in jan["messages"] if m["k"] == "video")
    assert "th" in vid["m"]  # thumbnail generated from the mp4 with ffmpeg
    feb = json.loads((out / f"chat-{papa['id']}-2025-02.json").read_text())
    gone = next(m for m in feb["messages"] if m["k"] == "image")
    assert gone["m"].get("miss") == 1 and "th" in gone["m"]  # deleted file still shows DB thumbnail
    fam = next(c for c in man["chats"] if c["g"])
    famj = json.loads((out / f"chat-{fam['id']}-2025-01.json").read_text())
    assert {"Papa", "Mom", "Rahul College"} <= {m.get("s") for m in famj["messages"]}
