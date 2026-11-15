# Chat Archive app: install & setup

One app does everything: reads WhatsApp's backup on your phone, uploads every photo, video and
document to your Google Drive each night, and shows them WhatsApp-style, sorted by chat and date.

**Download:** [`apk/ChatArchive.apk`](../apk/ChatArchive.apk) → on GitHub tap the file → **Download raw file**.

---

## A. One time on a computer or the phone's browser: let the app use Google Drive (~10 min)

Google requires every app that uploads to Drive to be registered. You do this once, and never again.
Use Chrome with **Desktop site** turned on (⋮ menu) if you're on the phone.

1. Go to **console.cloud.google.com** → project picker → **New project** → name `chat-archive` → **Create**.
2. ☰ → **APIs & Services → Library** → search **Google Drive API** → **Enable**.
3. ☰ → **Google Auth Platform** → **Get started**:
   - App name `Chat Archive`, support email = your Gmail → Next
   - Audience **External** → Next → contact email → Finish → **Create**
4. **Data access → Add or remove scopes** → search `drive.file` → tick
   `.../auth/drive.file` → **Update** → **Save**.
5. **Audience → Publish app → Confirm** (status becomes *In production*).
   This is allowed without Google review because the app only asks for `drive.file`. If you skip it, the
   app will ask you to sign in again every 7 days.
6. **Clients → Create client** → Application type **Android**:
   - Package name: `com.kandarp.chatarchive`
   - SHA-1 certificate fingerprint: `64:4D:7E:5E:69:2B:29:B6:15:3C:1E:3B:6F:F1:19:6C:EE:DD:18:73`
   - **Create**. You don't need to copy anything; Google recognises the app automatically.

## B. WhatsApp: use a 64-digit backup key (5 min)

WhatsApp → **Settings → Chats → Chat backup → End-to-end encrypted backup → Turn on →
"Use 64-digit encryption key instead"**. Save the key in a password manager, then tap **Back up**.

## C. Install and set up the app (5 min)

1. Open the downloaded `ChatArchive.apk`. Android asks to allow installs from your browser/Files app → **Allow** → **Install**.
   (Play Protect may warn that it doesn't know the app. Choose **More details → Install anyway**. It's your own app.)
2. Open **Chat Archive**. The setup screen has 5 steps:
   1. **Allow access to WhatsApp files** → turn on *Allow access to manage all files* → back.
   2. **Paste your 64-digit key** → *Save key*. It's stored encrypted on the phone and never uploaded.
   3. **Sign in with Google** → choose your account → allow access to files the app creates.
   4. **Allow contacts** (optional) so chats show "Papa" instead of "+91 98765…".
   5. **Back up now**. Keep the phone **on Wi-Fi and charging**. The first run uploads everything
      (~12 GB can take a night). A notification shows progress.
3. When the index is ready, tap **Open archive**. Chats → Papa → 🖼 → Media / Docs / Links by month.
   Tap a photo → ⬇ saves it to **Downloads/WhatsApp Archive**.

After that it runs **every night around 3:30 AM on Wi-Fi** by itself. The banner at the top of the
chat list shows the last result. Tap it (or ⚙) for details or *Back up now*.

## New phone

Install the APK, do steps C.2.1–3, tap **Back up now**:
- WhatsApp not restored yet → the app downloads your archive index from Drive, and every chat and photo
  is viewable immediately (photos stream from Drive).
- WhatsApp restored → it carries on backing up as normal and doesn't re-upload what's already in Drive.

## Freeing phone space

Once the banner says *All N files are safe in Google Drive*, you can clear media in WhatsApp
(Settings → Storage and data → Manage storage). The archive keeps showing them from Drive.
**Never delete the `WhatsAppArchive` folder in Drive.**

## Troubleshooting

| Message | Fix |
|---|---|
| *Could not decrypt… key is probably wrong* | Re-enter the key (step 2). If you changed the key in WhatsApp, make a new backup in WhatsApp first. |
| *Sign in to Google Drive again* | Setup → *Reconnect*. If it keeps happening, do step A.5 (Publish app). |
| Sign-in fails with code 10 / `DEVELOPER_ERROR` | Step A.6 isn't matching. Check the package name and SHA-1 exactly. |
| *Not enough free space to unpack the backup* | Free a few hundred MB (the decrypted chat database is temporary and deleted right after). |
| *Waiting for Wi-Fi…* forever | Backups only run on Wi-Fi with battery not low. |
| Photo shows *Not in backup* | It was deleted from the phone before the app ever uploaded it. Only WhatsApp's small preview survives. |

## Privacy

- Your files go only to **your** Google Drive, folder `WhatsAppArchive`. The app's `drive.file` permission
  can't see anything else in your Drive.
- The 64-digit key stays on the phone, encrypted by Android's secure hardware keystore.
- The chat index in Drive (`WhatsAppArchive/index`) contains your message text, protected by your
  Google account. Keep 2-step verification on.
- The app talks to nobody except Google.
