# NeoSync UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace NeoSync's button-heavy home screen with a library-first design (status chips, one suggestion slot, a paged two-device book list, a book sheet, a Connections screen, and regrouped Settings), and add the seven features in the spec's §7.

**Architecture:** First, pure-Java logic in a new `dev.neosync.boox.library` package (the list model, row status, suggestions, comparison, undo expiry, title cleaning, Picco address candidates, and problem classification), all unit-tested on the JVM like the existing `*Test.java` files. Small hooks in `SyncService`/`SyncActions`/`SyncSettings` record the data the UI needs, without changing what sync does. Then the Android Views layer: `MainActivity` is rebuilt around the library, and the long jobs move out of it into `dev.neosync.boox.jobs`.

**Tech Stack:** Java 11+, Android Views, `com.google.android.material` (BottomSheetDialog), JUnit 4, Gradle. JDK 21 + Android SDK as in `README.md` → Building.

**Spec:** `docs/superpowers/specs/2026-11-11-ux-redesign-design.md`. Mockups are in `docs/superpowers/specs/2026-11-11-ux-redesign-mockups/`.

## Global Constraints

- Build/test command, run from `android/` with the env in the README: `./gradlew assembleDebug testDebugUnitTest`. Every task ends with it passing.
- Work on branch `ux-redesign` (create from `main` before Task 1). One commit per task.
- No new sync-server requests to draw the list. Only the book sheet does one live check per opening (spec §5.2).
- Sync behaviour unchanged. The only engine changes allowed: record remote records (Task 1), record the error type and first-failure time (Task 2), and save/restore the pre-jump position (Task 6).
- Black/white only; mid-grey only for secondary text. At most one `NeoSync.Button.Primary` visible per screen. Tap targets ≥ 48dp. No animations (spec §8).
- Job UI updates are throttled to at most one every 3000 ms; the result card auto-hides after 10000 ms (spec §5.5).
- The problem chip turns black only after 5 minutes (300000 ms) of failure or 2 consecutive failed attempts (spec §9.4).
- Undo is available for 24 h (86400000 ms) or until the book's position changes again on either device (spec §7.5).
- Picco discovery finishes within ~10 s (spec §7.4).
- Never log or store the user's password or book titles in the problem report (existing rule in `ProblemReport`).
- Strings go in `res/values/strings.xml`. Wording is plain, second person, and reuses existing strings where they still fit.

## Review Focus

1. **Accented file names** (é as one character vs e + accent): the Boox and Picco copies of one book must merge into **one** row. Test: `LibraryModelTest.accentSpellingsMergeIntoOneRow` (Task 4).
2. **The Picco can't be reached and the list is cached**: rows still show the cached Picco books, and the chip says "book list from <time>", not "not set up". Test: `SuggestionPickerTest.unreachablePiccoWithCacheDoesNotAskForSetup` (Task 5).
3. **Jumping while the book is open in Neo Reader**: the sheet says to close it first, and **no undo** is recorded for a jump that wrote nothing. Test: `UndoRuleTest.noUndoWhenNothingWasWritten` (Task 6).
4. **Rate-limited or offline during the sheet's live check**: the sheet keeps the stored values, says "Couldn't check just now", and shows no primary button based on stale data. Test: `BookComparisonTest.failedLiveCheckOffersNoAction` (Task 8).
5. **Screen size changes** (rotation, split screen): the page size is recomputed and the current page clamped, so no empty page and no clipped row. Test: `LibraryPagerTest.clampsPageWhenPageSizeGrows` (Task 4).

---

## File Structure

New package `app/src/main/java/dev/neosync/boox/library/` (pure Java, no Android imports unless noted):

| File | Responsibility |
|---|---|
| `RemotePosition.java` | Value object for one remote record + JSON codec + `newer()` |
| `RemotePositionStore.java` (Android) | SharedPreferences store keyed by document hash |
| `SyncProblem.java` | Error type enum, classification, alarm rule |
| `TitleCleaner.java` | Display title from a file name |
| `BookMeta.java` / `BookMetaCache.java` (Android) | Title/author/cover thumbnail, cached by path + mtime |
| `LibraryEntry.java` | One merged row (Boox side, Picco side, meta, status) |
| `RowStatus.java` | Enum of row status lines + the rule choosing one |
| `LibraryModel.java` | Merge, filter, search, sort |
| `LibraryPager.java` | Page size / current page arithmetic |
| `Suggestion.java` / `SuggestionPicker.java` | Suggestion kinds + priority + dismissals |
| `BookComparison.java` | Sheet verdict + primary action |
| `UndoRule.java` / `UndoStore.java` (Android) | Undo expiry rule; saved pre-jump positions |
| `PiccoFinder.java` | Candidate addresses + parallel probe |

New package `app/src/main/java/dev/neosync/boox/jobs/`: `Job.java` (interface + listener), `SendMissingJob.java`, `SyncAllJob.java`, `FixCoversJob.java`, `JobRunner.java` (one-at-a-time, throttled updates). The bodies move from `MainActivity` (`uploadAll`, `syncAllPositions`, `runClean`).

UI (`activity/`): `MainActivity.java` (rebuilt home), `BookSheet.java` (BottomSheetDialog), `CoverSheet.java`, `ConnectionsActivity.java`, `SettingsActivity.java` (rebuilt), `MatchingActivity.java`, `ServerActivity.java`. Layouts: `activity_main.xml` (rewritten), `item_book.xml`, `sheet_book.xml`, `sheet_covers.xml`, `activity_connections.xml`, `activity_settings.xml` (rewritten), `item_setting.xml`, `activity_matching.xml`, `activity_server.xml`.

---

### Task 1: Remote position store, fed by sync

**Files:**
- Create: `app/src/main/java/dev/neosync/boox/library/RemotePosition.java`, `.../library/RemotePositionStore.java`
- Modify: `app/src/main/java/dev/neosync/boox/service/SyncService.java` (`readChangeFeed`, `resolve`), `.../kosync/SyncAll.java` (where it reads remote progress), `.../kosync/SyncActions.java` (`getProgress`)
- Test: `app/src/test/java/dev/neosync/boox/RemotePositionTest.java`

**Interfaces:**
- Produces: `final class RemotePosition { String document; double percentage; String device; String deviceId; long timestampMs; static RemotePosition from(KoSyncClient.Progress p); String toJson(); static RemotePosition fromJson(String s) /* null on bad input */; static RemotePosition newer(RemotePosition a, RemotePosition b) /* null-safe, larger timestampMs wins, tie → b */; boolean isCrossPoint() /* device null/empty or contains "crosspoint" ignoring case */ }`
- Produces: `class RemotePositionStore { RemotePositionStore(Context c); RemotePosition get(String hash); void record(RemotePosition p) /* keeps newer() of stored and p; also under each alias */; void recordAll(String hash, List<String> aliases, RemotePosition p) }`. SharedPreferences name `remote_positions`.

- [ ] **Step 1: Write failing tests** `RemotePositionTest`:
  - `jsonRoundTrip`: a position (doc "abc", 0.734, "CrossPoint", "dev1", 1700000000000L) survives `toJson`/`fromJson` with equal fields.
  - `fromJsonRejectsGarbage`: `fromJson("{")` and `fromJson(null)` return `null`.
  - `newerKeepsLatestTimestamp`: `newer(a@1000, b@2000) == b`, `newer(a@3000, b@2000) == a`, `newer(null, b) == b`, `newer(a, null) == a`.
  - `fromProgressConvertsSeconds`: `Progress{timestampSec=1700000000}` gives `timestampMs == 1700000000000L`.
  - `crossPointDetection`: device "CrossPoint" → true, "" → true, "KOReader" → false.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*RemotePositionTest*'`. Expected: FAIL (class missing).
- [ ] **Step 3: Implement `RemotePosition` and `RemotePositionStore`.** Use `org.json` (already used by `KoSyncClient`).
- [ ] **Step 4: Record in `SyncService.readChangeFeed`.** For every change not from this `deviceId`, call `remoteStore.recordAll(change.document, change.aliases, RemotePosition.from(change))` **before** the `book == null` `continue`. Do the same with the result in `resolve` (after a successful `getProgress`), in `SyncActions.getProgress` callers inside `SyncAll`, and in the manual check path. Apply logic stays unchanged.
- [ ] **Step 5: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 6: Commit** `git commit -am "Record remote positions by document hash for the library view"` (add the new files first).

### Task 2: Sync problem type and alarm rule

**Files:**
- Create: `.../library/SyncProblem.java`
- Modify: `.../kosync/SyncSettings.java` (`recordSyncError`, `clearSyncError`, `recordSyncSuccess`; new getters), `.../service/SyncService.java` (`resolve`, `push`, `backOff`, `documentHash` call sites), `.../kosync/SyncAll.java`
- Test: `app/src/test/java/dev/neosync/boox/SyncProblemTest.java`

**Interfaces:**
- Produces: `final class SyncProblem { enum Type { NONE, NO_INTERNET, AUTH, SERVER_UNREACHABLE, RATE_LIMITED, OTHER } static Type classify(KoSyncClient.Error e, boolean wifiHasInternet); static boolean isAlarm(long firstFailureMs, int consecutiveFailures, long nowMs) /* true when firstFailureMs>0 && (now-first >= 300000 || consecutive >= 2) */ }`
- Produces on `SyncSettings`: `void recordSyncError(String message, SyncProblem.Type type)` (the old one-arg form delegates with `OTHER`), `SyncProblem.Type getProblemType()`, `long getFirstFailureMs()`, `int getConsecutiveFailures()`. `clearSyncError()` and `recordSyncSuccess()` reset all three. Keys: `problem_type`, `first_failure_ms`, `consecutive_failures`.

- [ ] **Step 1: Write failing tests** `SyncProblemTest`:
  - `classifyMapsErrors`: `AUTH_FAILED→AUTH`; `NETWORK_ERROR` with `wifiHasInternet=false`→`NO_INTERNET`, with `true`→`SERVER_UNREACHABLE`; `SERVER_ERROR→SERVER_UNREACHABLE`; `RATE_LIMITED→RATE_LIMITED`; `JSON_ERROR→OTHER`; `OK→NONE`.
  - `singleFreshFailureIsNotAlarm`: `isAlarm(now-1000, 1, now) == false`.
  - `twoFailuresIsAlarm`: `isAlarm(now-1000, 2, now) == true`.
  - `fiveMinutesIsAlarm`: `isAlarm(now-300000, 1, now) == true`.
  - `noFailureIsNotAlarm`: `isAlarm(0, 0, now) == false`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*SyncProblemTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `SyncProblem` and the `SyncSettings` additions. `recordSyncError` sets `first_failure_ms` only when it is 0, and increments `consecutive_failures`.
- [ ] **Step 4: Pass types at call sites.** In `SyncService`/`SyncAll`, pass `SyncProblem.classify(result.error, hasInternet)`. Put the internet check (`NetworkCapabilities.NET_CAPABILITY_VALIDATED`, as `MainActivity.refreshNetworkHints` does today) in one static helper, `SyncProblem.wifiHasInternet(Context)` (the only Android-dependent method; keep it out of the tested code paths).
- [ ] **Step 5: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 6: Commit** `"Record sync problem type and first failure time"`.

### Task 3: Titles, authors and cover thumbnails

**Files:**
- Create: `.../library/TitleCleaner.java`, `.../library/BookMeta.java`, `.../library/BookMetaCache.java`
- Test: `app/src/test/java/dev/neosync/boox/TitleCleanerTest.java`

**Interfaces:**
- Consumes: `EpubCover.titleAndAuthor(File)` and `EpubCover.check(File)` (existing).
- Produces: `final class TitleCleaner { static String fromFileName(String fileName) }`; `final class BookMeta { final String title; final String author /* may be "" */; final boolean hasCover; final boolean wouldShowBlack; }`; `class BookMetaCache { BookMetaCache(Context c); BookMeta get(File book) /* blocking; call off the UI thread */; android.graphics.Bitmap thumbnail(File book, int widthPx, int heightPx) /* null when none */ }`. Cache key: absolute path + `lastModified()` + length. Metadata in SharedPreferences `book_meta`; thumbnails as JPEG in `getCacheDir()/thumbs/`.

- [ ] **Step 1: Write failing tests** `TitleCleanerTest`:
  - `stripsExtension`: `"republic.epub"` → `"republic"`.
  - `underscoresBecomeSpaces`: `"platon_banquet_0.epub"` → `"platon banquet 0"`.
  - `stripsDomainParenthetical`: `"Fictions (Jorge Luis Borges) (z-library.sk, 1lib.sk, z-lib.sk).epub"` → `"Fictions (Jorge Luis Borges)"`.
  - `keepsNonDomainParenthetical`: `"Dune (Frank Herbert).epub"` → `"Dune (Frank Herbert)"`.
  - `trimsTruncationUnderscore`: `"Sufism An Introduction to t_ (z-library.sk, 1lib.sk, z-lib.sk).epub"` → `"Sufism An Introduction to t…"`.
  - `emptyFallsBackToFileName`: `".epub"` → `".epub"`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*TitleCleanerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `TitleCleaner.fromFileName`.** Remove the last extension. Repeatedly remove a trailing `\s*\([^()]*\)` group when **every** comma-separated token inside it matches `[\w-]+(\.[\w-]+)+`. Turn a trailing `_` into `…`. Replace the remaining `_` with spaces. Trim. If the result is empty, return the input.
- [ ] **Step 4: Implement `BookMeta`/`BookMetaCache`.** For EPUBs, use `titleAndAuthor`, falling back to `TitleCleaner` when empty. `wouldShowBlack = !check(file).hasUsableCover` (use whatever field `EpubCover.Check` exposes for that today). For non-EPUBs, use `TitleCleaner` with no cover. Decode the thumbnail from the cover image entry with `BitmapFactory` and `inSampleSize`.
- [ ] **Step 5: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 6: Commit** `"Add book titles, authors and cover thumbnails for the library"`.

### Task 4: Library model, row status, filters and paging

**Files:**
- Create: `.../library/LibraryEntry.java`, `.../library/RowStatus.java`, `.../library/LibraryModel.java`, `.../library/LibraryPager.java`
- Test: `app/src/test/java/dev/neosync/boox/LibraryModelTest.java`, `.../LibraryPagerTest.java`, `.../RowStatusTest.java`

**Interfaces:**
- Consumes: `BookSyncStatus.of(...)` (existing), `RemotePosition` (Task 1).
- Produces:
  - `final class LibraryEntry { String key /* NFC-normalised file name */; String booxPath /* null if not here */; String piccoPath /* null if not on Picco */; boolean isEpub; double booxPct /* -1 unknown */; double piccoPct /* -1 unknown */; long booxLastMs; RemotePosition remote /* may be null */; BookSyncStatus syncStatus; RowStatus status; long activityMs; String title; String author; }`
  - `enum RowStatus { SAME_PLACE, READ_FURTHER_HERE, WAITING_TO_SYNC, PICCO_AHEAD, NOT_ON_PICCO, ONLY_ON_PICCO, NOT_SYNCED_YET, NOT_OPENED, BOOX_ONLY_TYPE; static RowStatus of(LibraryEntry e, boolean piccoConfigured, boolean syncing) }`
  - `enum Filter { ALL, READING, ONLY_HERE, ONLY_ON_PICCO }`
  - `final class LibraryModel { static List<LibraryEntry> merge(List<BooxBook> boox, List<String> piccoPaths, Map<String, RemotePosition> remoteByKey, boolean piccoConfigured, boolean syncing); static List<LibraryEntry> filter(List<LibraryEntry> all, Filter f, String query); static int count(List<LibraryEntry> all, Filter f); }` where `final class BooxBook { String path; double pct; long lastAccessMs; BookSyncStatus status; String title; String author; }` is a nested static class (the activity builds it from `OnyxMetadataRepository.Book` + `BookMetaCache` + `SyncStateStore`).
  - `final class LibraryPager { LibraryPager(int total, int pageSize); int pageCount(); int clamp(int page); int from(int page); int to(int page) /* exclusive */ }`

Rules to pin (spec §5.1, §9.2, §9.3):
- Merge key: file name after the last `/`, `java.text.Normalizer` NFC.
- `piccoPct`: when `syncStatus == SYNCED` → `booxPct`; else `remote.percentage` if `remote != null`; else `-1`. Only on Picco → `remote` or `-1`.
- `RowStatus.of`, checked in this order:
  1. not EPUB and on Boox → `BOOX_ONLY_TYPE`;
  2. not on Boox → `ONLY_ON_PICCO`;
  3. Picco configured and not on Picco → `NOT_ON_PICCO`;
  4. `remote != null && remote.timestampMs > booxLastMs && syncStatus != SYNCED` → `PICCO_AHEAD`;
  5. `SYNCED` → `SAME_PLACE`;
  6. `CHANGED_HERE` → `syncing ? READ_FURTHER_HERE : WAITING_TO_SYNC`;
  7. `NEVER_SYNCED` → `NOT_SYNCED_YET`;
  8. otherwise → `NOT_OPENED`.
- `activityMs = max(booxLastMs, remote?.timestampMs ?: 0)`. Sort descending, ties by title.
- `READING` = `(booxPct > 0 && booxPct < 1) || (piccoPct > 0 && piccoPct < 1)`. `ONLY_HERE` = on Boox, not on Picco, `isEpub`. `ONLY_ON_PICCO` = not on Boox.
- Search: case-insensitive substring over title + author, accents folded (NFD with combining marks removed).

- [ ] **Step 1: Write failing tests.**
  - `LibraryModelTest`:
    - `mergesSameFileNameAcrossDevices` (Boox `/Books/a/Dune.epub` + Picco `books/Dune.epub` → 1 entry with both paths);
    - `accentSpellingsMergeIntoOneRow` (Boox `"Société.epub"` in NFC + Picco in NFD → 1 entry);
    - `piccoOnlyAndBooxOnlyKept`;
    - `syncedUsesBooxPctForPicco`;
    - `remoteUsedWhenNotSynced`;
    - `sortedByActivityNewestFirst`;
    - `filterReading`;
    - `filterOnlyHereExcludesPdf`;
    - `searchMatchesAuthorIgnoringAccents` (`"maire"` finds author `"Máire Roche"`);
    - `countMatchesFilter`.
  - `RowStatusTest`: one test per rule above, plus `pdfIsBooxOnlyType`, and `piccoAheadNotShownWhenSynced`.
  - `LibraryPagerTest`:
    - `pageCountRoundsUp` (19 items, 6 per page → 4);
    - `clampsPageWhenPageSizeGrows` (page 3 of 4, then pageSize 10 → `clamp(3) == 1`);
    - `emptyHasOnePage` (`pageCount()==1`, `from(0)==0`, `to(0)==0`).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*LibraryModelTest*' --tests '*RowStatusTest*' --tests '*LibraryPagerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** the four classes.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: pass.
- [ ] **Step 5: Commit** `"Add library model: merged two-device list, row status, filters, paging"`.

### Task 5: Suggestion picker

**Files:**
- Create: `.../library/Suggestion.java`, `.../library/SuggestionPicker.java`
- Test: `app/src/test/java/dev/neosync/boox/SuggestionPickerTest.java`

**Interfaces:**
- Consumes: `SyncProblem.Type` (Task 2).
- Produces:
  - `final class Suggestion { enum Kind { JOB, PROBLEM_NO_INTERNET, PROBLEM_AUTH, PROBLEM_SERVER, PROBLEM_PAUSED, SETUP_PICCO, SEND_MISSING, FIX_COVERS, UPDATE } final Kind kind; final int count; final String detail; String dismissKey() /* kind + ":" + count, or + version for UPDATE */ }`
  - `final class SuggestionPicker { static final class Input { boolean jobRunning; boolean autoSyncOn; SyncProblem.Type problem; boolean problemIsAlarm; boolean piccoConfigured; boolean piccoReachable; int missingOnPicco; String missingNames; long missingBytes; int blackCovers; String updateVersion; Set<String> dismissed; } static Suggestion pick(Input in) /* null = none */ }`

Priority (spec §5.1):
1. `JOB`;
2. `PROBLEM_PAUSED` when auto sync is off;
3. problem cards when `problemIsAlarm`: NO_INTERNET → `PROBLEM_NO_INTERNET`, AUTH → `PROBLEM_AUTH`, SERVER_UNREACHABLE/RATE_LIMITED/OTHER → `PROBLEM_SERVER`;
4. `SETUP_PICCO` when the Picco isn't configured;
5. `SEND_MISSING` when reachable && missing > 0;
6. `FIX_COVERS` when blackCovers > 0;
7. `UPDATE` when updateVersion != null.

Kinds 4–7 are skipped when their `dismissKey()` is in `dismissed`; problems and jobs can't be dismissed.

- [ ] **Step 1: Write failing tests:**
  - `jobBeatsEverything`
  - `pausedBeatsProblems`
  - `freshProblemNotShown` (`problemIsAlarm=false` → falls through)
  - `noInternetProblem`
  - `setupWhenNoAddress`
  - `unreachablePiccoWithCacheDoesNotAskForSetup` (configured, unreachable, missing=3 → not SETUP_PICCO and not SEND_MISSING)
  - `sendMissingOnlyWhenReachable`
  - `dismissedSendMissingReturnsWhenCountChanges` (dismissed "SEND_MISSING:3", count 4 → shown)
  - `problemsCannotBeDismissed`
  - `updateLast`
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*SuggestionPickerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: pass.
- [ ] **Step 5: Commit** `"Add suggestion picker for the home screen"`.

### Task 6: Undo a jump

**Files:**
- Create: `.../library/UndoRule.java`, `.../library/UndoStore.java`
- Modify: `.../kosync/SyncActions.java` (`applyRemote`: save before writing), `.../contentprovider/OnyxMetadataRepository.java` (only if a getter is needed to restore `extraAttributes`)
- Test: `app/src/test/java/dev/neosync/boox/UndoRuleTest.java`

**Interfaces:**
- Produces:
  - `final class UndoRule { static boolean available(long savedAtMs, String writtenProgress, String currentProgress, long remoteChangedAfterMs, long nowMs) }`: true when `savedAtMs > 0 && now - savedAtMs < 86400000 && currentProgress.equals(writtenProgress) && remoteChangedAfterMs <= savedAtMs`.
  - `class UndoStore { UndoStore(Context c); void save(String path, String prevProgress, long prevLastAccessMs, String prevExtraAttributes, String writtenProgress, long nowMs); Saved get(String path); void clear(String path); static final class Saved { String prevProgress; long prevLastAccessMs; String prevExtra; String writtenProgress; long savedAtMs; } }`. SharedPreferences `undo_jumps`.
  - `static boolean SyncActions.undoJump(Context c, OnyxMetadataRepository repo, UndoStore store, OnyxMetadataRepository.Book book)`: writes `prevProgress` back with `lastAccessMs = System.currentTimeMillis()` and restores the chapter position from `prevExtra`. It does **not** set `lastWrittenProgress`, so the watcher sees reading here and pushes it, and the Picco follows (spec §7.5). It clears the entry and returns false when nothing was written.

- [ ] **Step 1: Write failing tests** `UndoRuleTest`:
  - `availableRightAfterJump`
  - `expiresAfter24h` (`now - saved == 86400000` → false)
  - `goneWhenReadAfterJump` (current "60/300" ≠ written "50/300")
  - `goneWhenPiccoMovedAgain` (`remoteChangedAfterMs > savedAtMs`)
  - `noUndoWhenNothingWasWritten` (`savedAtMs == 0` → false)
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*UndoRuleTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** In `SyncActions.applyRemote`, call `UndoStore.save(...)` only **after** `writeProgress` returns true (so a failed write records nothing), using the `book` fields read before the write. The `remoteChangedAfterMs` input comes from `RemotePositionStore.get(hash).timestampMs`.
- [ ] **Step 4: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 5: Commit** `"Keep the place before each jump so it can be undone"`.

### Task 7: Find the Picco automatically

**Files:**
- Create: `.../library/PiccoFinder.java`
- Test: `app/src/test/java/dev/neosync/boox/PiccoFinderTest.java`

**Interfaces:**
- Consumes: `PiccoClient(String address).listDir("/")` or a lighter probe on `GET /api/files?path=/` (make a package-visible `PiccoClient.probe(int timeoutMs)` returning boolean if needed).
- Produces: `final class PiccoFinder { static List<String> candidates(String lastKnown, String gatewayIp, String ownIp) /* ordered, de-duplicated */; interface Prober { boolean isPicco(String address); } static String find(List<String> candidates, Prober prober, int threads, long deadlineMs) /* first hit in candidate order among those answering, null if none */ }`

Order: `lastKnown`, then `gatewayIp`, then all `x.y.z.1..254` of `ownIp`'s /24 except `ownIp`. Skip null/empty inputs. Use 32 threads, a 600 ms connect timeout per probe, and an overall deadline of 10000 ms. The Android caller gets the gateway and own IP from `WifiManager.getDhcpInfo()` / `ConnectivityManager.getLinkProperties`.

- [ ] **Step 1: Write failing tests:**
  - `lastKnownThenGatewayThenSubnet` (`candidates("192.168.1.244","192.168.1.1","192.168.1.20")` starts `[.244, .1, .2, .3…]`, size 254, no `.20`, no duplicates)
  - `nullsSkipped`
  - `findReturnsFirstHit` (fake prober true only for ".57" → ".57")
  - `findReturnsNullWhenNoneAnswer`
  - `findHonoursDeadline` (prober sleeps 200 ms each, deadline 300 ms → returns within 1000 ms)
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*PiccoFinderTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** with an `ExecutorService`. Cancel the remaining tasks on the first hit or at the deadline.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: pass.
- [ ] **Step 5: Commit** `"Find the Picco on the local network"`.

### Task 8: Book comparison (sheet verdict and action)

**Files:**
- Create: `.../library/BookComparison.java`
- Test: `app/src/test/java/dev/neosync/boox/BookComparisonTest.java`

**Interfaces:**
- Consumes: `LibraryEntry` (Task 4).
- Produces: `final class BookComparison { enum Verdict { SAME, OTHER_MORE_RECENT, HERE_MORE_RECENT, NOTHING_ONLINE, NOT_CHECKED } enum Action { NONE, JUMP_TO_REMOTE, SEND_PLACE, SEND_FILE } final Verdict verdict; final Action primary; final Action secondary /* the "instead" link: SEND_PLACE or JUMP_TO_REMOTE or NONE */; static BookComparison of(LibraryEntry e, boolean liveCheckOk, boolean piccoConfigured) }`

Rules:
1. Not on Boox → `NOT_CHECKED`/`NONE`/`NONE`.
2. Picco configured && not on Picco && isEpub → primary `SEND_FILE`, plus the position verdict.
3. `!liveCheckOk` → `NOT_CHECKED`, primary `NONE` (secondary also `NONE`).
4. `remote == null` → `NOTHING_ONLINE`, primary `SEND_PLACE`.
5. Positions within 0.5 percentage points (|Δ| < 0.005) → `SAME`, `NONE`, secondary `NONE`.
6. `remote.timestampMs > booxLastMs` → `OTHER_MORE_RECENT`, primary `JUMP_TO_REMOTE`, secondary `SEND_PLACE`.
7. Otherwise → `HERE_MORE_RECENT`, primary `SEND_PLACE`, secondary `JUMP_TO_REMOTE`.

- [ ] **Step 1: Write failing tests**, one per rule:
  - `piccoOnlyHasNoActions`
  - `missingFileOffersSend`
  - `failedLiveCheckOffersNoAction`
  - `nothingOnlineOffersSendPlace`
  - `samePlaceWithinHalfPercent`
  - `remoteNewerOffersJump`
  - `hereNewerOffersSend`
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*BookComparisonTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: pass.
- [ ] **Step 5: Commit** `"Add the book sheet's comparison rules"`.

### Task 9: Jobs out of MainActivity

**Files:**
- Create: `.../jobs/Job.java`, `.../jobs/JobRunner.java`, `.../jobs/SendMissingJob.java`, `.../jobs/SyncAllJob.java`, `.../jobs/FixCoversJob.java`
- Modify: `.../activity/MainActivity.java` (remove `uploadAll`, `syncAllPositions`, `runClean` bodies; call the jobs)
- Test: `app/src/test/java/dev/neosync/boox/JobRunnerTest.java`

**Interfaces:**
- Produces:
  - `interface Job { String title(Context c); void run(Listener l) throws InterruptedException /* checks Thread.interrupted() between books */; interface Listener { void progress(int done, int total, String currentKey, String rowState /* "sent","sending","waiting","fetched","sent_place","failed" */); void finished(String resultSentence); } }`
  - `final class JobRunner { JobRunner(Executor ui, long throttleMs /* 3000 */, Clock clock); boolean start(Job j, Job.Listener uiListener) /* false if one is running */; void stop(); boolean isRunning(); }`. Forwards `progress` at most every `throttleMs` (the last one always delivered before `finished`).
  - Each job's constructor takes what the old method used (`PiccoClient`, folder, file list / `CleanPlan` + `includePiccoOnly`). Move the `CleanPlan` class to `jobs/`. Result sentences reuse the existing `send_missing_done`, `sync_all_done`/`sync_all_stopped`, and `clean_done`/`clean_lost_picco` strings.
- [ ] **Step 1: Write failing tests** `JobRunnerTest` with a fake `Clock` and a direct executor:
  - `throttlesProgress` (10 progress calls within 1000 ms → 1 delivered, then the final one before finished)
  - `secondStartRefused`
  - `stopInterrupts` (a job looping on `Thread.interrupted()` ends; `finished` called once)
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*JobRunnerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `JobRunner`, then move the three job bodies** unchanged in behaviour (rate-limit stops, "lost the Picco" handling, positions-first for covers).
- [ ] **Step 4: Run** `./gradlew assembleDebug testDebugUnitTest`, then install and run "Send missing books" once on the device as today. Expected: same confirmation and result as before.
- [ ] **Step 5: Commit** `"Move long jobs out of MainActivity behind a throttled JobRunner"`.

### Task 10: New home screen

**Files:**
- Rewrite: `app/src/main/res/layout/activity_main.xml`, `.../activity/MainActivity.java`
- Create: `app/src/main/res/layout/item_book.xml`, `res/drawable/chip_bg.xml`, `chip_bg_alarm.xml`, `suggest_bg.xml`, `filter_on.xml`, `filter_off.xml`, `track_bg.xml`
- Modify: `res/values/strings.xml`

**Interfaces:**
- Consumes: Tasks 1–5, 7, 9; `PiccoLibraryStore`, `UpdateChecker`, `SyncSettings`.
- Produces: `MainActivity` extras/hooks used later: `void openBook(LibraryEntry e)` (Task 11 fills it in), `void reload()` (re-runs the merge off the UI thread).

Build per spec §5.1 and mockup 1:
- Header with the gear only.
- Two chips (texts and states from the spec's table; alarm = `chip_bg_alarm` black with white text when `SyncProblem.isAlarm`). Each opens `ConnectionsActivity` (Task 12; until then a stub that finishes).
- Suggestion slot: renders `SuggestionPicker.pick`.
  - Buttons: Send → existing send-missing confirmation then `SendMissingJob`; Fix → `CoverSheet` (Task 11); Get it → existing update flow; Wi-Fi → existing `openWifiPicker`; Sign in → `SignInActivity`; Try again → existing `retrySync`; Turn on → `settings.setAutoSyncEnabled(true)`; Find → `PiccoFinder` then refresh.
  - ✕ adds `dismissKey()` to the SharedPreferences set `dismissed_suggestions`.
  - While a job runs, the slot shows the progress card (title, "N of M", bar, current book, Stop), and affected rows show the job's `rowState`. On `finished`, the card shows the result sentence and hides after 10000 ms or on tap.
- Filter chips (`All N` etc.; hide `Only on Picco` when not configured; remember the choice in `kosync_settings` key `library_filter`). The search icon toggles an `EditText` that filters as you type.
- Paged rows (`item_book.xml`: 34×48dp cover, title/author/status, two mini bars with %). The page size is computed from the list container's height ÷ row height on layout, and recomputed on `onSizeChanged`. `‹ n / m ›` buttons; `KEYCODE_PAGE_DOWN/UP` and volume keys turn pages.
- Empty state (spec §5.7) when no entry has a position.
- Remove the old views: Latest book card, refresh icons, bulk buttons, hotspot box, footer.

- [ ] **Step 1: Write the layouts and strings** (row status strings: `row_same_place` "✓ Same place · %1$s", `row_read_further` "Read further here · syncs soon", `row_waiting` "Read further here · waiting to sync", `row_picco_ahead` "Picco is ahead · tap to jump", `row_not_on_picco` "Not on the Picco", `row_only_on_picco` "Only on the Picco", `row_not_synced` "Not synced yet", `row_not_opened` "Not opened yet", `row_boox_only_type` "On this Boox only (%1$s)").
- [ ] **Step 2: Rebuild `MainActivity`.** Keep the existing `refreshPiccoLibrary` network logic and `SyncSettings` listener; replace the rendering.
- [ ] **Step 3: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 4: Verify on the Go:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`, open the app, `adb exec-out screencap -p > home.png`. Expected: matches mockup 1. The library lists the real books with both bars, the chips show the real state, and there's no horizontal overflow. Turn the Picco's File Transfer on and off and check that the chip changes.
- [ ] **Step 5: Commit** `"Rebuild home as the two-device library"`.

### Task 11: Book sheet and cover sheet

**Files:**
- Create: `.../activity/BookSheet.java`, `.../activity/CoverSheet.java`, `res/layout/sheet_book.xml`, `res/layout/sheet_covers.xml`
- Modify: `.../activity/MainActivity.java` (`openBook`), `res/values/strings.xml`

**Interfaces:**
- Consumes: `BookComparison.of` (Task 8), `UndoRule`/`UndoStore`/`SyncActions.undoJump` (Task 6), `FixCoversJob` + `CleanPlan` (Task 9), `SyncActions.getProgress`/`applyRemote`/`uploadLocal` (existing), and the existing single-book send (`sendBookToPicco` logic, moved into `SendMissingJob` with a one-file list).
- Produces: `static void BookSheet.show(MainActivity a, LibraryEntry e)`, `static void CoverSheet.show(MainActivity a, CleanPlan plan)`.

Book sheet per spec §5.2 / mockup 2:
- Header and comparison box. The live check runs on open, showing "Checking…", and records into `RemotePositionStore`.
- Verdict strings: `sheet_same` "Same place.", `sheet_other_recent` "You read more recently on the %1$s.", `sheet_here_recent` "You read more recently here.", `sheet_nothing_online` "Nothing saved online yet.", `sheet_not_checked` "Couldn't check just now." The device label is "Picco" when `remote.isCrossPoint()`, else `remote.device`.
- Primary/secondary from `BookComparison`. Button texts: `sheet_jump` "Jump to the %1$s's place (%2$s)", `sheet_send_place` "Send this place to the Picco (%1$s)", `sheet_send_file` "Send to Picco". Links: "Keep this Boox's place instead" / "Use the Picco's place instead", "Send book file again", "Add a cover" (only when `wouldShowBlack`), "Open in Neo Reader" (`Intent.ACTION_VIEW` on the file with package `SyncActions.NEOREADER_PACKAGE`), and "Undo jump" when `UndoRule.available(...)`.
- Results appear inline in the sheet. The undo result text: "Back to %1$s. The Picco goes back too next time you choose Sync Progress there."
- When `applyRemote` returns null because the book is open: "Close the book in Neo Reader first, then try again."

Cover sheet per spec §5.6 / mockup 7: before → after rows (max 3, then "+ N more"), the warning box only when some are on the Picco, a Picco-only checkbox unticked by default, the primary "Add N covers" starting `FixCoversJob`, and the backup note.

- [ ] **Step 1: Layouts and strings.**
- [ ] **Step 2: Implement both sheets** with `BottomSheetDialog`, expanded state, animations off (`window.setWindowAnimations(0)`).
- [ ] **Step 3: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 4: Verify on the Go:**
  - Read a few pages of a book on the Picco and Sync Progress there. Open its sheet on the Boox: it shows "You read more recently on the Picco". Jump, then open the book in Neo Reader: it's at the Picco's place.
  - Undo, then on the Picco choose Sync Progress: it goes back.
  - Open a Picco-only book's sheet: no buttons.
  - Screenshot both sheets.
- [ ] **Step 5: Commit** `"Add the book sheet with jump, send and undo, and the cover sheet"`.

### Task 12: Connections screen

**Files:**
- Create: `.../activity/ConnectionsActivity.java`, `res/layout/activity_connections.xml`
- Modify: `AndroidManifest.xml`, `res/values/strings.xml`

**Interfaces:**
- Consumes: `SyncSettings` problem getters (Task 2), `PiccoFinder` (Task 7), `SyncAllJob` via `JobRunner` (Task 9; run it from here and return to home so the progress card shows), existing `openWifiPicker` (move it to a static helper in `Permissions` or a new `WifiHelper`).

Build per spec §5.3 / mockup 3:
- Diagram card. The Wi-Fi line is dashed with "not connected" when unreachable.
- Server card: one sentence plus the fix button for the problem type, and the "Sync all books now" link.
- Picco card: state, the 3 steps when unreachable, "Look for the Picco" (runs `PiccoFinder`, shows "Looking…", then saves the address and refreshes) and "Wi-Fi settings", and the "Last seen at <ip> · enter address by hand" link (an `AlertDialog` with an `EditText`).
- The hotspot explanation shows when `SyncProblem.wifiHasInternet` is false.

- [ ] **Step 1: Layout, strings, manifest entry.**
- [ ] **Step 2: Implement.** The chips in `MainActivity` open it.
- [ ] **Step 3: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 4: Verify on the Go:**
  - With File Transfer open on the Picco, clear the address, then tap "Look for the Picco". It finds the Picco within 10 s and the chip turns "Picco connected".
  - Join the Picco's hotspot: the hotspot text shows.
  - Screenshot.
- [ ] **Step 5: Commit** `"Add the Connections screen with Picco auto-discovery"`.

### Task 13: Settings regrouped, Advanced pages, notification

**Files:**
- Rewrite: `res/layout/activity_settings.xml`, `.../activity/SettingsActivity.java`
- Create: `res/layout/item_setting.xml`, `.../activity/MatchingActivity.java` + `activity_matching.xml`, `.../activity/ServerActivity.java` + `activity_server.xml`
- Modify: `.../activity/SignInActivity.java` (remove the "Advanced: use another sync server" toggle; keep the server field reachable only from `ServerActivity`), `.../service/SyncService.java` (`buildNotification`), `AndroidManifest.xml`, `res/values/strings.xml`

**Interfaces:**
- Consumes: `SyncSettings` (existing setters), `UpdateChecker`, `ProblemReport`, `CoverFixer.backupDir()`.
- Produces: `SyncService.updateNotification(String text)`, called after each successful sync with `"%1$s synced · %2$s"` (title from `BookMetaCache`, falling back to `TitleCleaner`), and with `"Positions not syncing · tap to fix"` while `SyncProblem.isAlarm`. The notification's content intent opens `MainActivity`.

Rows per spec §5.4 / mockup 4:
- **Sync:** Automatic sync (switch), Account.
- **Picco:** Picco (→ `ConnectionsActivity`), Folder for new books (value + Change → existing folder chooser).
- **Help:** Setup guide, Report a problem (a small page or dialog with today's `report_hint` and the copy action).
- **Advanced:** Book matching → `MatchingActivity` (radio pair + warning "Use the same mode on both devices, or books stop matching."), Sync server → `ServerActivity` (field + https rule from `SignInActivity`; saving re-runs sign-in validation), Cover backups (path + file count, no action).
- **About:** version/update row + download button when available, footer line, Buy me a coffee.

- [ ] **Step 1: Layouts and strings.**
- [ ] **Step 2: Implement** the activities and the notification text.
- [ ] **Step 3: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 4: Verify on the Go:** each row opens the right place; switching matching mode persists; turning automatic sync off shows "Sync paused · Turn on" on home; the notification shows the last synced book. Screenshot Settings.
- [ ] **Step 5: Commit** `"Regroup Settings, add Advanced pages and a useful notification"`.

### Task 14: Clean-up, README, full-device pass

**Files:**
- Modify: `res/values/strings.xml` (delete unused strings: run `./gradlew lint` and remove those flagged `UnusedResources`), `README.md` ("Using it", "The Picco library" sections rewritten for the new screens; Privacy section unchanged), delete unused drawables (`num_bg.xml` etc. only if lint flags them)

- [ ] **Step 1: Run** `./gradlew lint`. Remove the unused resources it reports from this redesign.
- [ ] **Step 2: Update the README** to describe home, the book sheet, Connections and Settings in the same tone.
- [ ] **Step 3: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: pass.
- [ ] **Step 4: Full pass on the Go and the Picco.** Run all 8 mockup states and compare screenshots against `docs/superpowers/specs/2026-11-11-ux-redesign-mockups/`. Check:
  - no horizontal overflow, and at most one black button per screen;
  - pages turn with one refresh;
  - a long title ellipsizes.
- [ ] **Step 5: Commit** `"Update README for the redesign and drop unused resources"`.
