# NeoSync

Keeps your reading position in sync between **Neo Reader** on an Onyx Boox
tablet and **CrossPoint** on a Boox Picco (or any other device that speaks the
KOReader progress-sync protocol, "kosync").

You stay in Neo Reader, with its pen annotations. NeoSync runs beside it,
watches where you are, and exchanges that position with a sync server. It can
also copy your books to the Picco over Wi-Fi.

Forked from [eszter007/boox-kosync](https://github.com/eszter007/boox-kosync),
itself forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin).

## Download

Grab the APK from the
[latest release](https://github.com/TryingVlad/neosync/releases/latest)
(direct link:
[neosync.apk](https://github.com/TryingVlad/neosync/releases/latest/download/neosync.apk))
and sideload it on the Boox. Later releases install as updates over it:
NeoSync checks GitHub once a day and shows a banner when a new version is
out.

Coming from **7.0.0 or older**: uninstall that version first. Those builds
were signed with a temporary key, so Android won't accept 7.0.1 as an update
(you'll need to sign in again).

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
   CrossPoint's default. A custom server must use `https://`.
4. In *Settings → Apps → NeoSync*, allow background running and auto-start.
   Boox power management can otherwise stop the sync service.

On the Picco: open the book, press Confirm, choose **Sync Progress**.

## Using it

The home screen has three parts:

- **Status**: whether sync is working, the last book synced, and an
  automatic-sync switch. When something goes wrong it says *Needs attention*
  with a **Try again** button; the message also clears by itself as soon as
  the server answers again.
- **Latest book**: your position here and on the server for the book you
  read last, with buttons to force a direction (**Fetch from Picco** /
  **Send to Picco**) and to copy the book file to the Picco.
- **On the Picco**: the books on your Picco (see below).

Account, Picco address, book matching and the guide are behind the gear icon.

## The Picco library

Set the Picco's address in *Settings* (CrossPoint shows it on its
**File Transfer** screen). NeoSync then keeps a list of the books on the
Picco. It is saved between visits and refreshed quietly in the background
whenever the Picco's File Transfer screen is open; when the Picco can't be
reached, the last list stays, with its age shown.

Each book shows how it stands on this Boox: *Synced* (with the time), *Read
further here* (the sync service sends it soon), *Not synced yet*, or *Not on
this Boox*. This comes from what the Boox already knows, so it costs no
requests to the sync server; reading done on the Picco shows up once the Boox
has fetched it.

Two buttons work on the whole list:

- **Sync all positions**: for every book on both devices, asks the server
  and keeps the newest position: fetched if the Picco is ahead, sent if this
  Boox is ahead. Just opening a book in Neo Reader doesn't count as reading,
  so it never overwrites the Picco's position with a stale one. Requests are
  spaced out for the server's rate limit; the run stops (and says why) at the
  first rate limit or network error.
- **Send missing books**: copies every EPUB on this Boox that the Picco
  doesn't have yet, after showing how many and their total size.

The first time NeoSync sends a book, it lists the Picco's folders and asks
where books should go (it suggests `books` when there is one). The choice is
remembered; *Settings → Connection → Choose again* makes it ask once more. A
book already anywhere on the Picco, in any folder, is never sent twice.

Book files travel straight to CrossPoint's upload endpoint on your local
network: no sync server involved, so it works while the server is
rate-limiting.

### Wi-Fi: hotspot or shared network

Positions go through the sync server on the internet; book files go to the
Picco on the local network. If the Boox joins the **Picco's own hotspot**,
book files work but positions can't sync, because that network has no
internet. NeoSync then shows a hint with a **Wi-Fi settings** button
(Android doesn't let apps switch networks themselves).

To do both without switching, use **File Transfer → Join a Network** on the
Picco, so it joins your usual Wi-Fi next to the Boox.

## How it works

- A foreground service watches Onyx's `Metadata` content provider
  (`content://com.onyx.content.database.ContentProvider/Metadata`), with a
  60-second poll as fallback.
- **Upload:** when Neo Reader's position changes, it is sent to the server.
- **Fetch:** when the server holds a newer position from another device, it
  is written into Neo Reader's row, so the book opens there next time. A book
  open in the foreground is never interrupted and cannot be moved.
- **Finding what changed:** the default server (crosspoint-sync) has a change
  feed: one request lists every book whose position another device changed
  since the last check. NeoSync reads it when the Boox wakes and every few
  minutes, so a book you read on the Picco is already at the right page when
  you open it on the Boox, even if it isn't your latest book. Plain kosync
  servers don't have the feed; there NeoSync asks about the books on both
  devices one by one (all of them when the Boox wakes, a few at a time
  otherwise).

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

An accented letter can be stored two ways in a file name ("é" as one
character, or "e" plus an accent mark). The Boox and the Picco don't always
agree, so NeoSync checks and sends both spellings of such names.

Two different files of the same book (another edition, another download) are
two different books to the sync server. Keep one copy on both devices.

## Reporting a problem

*Settings → Copy problem report* copies a short report to the clipboard:
your device model, firmware, Android version, a few NeoSync settings and its
recent log. Book names, file paths, document hashes and your account details
are removed, and it fits in a Reddit comment. Paste it, with a few words on
what happened, in a Reddit comment or message, or in a
[GitHub issue](https://github.com/TryingVlad/neosync/issues). The app sends
nothing by itself.

## Privacy

Only the MD5 of your password is stored on the device, which is the credential
the protocol uses. Your username, book identifiers (hashes, not titles) and
positions are stored on the sync server you choose, always over HTTPS. Book
files and the Picco's book list only travel between the Boox and the Picco on
your local network (plain HTTP, as CrossPoint's file-transfer server speaks
it). Once a day NeoSync asks GitHub's public API for the latest release; that
request carries nothing but the app version.

## Known limitations

- A fetch cannot move a book that is open on screen; close it first.
- "Send missing books" only sends EPUBs.
- Books are matched between the devices by file name, also in the Picco
  list, so both devices need the same file.
- A book never opened in Neo Reader can't be moved ahead of time: Neo Reader
  only records its page count once it has been opened.
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
Published releases are built by `.github/workflows/release.yml` when a
`v*.*.*` tag is pushed, signed with the project's release key from the
repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`).

## Credits

Forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin);
kosync protocol compatibility modeled on
[CrossPoint](https://github.com/crosspoint-reader/crosspoint-reader)'s KOReaderSync client.
NeoSync is a client of the kosync protocol whose reference server is
[koreader-sync-server](https://github.com/koreader/koreader-sync-server) — it
shares no code with it, but it talks to any server that implements that API.
