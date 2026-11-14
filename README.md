# WhatsApp Chat Archive

Keep every WhatsApp photo, video and document **organised by chat and date**, in your own Google Drive,
with a WhatsApp-style viewer that works on any phone or browser. You can still find
*"the Manali photos I sent Papa on 21 Jan 2025"* after switching phones, even after clearing them from WhatsApp.

Personal tool. Android (WhatsApp end-to-end backup with a 64-digit key) + Google Drive.

| Part | What it does | Where it runs |
|---|---|---|
| FolderSync (3rd-party app) | Uploads WhatsApp's folder to `Drive/WhatsAppArchive/raw` every night | Your phone |
| [`indexer/`](indexer) | Decrypts `msgstore.db.crypt15` with your 64-digit key, maps every media file to its chat/date, makes thumbnails | Google Colab ([notebook](colab/WhatsApp_Archive_Indexer.ipynb)) or any PC |
| [`viewer/`](viewer) | WhatsApp-style web app: chats → messages, Media/Docs/Links tabs by month, search, jump to month, download | Your browser (GitHub Pages), reads Drive directly |

**Setup:** [docs/SETUP.md](docs/SETUP.md) (all from a phone).
**Demo:** open the viewer with `?demo=demo/` to browse synthetic sample data.

## Local use (PC)

```bash
pip install ./indexer
python -m wa_archive summary --backup msgstore.db.crypt15 --contacts contacts.vcf     # quick check
python -m wa_archive build --backup msgstore.db.crypt15 --media-root WhatsApp/ \
       --contacts contacts.vcf --out viewer/mydata/index
# then copy WhatsApp/Media to viewer/mydata/raw/Media and open
# http://localhost:8000/?demo=mydata/   after:  cd viewer && python -m http.server
```

## Known limits

- The `.crypt15` format and database schema are reverse-engineered. A WhatsApp update can break the parser.
  The raw backups stay safe in Drive, so the index can be rebuilt once the parser is fixed.
- Media deleted from the phone before the first upload can't be recovered (only WhatsApp's small preview).
- Contact names come from your Google Contacts export, not from WhatsApp.
- iPhone backups are not supported as a source (the viewer itself works on iPhone).
- Decryption is verified against the open-source [wa-crypt-tools](https://github.com/ElDavoo/wa-crypt-tools),
  but not yet against a real phone backup.

Tests: `cd indexer && pytest`
