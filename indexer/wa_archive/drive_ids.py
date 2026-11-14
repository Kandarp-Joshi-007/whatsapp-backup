"""List every file under the raw WhatsApp folder in Google Drive and map
'Media/WhatsApp Images/IMG-...jpg' -> {id, size}, so the viewer can open files directly.
Used from the Colab notebook (needs google-api-python-client + Colab auth)."""
from __future__ import annotations

FOLDER = "application/vnd.google-apps.folder"


def _q(svc, q, fields="nextPageToken, files(id, name, mimeType, size)"):
    page = None
    while True:
        r = svc.files().list(q=q, fields=fields, pageSize=1000, pageToken=page,
                             supportsAllDrives=True, includeItemsFromAllDrives=True).execute()
        yield from r.get("files", [])
        page = r.get("nextPageToken")
        if not page:
            return


def folder_id_for_path(svc, path: str) -> str:
    """'WhatsAppArchive/raw' -> folder id (starting at My Drive root)."""
    parent = "root"
    for part in [p for p in path.split("/") if p]:
        esc = part.replace("\\", "\\\\").replace("'", "\\'")
        hits = list(_q(svc, f"name='{esc}' and '{parent}' in parents and mimeType='{FOLDER}' and trashed=false"))
        if not hits:
            raise FileNotFoundError(f"Drive folder not found: {path} (missing '{part}')")
        parent = hits[0]["id"]
    return parent


def list_tree(svc, root_id: str, log=print) -> dict[str, dict]:
    out: dict[str, dict] = {}
    stack = [(root_id, "")]
    while stack:
        fid, prefix = stack.pop()
        for f in _q(svc, f"'{fid}' in parents and trashed=false"):
            rel = prefix + f["name"]
            if f["mimeType"] == FOLDER:
                stack.append((f["id"], rel + "/"))
            else:
                out[rel] = {"id": f["id"], "size": int(f.get("size") or 0)}
        if len(out) and len(out) % 5000 < 1000:
            log(f"  listed {len(out)} files…")
    return out
