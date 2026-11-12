# Play Console answers

Copy-paste text for the NeoSync forms in Play Console, written to match
[privacy.md](privacy.md). Keep both in step when the app changes.

Privacy policy URL:
`https://github.com/TryingVlad/neosync/blob/main/docs/privacy.md`

## App content → Sensitive permissions → All files access (MANAGE_EXTERNAL_STORAGE)

**Core feature:** Sync and file management between Neo Reader on a Boox
e-reader and a CrossPoint (KOReader-compatible) e-reader.

**Form field (500 characters max), as submitted:**

> Syncing reading positions for Boox e-readers. On current Boox firmware, Neo Reader saves and reopens an EPUB by chapter position, given only as a raw file path. To apply a position synced from the user's other e-reader, NeoSync must read that EPUB's chapter table; without it the synced position isn't applied and outgoing positions are several percent off. SAF and MediaStore can't map those paths or index EPUBs. Explained in-app before the toggle. Books never leave the user's devices.

**Usage:** Core functionality only.

**Longer explanation (for appeals or follow-up questions):**

> NeoSync syncs reading positions for the books in Neo Reader, the Boox
> e-reader's built-in reading app. Neo Reader's library is whatever EPUB and
> PDF files the user keeps anywhere in shared storage. Neo Reader reports a
> book only as an absolute file path, so NeoSync must open the same file at
> that path. NeoSync uses the access to:
> 1. read each EPUB's chapter list (its spine), turning Neo Reader's
>    chapter-based position into the exact position other readers use;
> 2. compute the KOReader document fingerprint (partial MD5 of the file), when
>    the user chooses "match by file contents";
> 3. send book files the user selects to their own e-reader over local Wi-Fi;
> 4. show cover thumbnails in the library list;
> 5. add a missing cover to an EPUB, only when the user taps "Fix missing
>    covers", after first copying the original to Books/.neosync-backup.
>
> The Storage Access Framework doesn't fit: books are spread across arbitrary
> folders, Neo Reader hands over raw paths rather than content URIs, and
> MediaStore doesn't index EPUBs. The onboarding screen explains each use
> before the user is sent to the system settings toggle. Without it, synced
> positions can't be applied to EPUBs (Neo Reader on current firmware
> reopens from a chapter position NeoSync can only compute from the file),
> and outgoing positions are several percent off. Book files
> are never uploaded to any server.

**Video:** record on the Boox: onboarding step 5 (the explanation) → tap
Allow → system toggle → back in NeoSync, open a book's sheet to show the
chapter-accurate position, then run a send to the Picco.

## App content → Foreground service permissions (specialUse)

> NeoSync runs one foreground service with a permanent notification while
> automatic sync is on. It watches Neo Reader's on-device database (a
> ContentObserver plus a one-minute check) and pushes reading-position
> changes to the user's KOReader sync server as the user reads. It restarts
> after reboot, so sync keeps working without the user reopening the app. It
> is specialUse rather than dataSync because it runs for as long as the user
> reads, not as a bounded transfer, and dataSync services can no longer start
> at boot. The user can turn automatic sync off in the app, which stops the
> service.

User impact if deferred: reading positions would be lost or arrive late
when the user switches devices mid-session.

## App content → Data safety

- **Does the app collect or share any of the required user data types?** Yes.
- **Is all user data encrypted in transit?** No. The sync server address is
  user-editable (the default is HTTPS), and Picco transfers are local HTTP.
- **Can users request deletion?** Data stays on the device and is removed
  when the app is uninstalled. The developer holds none. Server-side data is
  kept by the sync server the user chose.

Declare these as **collected** (sent off the device to the sync server the
user configures), **not shared**, **required for app functionality**, and
**not processed ephemerally**:

| Data type | Sent | Purpose |
|---|---|---|
| Personal info → User IDs | Sync username | App functionality, Account management |
| App activity → Other user-generated content / App interactions | Reading position per book (hashed book ID, progress, percentage) | App functionality |
| Device or other IDs | App-specific ANDROID_ID, so the server can tell devices apart | App functionality |

Not collected: location, contacts, files and docs (book files go only to the
user's own device on the local network), photos, audio, health, financial
info, messages, web history, crash logs, diagnostics. The problem report is
copied by the user and never sent by the app.

Note: Play counts data sent to a server the user enters as "collected" even
though the developer doesn't run that server. Declaring it is the
transparent choice.

## Other forms

- **Ads:** No ads.
- **Target audience:** 18+ (or 13+). Not designed for children.
- **Content rating:** Utility, no user-generated content shared between users.
- **Government / financial / health / news app:** No.
