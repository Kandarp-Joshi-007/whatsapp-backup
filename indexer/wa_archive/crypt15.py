"""Decrypt WhatsApp end-to-end encrypted backups (.crypt15) with the user's 64-digit key.

File layout (as documented by the open-source wa-crypt-tools project):

    [1 byte  ] length N of the protobuf header
    [1 byte  ] optional 0x01 "feature table" flag (msgstore backups)
    [N bytes ] protobuf header (BackupPrefix) - contains a 16-byte IV
    [....... ] AES-256-GCM ciphertext of a zlib-compressed SQLite file
    [16 bytes] GCM auth tag
    [16 bytes] MD5 checksum (msgstore only; absent in some backups)

Encryption key = HKDF-SHA256(salt=32 zero bytes, ikm=root_key, info="backup encryption").

We deliberately do not depend on the exact protobuf field numbers: every 16-byte
bytes field in the header is tried as the IV, and the result is verified (GCM tag,
then zlib + SQLite magic bytes). This keeps working if WhatsApp reshuffles the header.
"""
from __future__ import annotations

import hashlib
import hmac
import re
import zlib
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SQLITE_MAGIC = b"SQLite format 3\x00"


class DecryptError(Exception):
    pass


def parse_key(key_text: str) -> bytes:
    """Accept the 64-digit key as WhatsApp shows it (spaces/dashes/newlines allowed)."""
    cleaned = re.sub(r"[^0-9a-fA-F]", "", key_text)
    if len(cleaned) != 64:
        raise DecryptError(
            f"The key must have 64 hex digits (0-9, a-f); got {len(cleaned)}. "
            "Copy it again from where you saved it."
        )
    return bytes.fromhex(cleaned)


def derive_backup_key(root_key: bytes) -> bytes:
    prk = hmac.new(b"\x00" * 32, root_key, hashlib.sha256).digest()
    return hmac.new(prk, b"backup encryption\x01", hashlib.sha256).digest()


# --- minimal protobuf walker -------------------------------------------------

def _read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("truncated varint")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def _bytes_fields(buf: bytes, depth: int = 0) -> list[bytes]:
    """Return all length-delimited fields, recursing into ones that parse as messages."""
    out: list[bytes] = []
    pos = 0
    try:
        while pos < len(buf):
            tag, pos = _read_varint(buf, pos)
            wire = tag & 7
            if tag >> 3 == 0:
                raise ValueError("field 0")
            if wire == 0:
                _, pos = _read_varint(buf, pos)
            elif wire == 1:
                pos += 8
            elif wire == 5:
                pos += 4
            elif wire == 2:
                ln, pos = _read_varint(buf, pos)
                val = buf[pos:pos + ln]
                if len(val) != ln:
                    raise ValueError("truncated field")
                pos += ln
                out.append(val)
                if depth < 4:
                    out.extend(_bytes_fields(val, depth + 1))
            else:
                raise ValueError("bad wire type")
    except ValueError:
        pass  # not a message (or garbage) - keep what we found
    return out


def _split_header(data: bytes) -> tuple[bytes, bytes]:
    size = data[0]
    off = 1
    if data[1] == 0x01:  # feature-table flag; 0x01 is never a valid protobuf tag
        off = 2
    header = data[off:off + size]
    return header, data[off + size:]


def _inflate_and_check(plain: bytes) -> bytes | None:
    try:
        out = zlib.decompressobj().decompress(plain)
    except zlib.error:
        out = plain  # some backups are not compressed
    return out if out.startswith(SQLITE_MAGIC) else None


def decrypt_bytes(data: bytes, root_key: bytes) -> bytes:
    if len(data) < 64:
        raise DecryptError("File is too small to be a WhatsApp backup.")
    key = derive_backup_key(root_key)
    header, body = _split_header(data)
    ivs = [f for f in _bytes_fields(header) if len(f) == 16]
    if not ivs:
        raise DecryptError("No IV found in the backup header. Is this really a .crypt15 file?")

    for iv in ivs:
        # 1) authenticated decryption: with and without the trailing 16-byte MD5
        for end in (len(body) - 16, len(body)):
            try:
                plain = AESGCM(key).decrypt(iv, body[:end], None)
            except Exception:
                continue
            db = _inflate_and_check(plain)
            if db:
                return db
        # 2) unauthenticated fallback (tolerates unknown trailers)
        dec = Cipher(algorithms.AES(key), modes.GCM(iv)).decryptor()
        db = _inflate_and_check(dec.update(body[:-32] if len(body) > 32 else body))
        if db:
            return db

    raise DecryptError(
        "Could not decrypt. Most likely the 64-digit key is wrong, or this backup "
        "was made before you switched to the 64-digit key (make a new backup in "
        "WhatsApp > Settings > Chats > Chat backup > Back up)."
    )


def decrypt_file(src: str | Path, key_text: str, dst: str | Path) -> Path:
    db = decrypt_bytes(Path(src).read_bytes(), parse_key(key_text))
    dst = Path(dst)
    dst.write_bytes(db)
    return dst


# --- used only by tests to build fixtures -------------------------------------

def encrypt_bytes_for_tests(sqlite_bytes: bytes, root_key: bytes, iv: bytes) -> bytes:
    key = derive_backup_key(root_key)
    ct = AESGCM(key).encrypt(iv, zlib.compress(sqlite_bytes), None)
    # header: field 1 varint (key type), field 3 { field 1: iv }
    inner = b"\x0a\x10" + iv
    header = b"\x08\x01" + b"\x1a" + bytes([len(inner)]) + inner
    head = bytes([len(header)]) + b"\x01" + header
    return head + ct + hashlib.md5(head + ct).digest()
