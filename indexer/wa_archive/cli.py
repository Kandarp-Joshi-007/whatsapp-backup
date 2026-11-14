"""Command line:  python -m wa_archive <command> ...

  decrypt  BACKUP.crypt15 OUT.db --key-file key.txt
  build    --db msgstore.db --out index/ [--media-root WhatsApp/] [--contacts contacts.vcf ...]
  summary  --db msgstore.db [--contacts ...]      (Phase-0 check: per-chat media counts)
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import tempfile
from collections import Counter
from datetime import datetime
from pathlib import Path

from .build import build_index
from .contacts import load_contacts
from .crypt15 import DecryptError, decrypt_file
from .msgstore import SchemaError, read_msgstore


def _key(args) -> str:
    if getattr(args, "key_file", None):
        return Path(args.key_file).read_text()
    if os.environ.get("WA_KEY"):
        return os.environ["WA_KEY"]
    return getpass.getpass("64-digit key: ")


def _db(args) -> str:
    if args.db:
        return args.db
    if not args.backup:
        sys.exit("Give --db (decrypted msgstore.db) or --backup (msgstore.db.crypt15)")
    tmp = Path(tempfile.mkdtemp()) / "msgstore.db"
    decrypt_file(args.backup, _key(args), tmp)
    return str(tmp)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="wa_archive")
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("decrypt")
    d.add_argument("backup")
    d.add_argument("out")
    d.add_argument("--key-file")

    for name in ("build", "summary"):
        p = sub.add_parser(name)
        p.add_argument("--db")
        p.add_argument("--backup")
        p.add_argument("--key-file")
        p.add_argument("--contacts", nargs="*", default=[])
        p.add_argument("--country-code", default="91")
        if name == "build":
            p.add_argument("--out", required=True)
            p.add_argument("--media-root", help="folder that contains Media/ (the WhatsApp folder)")
            p.add_argument("--drive-ids", help="JSON {relative_path: {id, size}} from the Colab notebook")
            p.add_argument("--tz", default="Asia/Kolkata")
            p.add_argument("--thumb-cache")

    a = ap.parse_args(argv)
    try:
        if a.cmd == "decrypt":
            decrypt_file(a.backup, _key(a), a.out)
            print(f"Decrypted -> {a.out}")
            return
        chats = read_msgstore(_db(a))
        names = load_contacts(a.contacts, a.country_code) if a.contacts else {}
        if a.cmd == "summary":
            for c in sorted(chats, key=lambda c: -len(c.messages)):
                kinds = Counter(m.kind for m in c.messages)
                label = c.subject if c.is_group else names.get(c.user, "+" + c.user)
                first = datetime.fromtimestamp(c.messages[0].ts / 1000).date()
                media = {k: v for k, v in kinds.items() if k != "text"}
                print(f"{label:30.30} {len(c.messages):6} msgs since {first}  {dict(media)}")
            print(f"\n{len(chats)} chats")
            return
        ids = json.loads(Path(a.drive_ids).read_text()) if a.drive_ids else None
        build_index(chats, a.out, names=names, media_root=a.media_root, drive_ids=ids, tz=a.tz,
                    thumb_cache=a.thumb_cache)
    except (DecryptError, SchemaError) as e:
        sys.exit(f"Error: {e}")


if __name__ == "__main__":
    main()
