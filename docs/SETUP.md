# Setup guide (phone only, ~45 minutes once)

You set this up once. After that:
- **FolderSync** uploads WhatsApp's files to your Google Drive every night, by itself.
- You run the **Colab notebook** whenever you want the viewer updated (weekly is plenty, and
  always before switching phones).
- You open the **Chat Archive** app from your home screen to browse and download.

```
Phone ──FolderSync (nightly)──► Drive/WhatsAppArchive/raw/      (all 12 GB, never deleted)
                                         │
                       Colab notebook ───┘──► Drive/WhatsAppArchive/index/  (chat ↔ media map + thumbnails)
                                                        │
                                Chat Archive web app ◄──┘  (reads your Drive directly, nothing else)
```

---

## Step 1 — WhatsApp: switch to a 64-digit backup key (5 min)

1. WhatsApp → **Settings → Chats → Chat backup → End-to-end encrypted backup → Turn on**.
2. Choose **"Use 64-digit encryption key instead"** → **Generate your 64-digit key**.
3. **Save the key in a password manager** (Google Password Manager, Bitwarden…). Also write it down.
   Without it, neither this app nor WhatsApp can open your backups.
4. Back on *Chat backup*, tap **Back up**. Set *Back up to Google Account* frequency to **Daily**.
5. Check in your file manager that this file exists:
   `Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15`

> WhatsApp writes this local backup every night around 2 AM, even if Google Drive backup is off.

## Step 2 — Contact names (5 min)

WhatsApp's backup only stores phone numbers, not names. To show "Papa" instead of "+91 98765…":

1. Open **contacts.google.com** → ☰ → **Export** → *Contacts* → **vCard** → Export.
2. Move the downloaded `contacts.vcf` into Google Drive folder **WhatsAppArchive** (create the folder).
3. Optional: names for numbers not in your contacts — create `WhatsAppArchive/aliases.json`:
   ```json
   { "919876543210": "Papa", "+91 98123 45678": "Mumma" }
   ```

## Step 3 — FolderSync: nightly upload to Drive (10 min + first upload overnight)

Install **FolderSync** (by Tacit Dynamics) from the Play Store.

1. **Accounts → + → Google Drive** → sign in.
2. **Folder pairs → + → "WhatsApp to Drive"**:

| Setting | Value | Why |
|---|---|---|
| Left / local folder | `Android/media/com.whatsapp/WhatsApp` | Grant access when Android asks (or give FolderSync *All files access* in its permissions) |
| Right / remote folder | `/WhatsAppArchive/raw` | Create it from the picker |
| Sync direction | **To right folder** (one-way upload) | |
| **Sync deletions** | **OFF** ⚠️ | Otherwise clearing media in WhatsApp also deletes it from Drive |
| Delete source files after sync | **OFF** | |
| Overwrite old files | Always | Keeps the newest `msgstore.db.crypt15` |
| Schedule | Daily, 03:30 | After WhatsApp's 2 AM backup |
| Use Wi-Fi only | ON | |
| Require charging | ON (optional) | |
| Filters | Exclude folder name **starts with `.`** | Skips `.Statuses`, `.Thumbs`, `.trash` |

3. For the **first upload (12 GB)**: plug in the charger, stay on Wi-Fi, and tap *Sync* on the folder pair before sleeping.
4. Next morning, check in Google Drive that `WhatsAppArchive/raw/Media/WhatsApp Images` and
   `WhatsAppArchive/raw/Databases` exist.

> After you confirm a chat's media is in Drive, you can safely clear it from WhatsApp
> (Settings → Storage and data → Manage storage) to free phone space. It stays in the archive.

## Step 4 — Google sign-in for the viewer (10 min, one time)

The viewer runs in your browser and reads your Drive with **your own** Google Cloud "OAuth client".
Do this in Chrome with **Desktop site** turned on (⋮ menu); the console is hard to use otherwise.

1. Go to **console.cloud.google.com** → project picker → **New project** → name `chat-archive` → Create.
2. ☰ → **APIs & Services → Library** → search **Google Drive API** → **Enable**.
3. ☰ → **Google Auth Platform** (or *OAuth consent screen*) → **Get started**:
   app name `Chat Archive`, your email → Audience **External** → Create.
4. **Audience → Test users → Add users** → your own Gmail address.
   (Leave the app in *Testing*. That's fine for personal use.)
5. **Data access → Add or remove scopes** → tick `.../auth/drive.readonly` → Update → Save.
6. **Clients → Create client** → type **Web application** →
   *Authorized JavaScript origins*: `https://kandarp-joshi-007.github.io` → Create.
7. Copy the **Client ID** (`…apps.googleusercontent.com`).

When you sign in, Google will say **"Google hasn't verified this app"**. That is expected because it's
your own unpublished app. Tap *Continue*. Sign-in lasts 1 hour per session; then just tap *Sign in* again.

## Step 5 — Host the viewer on GitHub Pages (5 min)

1. The code is in your GitHub repo `whatsapp-backup`. Open it → **Settings → Pages → Source: GitHub Actions**.
2. Wait about 1 minute (Actions tab shows a green ✓). Your app is at
   `https://kandarp-joshi-007.github.io/whatsapp-backup/`
3. Try the demo first, with fake data and no sign-in:
   `https://kandarp-joshi-007.github.io/whatsapp-backup/?demo=demo/`

## Step 6 — Build the index with Colab (10 min the first time)

1. Open `https://colab.research.google.com/github/Kandarp-Joshi-007/whatsapp-backup/blob/main/colab/WhatsApp_Archive_Indexer.ipynb`
2. Cell 1 already points at your repo. Change nothing unless you renamed it.
3. Optional, so you don't paste the key every time: 🔑 (Secrets, left sidebar) → add `WA_KEY` = your 64-digit key → enable notebook access.
4. **Runtime → Run all**. Approve the Google Drive prompts.
5. The first run makes thumbnails for every photo and video (can take 20–60 min for 12 GB; keep the tab open).
   Later runs only process new files and finish in a few minutes.

## Step 7 — Open the app

1. Open `https://kandarp-joshi-007.github.io/whatsapp-backup/` → paste the Client ID → **Sign in with Google**.
2. Chrome ⋮ → **Add to Home screen**. It now opens like a normal app.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "Could not decrypt" | Wrong key, or the backup in Drive was made before you switched to the 64-digit key. Make a new backup in WhatsApp, run FolderSync, run Colab again. |
| "pre-2022 database layout" | Update WhatsApp and make a new backup. |
| Chats show numbers instead of names | Re-export `contacts.vcf` (Step 2) or add the number to `aliases.json`. |
| Photo shows "Not in backup" | The file was deleted from the phone before FolderSync uploaded it. Only the small preview from WhatsApp's database survived. |
| Sign-in popup does nothing | Allow pop-ups for `github.io` in Chrome settings. |
| `Error 400: redirect_uri_mismatch` / `origin_mismatch` | The JavaScript origin in Step 4.6 must be exactly `https://kandarp-joshi-007.github.io` (no trailing slash, no `/whatsapp-backup`). |

## Privacy

- Your photos, videos and the decrypted chat index live **only in your Google Drive**.
- The decrypted database exists only on the temporary Colab machine and is deleted at the end of the run.
- The viewer is a static page. It talks only to Google (sign-in + Drive API) from your browser.
- The index files in Drive contain your chat text **unencrypted**, protected by your Google account.
  Keep 2-step verification on.
- The 64-digit key is never written to Drive or GitHub.
