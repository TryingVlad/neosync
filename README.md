# NeoSync

Keeps your reading place in sync between **Neo Reader** on an Onyx Boox
tablet and **CrossPoint** on a Boox Picco (or any other device that speaks the
KOReader progress-sync protocol, "kosync").

You stay in Neo Reader, with its pen annotations. NeoSync runs beside it,
watches where you are, and exchanges that place with a sync server. It can
also copy your books to the Picco over Wi-Fi.

Forked from [eszter007/boox-kosync](https://github.com/eszter007/boox-kosync),
itself forked from [Tukks/onyxbooxsync.koplugin](https://github.com/Tukks/onyxbooxsync.koplugin).

## Download

Grab the APK from the
[latest release](https://github.com/TryingVlad/neosync/releases/latest)
(direct link:
[neosync.apk](https://github.com/TryingVlad/neosync/releases/latest/download/neosync.apk))
and sideload it on the Boox. Later releases install as updates over it:
NeoSync checks GitHub once a day and says so on the home screen when a new
version is out.

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

The home screen is your library: one row per book, on this Boox, on the
Picco or both, with a bar for where you are on each device and a line saying
how they compare: *Same place*, *Place sent* (the Picco catches up on its
next Sync Progress), *Read further here · syncs soon*, *Picco is ahead · tap
to jump*, *Not synced yet*, *Only on the Picco*. Only EPUB books are listed:
the Picco can't open PDFs and the other formats Neo Reader reads. Filters along
the top (**All**, **Reading**, **Only here**, **Only on Picco**) and a search
by title or author narrow it down. The list turns in pages with the arrows at
the bottom instead of scrolling, so each page is one e-ink refresh.

Above the list sit two chips. The left one is for reading places: synced
(with the last book), paused, or *not syncing*. The right one is for the
Picco: connected, or the age of its last book list. A chip turns black when
something is wrong; tap either one to open Connections.

Under the chips there is at most one suggestion at a time, the most useful
first: the fix for a known problem (a Wi-Fi without internet, a wrong
password, sync paused), connecting the Picco, sending the books it doesn't
have yet, or giving books a cover. **✕** puts a suggestion away. Long jobs
(sending books, syncing everything, adding covers) show their progress in
the same place with one **Stop** button, and each row says how it went.

**The book sheet.** Tap a book to see where you are on each device, when you
read there last, and which is more recent. One button does the obvious
thing: **Jump to the Picco's place** when the Picco is ahead, **Send this
place to the Picco** when this Boox is, **Send to Picco** when the Picco
doesn't have the book. Close the book in Neo Reader before jumping: a book
that is open on screen can't be moved. A jump can be undone for 24 hours
(**Undo jump**). Smaller links, when they apply: use the other device's place
instead, send the book file again, add a cover, open in Neo Reader.

**Connections.** A diagram of the two links: reading places go between this
Boox, the sync server and the Picco over the internet; books go from this
Boox to the Picco over the same Wi-Fi. Under it, one card each for this
Boox (file access), the sync server (working, or what's wrong and the button
that fixes it, plus **Sync all books now**) and the Picco. When the Picco
can't be found, its card lists the steps and **Look for the Picco** searches
the Wi-Fi for it, so you don't need its address. You can still enter one by
hand.

**Settings** (the gear icon):

- **Sync**: automatic sync on or off, and your account.
- **Permissions**: book file access and notifications, each with a button to
  allow it.
- **Picco**: where it was found, and the folder new books go to.
- **Library**: which folders the home list shows, from the folders in Neo
  Reader's library that hold EPUB books. This only narrows the list; syncing
  is not affected.
- **Help**: the setup guide and *Report a problem*.
- **Advanced**: book matching, the sync server, and the size of the cover
  backups.

## The Picco library

NeoSync finds the Picco by itself when CrossPoint's **File Transfer** screen
is open and both devices are on the same Wi-Fi (*Connections → Look for the
Picco*). It then keeps a list of the books on the Picco. The list is saved
between visits and refreshed quietly whenever the Picco answers; when it
can't be reached, the last list stays and the Picco chip shows its age.

A book's line comes from what the Boox already knows, so it costs no
requests to the sync server; reading done on the Picco shows up once the Boox
has fetched it.

Three jobs work on the whole library:

- **Sync all books now** (on Connections): for every book on both devices,
  asks the server and keeps the newest reading place: fetched if the Picco is
  ahead, sent if this Boox is ahead. Just opening a book in Neo Reader doesn't
  count as reading, so it never overwrites the Picco's place with a stale
  one. Requests are spaced out for the server's rate limit; the job stops
  (and says why) at the first rate limit or network error.
- **Send** (the "books aren't on the Picco yet" suggestion): copies every EPUB
  on this Boox that the Picco doesn't have, after showing how many and their
  total size.
- **Fix** (the "black cover" suggestion): the Picco shows a black tile for a
  book without a cover it can read. This finds those books, on this Boox and
  on the Picco, and gives each one a cover: a picture already in the book
  when its first page is just that picture (or one is called "cover"),
  otherwise a picture of the book's first page. Only the book's list of
  contents changes, never a chapter, so Neo Reader keeps your notes,
  highlights and place. The original is copied to `Books/.neosync-backup`
  first. The fixed file replaces the Picco's copy, which resets the Picco's
  own place in it: reading places are saved to the server first, so open the
  book on the Picco and choose Sync Progress to get your place back. Books
  that are only on the Picco are fixed only if you tick them. Needs book file
  access. A single book can be fixed from its sheet (**Add a cover**).

NeoSync never sends or fixes the files in hidden folders, such as its own
cover backups or Onyx's cloud copies.

The first time NeoSync sends a book, it lists the Picco's folders and asks
where books should go (it suggests `books` when there is one). The choice is
remembered; *Settings → Picco → Folder for new books* changes it. A book
already anywhere on the Picco, in any folder, is never sent twice.

Book files travel straight to CrossPoint's upload endpoint on your local
network: no sync server involved, so it works while the server is
rate-limiting.

### Wi-Fi: hotspot or shared network

Reading places go through the sync server on the internet; book files go to
the Picco on the local network. If the Boox joins the **Picco's own hotspot**,
book files work but reading places can't sync, because that network has no
internet. NeoSync then says so on the home screen, with a **Wi-Fi** button
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

*Settings → Report a problem* copies a short report to the clipboard:
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
- Sending the missing books only sends EPUBs.
- Sending books, all at once or one by one, gives a coverless EPUB its cover
  (as above) before sending it.
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
