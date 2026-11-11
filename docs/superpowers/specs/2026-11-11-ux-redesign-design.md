# NeoSync UX redesign: library first, tools when you need them

Date: 2026-11-11 · App version at time of writing: 7.2.0
Mockups (open in a browser): [main screens](2026-11-11-ux-redesign-mockups/main-screens.html) ·
[other states](2026-11-11-ux-redesign-mockups/states.html)

## 1. Why

NeoSync now does a lot: automatic position sync, forcing a direction for the latest
book, a Picco library list, sync-all, send missing books, cover fixing, update
notices, problem reports. Each feature was added as its own card or button on one
long scrolling home screen. The result: 8 full-width buttons, 3 refresh icons, a
toggle and two competing black "primary" buttons. A normal reader can't tell what
matters, what's safe, or what to press (usually: nothing, since sync is automatic).

The screen is organised around how the app works (server, fetch, upload, compare)
instead of what the reader wants:

1. **Is it working?** (a glance)
2. **Where am I in my books, on each device?**
3. **Is there something I should do?** (only when there is)

## 2. Goals, non-goals, anti-goals

**Goals**
- Home becomes the **library**: one list of every book on either device, with both
  positions visible per book.
- Every action belongs either to **a book** (book sheet) or to **a suggestion**
  (shown only when it applies). No free-floating action buttons on home.
- Keep **every existing feature** reachable (see the mapping in §6).
- Make the two connections understandable: positions go over the **internet** via the
  sync server; book files and the Picco's list go over **local Wi-Fi**.
- Suit e-ink: black and white, few borders, pages instead of long scrolling, few
  screen refreshes during jobs.
- Add the obvious missing features listed in §7.

**Non-goals**
- No change to the sync engine's behaviour (SyncService, position mapping, change
  feed, rate-limit handling, matching). This is a UI redesign plus the features in §7.
- No new platform or UI toolkit. Stay on Java + Android Views + the existing
  `NeoSync.*` styles; `material` is already a dependency (bottom sheets).
- Onboarding's 5-step guide and the sign-in screen keep their content; only entry
  points change.
- Deleting books from the Picco is out of scope (CrossPoint's file-server API for it
  hasn't been checked).

**Anti-goals (would be a failure even if it "works")**
- Any extra request to the sync server per book just to draw the list. The default
  server is rate-limited; the list must use only what the Boox already knows.
- A screen that says "synced" when the Boox has unsent reading.
- Hiding the risky part of a destructive action (cover fix resetting the Picco's place)
  or making it easier to hit by accident than today.
- Colour, grey fills or animation that look bad on e-ink.

## 3. Constraints (facts from the current code)

- Picco HTTP API used today: `GET /api/files?path=`, `GET /download?path=`,
  `POST /upload?path=` (`PiccoClient`). No other endpoints are assumed.
- Book status per row is computed locally by `BookSyncStatus.of(...)` from the Onyx
  row and `SyncStateStore.BookState` (no server call). Keep that rule.
- The **Picco's position per book** is not known locally today. It comes from the
  server (`KoSyncClient` progress records with `device`/`device_id`, and the change feed
  `GET /api/v1/progress/changes` on crosspoint-sync). See §5.1 for how the list gets it.
- Title/author: `EpubCover.titleAndAuthor(File)` already reads `dc:title`/`dc:creator`.
  Cover image: `EpubCover.check(File)` already finds a book's cover.
- A fetched position can't move a book that's open in Neo Reader.
- minSdk 24, targetSdk 34.

## 4. Information architecture

```
Home (library)
 ├─ Status chip: Positions ──┐
 ├─ Status chip: Picco ──────┴─> Connections screen
 ├─ Suggestion slot (0 or 1 card, or the progress card of a running job)
 ├─ Filters + search
 ├─ Book rows (paged) ──> Book sheet
 └─ ⚙ ──> Settings ──> Account / Picco / Book matching / Sync server /
                        Cover backups / Setup guide / Report a problem / About
```

## 5. Screens

### 5.1 Home: the library (mockup 1)

**Header:** "NeoSync" title, gear icon to Settings. No refresh icons: the Picco list
refreshes by itself as today (on open, and in the background while File Transfer is
open); the Connections screen has the manual "look again".

**Two status chips** side by side, each tappable (opens Connections):

| Chip | States (headline · second line) |
|---|---|
| Positions | `Positions synced` · "2 min ago · <book>" — `Nothing synced yet` — `Sync paused` (auto sync off) — `Positions not syncing` · "since 14:10 · tap to fix" (problem) |
| Picco | `Picco connected` · address — `Picco not connected` · "book list from 15:46" — `Picco` · "not set up" |

A problem state is drawn **inverted** (black chip, white text). That's the only
"alarm" signal; there is no colour.

**Suggestion slot:** at most one card, placed under the chips. Priority (highest
first):

1. A running job's **progress card** (§5.5).
2. Problems with a known cause and a fix: no internet / Picco hotspot ("Wi-Fi"
   button), wrong password ("Sign in"), server unreachable ("Try again"), sync paused
   ("Turn on").
3. Picco not set up → "Connect your Picco" ("Find").
4. Books on this Boox not on the Picco → "N books aren't on the Picco yet · names ·
   size" ("Send"). Only when the Picco is connected.
5. Books that would show a black cover → "N books would show a black cover on the
   Picco" ("Fix" → cover sheet §5.6).
6. Update available → "NeoSync X is available" ("Get it").

Cards 3–6 have an ✕ that hides that suggestion until its count or version changes.
Problem cards (2) can't be dismissed; they clear by themselves when the cause is gone.

**Filters:** chips `All N` · `Reading` · `Only here` · `Only on Picco`, plus a search
icon that opens a text field filtering by title/author. When the Picco isn't set up,
`Only on Picco` is hidden.
- *Reading* = started and below 100% on either device.
- The selected filter is remembered between visits.

**Book list:** union of books in the Onyx library (EPUB and others) and books in the
cached Picco list, matched by file name (same rule as today, including both accent
spellings). Sorted by most recent reading activity on either device, newest first.

Each row:
- Cover thumbnail (EPUB cover; for Picco-only books or books with no cover, a plain
  tile with the title in small type). A book the Picco would show without a cover
  gets a solid black tile, which is how the Picco itself would show it.
- Title (bold) and author. From the EPUB's metadata when the file is on this Boox;
  otherwise a cleaned file name (no extension, underscores → spaces, trailing
  parenthetical lists of domains removed).
- Status line, in words: `✓ Same place · 13:37`, `Read further here · syncs soon`,
  `Read further here · waiting to sync` (when positions aren't syncing),
  `Picco is ahead · tap to jump`, `Not on the Picco`, `Only on the Picco`,
  `Not synced yet`, `Not opened yet`.
- Two mini progress bars with percentages: **Boox** and **Picco**, or `—` when the
  book isn't on that device or has no known position.

Where the **Picco position** comes from (no new per-book requests):
- The last remote record NeoSync has seen for that document: from the change feed,
  from a fetch, from sync-all, or from a book-sheet check. Store it per book (percentage,
  device name, timestamp) in `SyncStateStore`.
- When the book is `SYNCED`, the Picco bar equals the Boox bar.
- Otherwise, with no stored record, show `—`.
- Label the bar "Picco" when the record's device is CrossPoint (or unknown). When
  another device wrote it (e.g. KOReader), the book sheet shows that device's name.

**Paging:** fixed pages sized to fill the screen (no scrolling list), with `‹ 1 / 4 ›`
at the bottom. Page buttons sit where a thumb reaches; volume/page keys turn pages too
if the device sends them.

### 5.2 Book sheet (mockup 2)

Opened by tapping a row; a bottom sheet over the dimmed library.

- Header: larger cover, title, author, "On both devices / Only on this Boox / Only on
  the Picco · EPUB · size".
- **Comparison box:** a bar per device (`This Boox`, `Picco` or the other device's name),
  percentage, and "chapter · when" when known (chapter from the spine; when the chapter
  can't be worked out, just the time). A verdict line:
  `Same place.` / `You read further on the Picco.` / `You read further here.` /
  `Nothing saved online yet.`
  Opening the sheet **does one live position check** for this book (today's "Compare
  positions"), shows "Checking…" briefly, then updates the box. One request per sheet
  opening is acceptable.
- **One primary button**, chosen by state:
  - Picco ahead → `Jump to the Picco's place (73%)` (today's Fetch from Picco). Hint
    under it: close the book in Neo Reader first; you can undo for 24 hours.
  - Boox ahead → `Send this place to the Picco (52%)` (today's Send to Picco).
  - Not on the Picco → `Send to Picco` (book file; adds a cover first if needed, as
    today).
  - Same place → no primary button.
- **Secondary links** (small, underlined), when they apply:
  `Keep this Boox's place instead` / `Use the Picco's place instead` (force the other
  direction), `Send book file again`, `Add a cover`, `Open in Neo Reader`, and
  `Undo jump` while an undo is available (§7.5).
- Results show inside the sheet ("Place sent." / "Done. Open the book in Neo Reader."),
  not as toasts.
- For a Picco-only book: comparison shows only the Picco bar; no primary button (the
  Boox doesn't have the file).

### 5.3 Connections screen (mockups 3 and 6)

Opened from either chip. Back arrow + "Connections".

1. **Diagram card:** Boox ↔ sync server ↔ Picco labelled "positions · internet"; a
   second line "book files & book list · same Wi-Fi", drawn dashed with "not connected"
   when the Picco isn't reachable.
2. **Sync server card:** state in one sentence (`● Working. Last sync 2 min ago
   (Fictions, 88%).` or the problem plus its fix button: Try again / Sign in / Wi-Fi
   settings / Turn on). Link: `Sync all books now` (today's Sync all positions; runs as
   a job, §5.5).
3. **Picco card:** state in one sentence. When not reachable: numbered steps (open File
   Transfer; Join a Network on the same Wi-Fi; come back), buttons `Look for the Picco`
   (§7.4) and `Wi-Fi settings`, and a small line `Last seen at <ip> · enter address by
   hand`. The hotspot explanation lives here too.

### 5.4 Settings (mockup 4)

A plain list of rows (title + current value), grouped:

- **Sync:** Automatic sync (switch; moved from home); Account (→ existing sign-in /
  manage account flow).
- **Picco:** Picco (→ Connections-style page: found address, Look for the Picco, enter
  by hand); Folder for new books (value + `Change` → today's folder chooser /
  "choose again").
- **Help:** Setup guide (→ existing onboarding); Report a problem (→ today's copy
  problem report, with its explanation on that page).
- **Advanced:** Book matching (→ own page with the two options and a warning to use the
  same mode on both devices); Sync server (→ own page with today's server field and
  https rule; moved out of sign-in's "Advanced"); Cover backups (folder and file count,
  read-only info).
- **About:** version + update state (and `Download version X` when available), open
  source / not affiliated line; Buy me a coffee.

### 5.5 Long jobs (mockup 5)

Send missing books, Sync all positions, and Fix covers each run as a **job**:
- The progress card takes the suggestion slot: title ("Sending books to the Picco"),
  `2 of 4`, a bar, the current book and a reminder when relevant ("keep File Transfer
  open on the Picco"), and `Stop`.
- Affected rows show `Sent` / `Sending…` / `Waiting` (or `Fetched` / `Sent place`).
- Update the card and rows at most every ~3 seconds (e-ink).
- At the end the card shows the result sentence (today's done/stopped/lost strings) and
  disappears after ~10 seconds or on tap.
- One job at a time; starting points for other jobs are disabled while one runs.
- Confirmations stay as today (send-missing: count, size and folder; cover fix: §5.6).

### 5.6 Fix covers sheet (mockup 7)

Opened from the black-cover suggestion (or a book's `Add a cover`, for that book only).
- Heading "N books would show a black cover on the Picco" and one reassurance sentence.
- A row per book: black tile → new cover thumbnail, with title and "picture from the
  book" / "its first page". Collapse beyond 3 into "+ N more".
- Warning box only when some are on the Picco: the reset-place explanation.
- Tick box for Picco-only books, **unticked** by default, with today's wording.
- Primary `Add N covers`; note "Originals are kept in Books/.neosync-backup".
- Behaviour (save positions first, backup, replace on Picco) is unchanged.

### 5.7 First launch, no Picco (mockup 8)

After onboarding and sign-in, home shows `Signed in · nothing synced yet` and `Picco ·
not set up`, the "Connect your Picco" suggestion, and the library with Boox books only.
When nothing has a position yet, an empty-state line under the rows: "Read a few pages
in Neo Reader… On the Picco, open the same book and choose Sync Progress."

## 6. Where every current feature goes

| Today | New home |
|---|---|
| Status card + Try again | Positions chip + problem suggestion + Connections |
| Automatic sync switch | Settings → Sync (home shows "Sync paused · Turn on" when off) |
| Latest book card: Compare positions | Automatic when a book sheet opens |
| Fetch from Picco / Send to Picco | Book sheet primary button + "instead" link |
| Send book file to Picco | Book sheet (`Send to Picco` / `Send book file again`) |
| Refresh latest book / refresh Picco list | Automatic; Connections → Look for the Picco |
| Sync all positions | Connections → Sync all books now (job) |
| Send missing books | Suggestion card → existing confirmation → job |
| Fix missing covers | Suggestion card / book sheet → cover sheet → job |
| Hotspot hint + Wi-Fi settings | Problem suggestion + Connections |
| Update banner / Download | Suggestion card; Settings → About |
| Picco address | Settings → Picco (+ auto-discovery) |
| Picco folder / Choose again | Settings → Folder for new books |
| Book matching | Settings → Advanced → Book matching |
| Sync server address | Settings → Advanced → Sync server |
| How it works (guide) | Settings → Help → Setup guide |
| Copy problem report | Settings → Help → Report a problem |
| Buy me a coffee, version, footer | Settings → About |

## 7. New features

1. **Tappable books** with the book sheet (§5.2).
2. **Titles, authors, covers** in the list, from EPUB metadata, cached by path + file
   modified time, so the list doesn't re-open every EPUB on each visit. Thumbnails go
   in the app's cache directory.
3. **Books only on this Boox** appear in the list and the `Only here` filter.
4. **Find the Picco automatically** (`Look for the Picco`): try the last known address,
   then the Wi-Fi gateway (the Picco's own hotspot), then probe the local /24 subnet
   for a host answering `GET /api/files?path=/` with CrossPoint's JSON, in parallel with
   short timeouts, stopping at the first hit. Finish within ~10 s; on success, save the
   address. If CrossPoint advertises itself over mDNS, checking that first is a
   builder's call. Manual entry stays as a fallback.
5. **Undo a jump.** Before writing a fetched position into the Onyx row (manual jump or
   automatic fetch), save the row's previous position fields. `Undo jump` in the book
   sheet writes them back with `lastAccess = now`, so the watcher treats it as reading
   done here and **sends it to the server: the Picco follows** on its next Sync Progress
   (decided by the user). The sheet says so: "Back to 41%. The Picco goes back too
   next time you choose Sync Progress there." Estimated reading statistics written by
   the fetch are not reversed *(call)*. Available for 24 hours or until the book's
   position changes again on either device, whichever comes first.
6. **Filters and search** (§5.1).
7. **A useful notification:** the foreground notification says the latest event in
   words ("Fictions synced · 88%", "Positions not syncing · tap to fix") and opens the
   app.

## 8. Visual rules (e-ink)

- Black on white only; mid-grey only for secondary text. No grey fills except the
  sheet's scrim and the warning box.
- Borders only on chips, cards that need grouping, and buttons. Rows are separated by
  hairlines, not boxes.
- One black (primary) button per screen at most.
- Tap targets ≥ 48dp. Text sizes from the existing `NeoSync.*` styles.
- No animation; bottom sheets appear without slide-in where the platform allows.

## 9. Decisions on open points (from the builder review)

Calls made while writing this up (marked so they can be checked):

1. **Remote positions store** *(call)*. Add a small store keyed by **document hash**
   (percentage, device, device_id, timestamp), separate from `SyncStateStore`'s
   path-keyed state. The change feed records **every** change it sees, including ones
   for books not on this Boox or not started here. Today it drops those, and recording
   them is the one allowed engine change; it doesn't alter what gets applied. Rows find
   their record by computing the hash from the file name (filename mode). In binary mode,
   books that are only on the Picco show `—`, and their sheet does no live check.
   **No feed-cursor reset.** Existing users fill the store gradually through new feed
   entries, sheet checks and "Sync all books now". Until then rows show `—`.
2. **"Ahead" means more recent, not further** *(call)*, as the engine already decides.
   The sheet's verdict reads `You read more recently on the Picco.` / `…here.`, with
   both percentages shown. Since newer remote positions are applied automatically, a
   row says `Picco is ahead · tap to jump` only when the stored remote record is newer
   than the Boox's and wasn't applied (book open, never opened here, or the pull failed).
3. **Non-EPUB books** *(call)*. They're listed with only the Boox bar and the status
   `On this Boox only (PDF)` (or the file's type). They're excluded from `Only here`,
   from the "aren't on the Picco" count and from cover fixing, and the sheet offers no
   send button. Position sync for them is unchanged.
4. **Problem state** *(call)*. `SyncService`/`SyncAll` store an error **type** (no
   internet, auth, server unreachable, rate limited, other) and the **first-failure
   time** next to today's message. These are new fields; retry behaviour is unchanged.
   The chip turns black only after the failure has lasted 5 minutes or 2 attempts in a
   row, so e-ink doesn't flicker. It clears on the next good answer, as today.
5. **Undo jump**: restores the place on the Boox and pushes it, so the Picco follows
   (decided by the user; details in §7.5).

## 10. Left to the builder

- Code structure: split `MainActivity` (1,473 lines today) as you see fit (e.g.
  library adapter, book sheet, job runner, suggestion picker).
- Exact page size, thumbnail size and caching format.
- How "most recent activity" is computed for Picco-only books (e.g. the stored remote
  timestamp, else bottom of the list).
- Strings: reuse today's wording where it still fits; new strings follow its plain,
  second-person tone.
- Tests: unit tests for the pure logic (status line choice, suggestion priority, title
  cleaning, Picco-position source, undo expiry, subnet probe target list), in the style
  of the existing `*Test.java` files.
