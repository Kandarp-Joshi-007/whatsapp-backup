import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from wa_archive.drive_ids import FOLDER, folder_id_for_path, list_tree  # noqa: E402

TREE = {  # id: (name, parent, mime, size)
    "A": ("WhatsAppArchive", "root", FOLDER, 0), "R": ("raw", "A", FOLDER, 0),
    "M": ("Media", "R", FOLDER, 0), "I": ("WhatsApp Images", "M", FOLDER, 0),
    "S": ("Sent", "I", FOLDER, 0), "f1": ("IMG-1.jpg", "I", "image/jpeg", 10), "f2": ("IMG-2.jpg", "S", "image/jpeg", 20),
}


class FakeSvc:
    def files(self): return self

    def list(self, q, **kw):
        self._q = q
        return self

    def execute(self):
        q = self._q
        parent = q.split("' in parents")[0].split("'")[-1]
        name = q.split("name='")[1].split("'")[0] if "name='" in q else None
        files = [{"id": k, "name": n, "mimeType": m, "size": str(s)} for k, (n, p, m, s) in TREE.items()
                 if p == parent and (name is None or n == name)]
        return {"files": files}


def test_tree_listing():
    svc = FakeSvc()
    rid = folder_id_for_path(svc, "WhatsAppArchive/raw")
    assert rid == "R"
    ids = list_tree(svc, rid, log=lambda *a: None)
    assert ids == {"Media/WhatsApp Images/IMG-1.jpg": {"id": "f1", "size": 10},
                   "Media/WhatsApp Images/Sent/IMG-2.jpg": {"id": "f2", "size": 20}}
