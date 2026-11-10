# NeoSync

Keeps your reading position in sync between **Neo Reader** on an Onyx Boox
tablet and **CrossPoint** on a Boox Picco (or any other device that speaks the
KOReader progress-sync protocol, "kosync").

You stay in Neo Reader, with its pen annotations. NeoSync runs beside it,
watches where you are, and exchanges that position with a sync server.

Forked from [eszter007/boox-kosync](https://github.com/eszter007/boox-kosync),
itself forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin).

## Download

Grab the APK from the
[latest release](https://github.com/TryingVlad/neosync/releases/latest)
(direct link:
[neosync.apk](https://github.com/TryingVlad/neosync/releases/latest/download/neosync.apk))
and sideload it on the Boox.

## What you need

- A Boox running Neo Reader. Developed and tested on a **Go 10.3 Gen 2 Lumi,
  firmware 4.2 (Android 15)**. The upstream project was tested on a Poke3
  (Android 10); that older position format is still supported in the code but
  has not been re-tested in this fork.
- A Picco running **CrossPoint** firmware with *Settings → Connectivity →
  CrossPoint Sync*. The firmware the Picco ships with does not have it. See
  [crosspointreader.com](https://crosspointreader.com). Boox does not support
  custom firmware and says it may void your warranty.
- The **same EPUB with the same file name** on both devices.

## Setup

1. Install the APK. If the icon is greyed out, long-press it and choose
   **Unfreeze**.
2. Follow the in-app guide: it covers the Picco settings and asks for two
   permissions (book file access, notification).
3. Sign in with the account you use on the Picco, or create one in the app.
   An empty server address means `https://sync.crosspointreader.com`,
   CrossPoint's default.
4. In *Settings → Apps → NeoSync*, allow background running and auto-start.
   Boox power management can otherwise stop the sync service.

On the Picco: open the book, press Confirm, choose **Sync Progress**.

## How it works

- A foreground service watches Onyx's `Metadata` content provider
  (`content://com.onyx.content.database.ContentProvider/Metadata`), with a
  60-second poll as fallback.
- **Upload:** when Neo Reader's position changes, it is sent to the server.
- **Fetch:** when the server holds a newer position from another device, it
  is written into Neo Reader's row. Neo Reader only reads it on a cold start,
  so its background process is killed first; a book open in the foreground is
  never interrupted and cannot be moved.

### Position mapping (firmware 4.x)

Neo Reader stores `current_page_position_v2` as
`{chapterIndex, nodePosition, chapterStartPosition, chapterEndPosition, chapterID, …}`:

- `chapterStartPosition` / `chapterEndPosition` are cumulative **uncompressed
  byte sizes of the EPUB's spine items**, in reading order.
- `nodePosition` is a **character** offset (UTF-8 code points) into the
  chapter file.

CrossPoint defines its sync percentage over the same cumulative spine bytes.
NeoSync therefore reads the EPUB's spine and converts exactly, instead of
using `page/totalPages`, which can be several percent off because pages and
bytes do not grow at the same rate. Neo Reader accepts a bare `nodePosition`
with an empty `xpath` and starts the page there.

Accuracy: fetching into Neo Reader lands within a line or two. Sending to the
Picco is accurate to the paragraph Neo Reader last recorded, then rounded to
CrossPoint's own page breaks (about a page).

Non-EPUB files, and EPUBs the app cannot read, fall back to page-based
percentages.

### Book matching

By default a book is identified by the MD5 of its **file name**, CrossPoint's
default. "By file contents" uses KOReader's partial-MD5 instead and needs
byte-identical files. Use the same mode on both devices.

## Privacy

Only the MD5 of your password is stored on the device, which is the credential
the protocol uses. Your username, book identifiers (hashes, not titles) and
positions are stored on the sync server you choose.

## Known limitations

- A fetch cannot move a book that is open on screen; close it first.
- KOReader on the same device with the same account will fight this app.
- Calibre-Web-Automated's kosync bridge expects the plaintext password and
  will not work.
- The provider schema is firmware-specific. Other Boox firmware versions may
  need adjustments.

## Building

```bash
cd android
./gradlew assembleDebug testDebugUnitTest   # JDK 21, Android SDK platform 36.1
```

The debug APK is signed with the machine's debug key; no secrets are needed.

## Credits

App icon by [ふにゃ猫 – funyaneko](https://iconbu.com/).

Forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin);
kosync protocol compatibility modeled on
[CrossPoint](https://github.com/crosspoint-reader/crosspoint-reader)'s KOReaderSync client.
NeoSync is a client of the kosync protocol whose reference server is
[koreader-sync-server](https://github.com/koreader/koreader-sync-server) — it
shares no code with it, but it talks to any server that implements that API.
