# NeoSync privacy policy

_Last updated: 12 November 2026_

NeoSync syncs reading positions between Neo Reader on a Boox e-reader and
other KOReader-compatible readers, such as a CrossPoint Picco. It is made by
one independent developer (TryingVlad). This page lists everything the app
reads, stores and sends.

**The short version:** the developer receives no data from the app. There
are no accounts with the developer, no analytics, no ads and no crash
reporting. Your book files never leave your own devices.

## What stays on your Boox

- **Your sync username and the MD5 hash of your sync password.** The KOReader
  sync protocol uses that hash as its credential. Your password itself is
  not stored.
- **Reading positions and book details from Neo Reader**, such as title,
  file path and progress, read from Neo Reader's on-device database.
- **Small caches**, such as cover thumbnails, kept in the app's private
  storage.

Uninstalling NeoSync deletes all of this.

## What is sent, and where

| Destination | What | When |
|---|---|---|
| **The sync server you choose** (default: `sync.crosspointreader.com`, run by a third party, not by the developer) | Your sync username and password hash; for each book, an identifier (an MD5 hash of the file name, or of parts of the file if you choose "by file contents" matching; never the title or the file), your position and percentage; the device name "NeoSync" and an app-specific device ID (Android's `ANDROID_ID`), so the server can tell your devices apart | Whenever a reading position changes, and when you sync by hand |
| **Your Picco, on your own Wi-Fi** | Book files you choose to send; requests for the Picco's book list and reading positions. This is plain HTTP, because that is what CrossPoint's file-transfer server speaks, and it never leaves your local network | When you send books or sync with the Picco |
| **GitHub** (`api.github.com`) | One request for the latest NeoSync version. It contains only the app version | At most once a day, and **only** for copies installed from GitHub. Copies installed from Google Play never make this request |

The sync server's own privacy terms cover what it stores. You can run your
own KOReader sync server and enter its address in NeoSync instead.

## Permissions

- **Access to all files (optional).** NeoSync reads your book files to find
  each EPUB's chapter list (so positions are exact), to show covers, to send
  books to your Picco, and to fingerprint books if you choose "by file
  contents" matching. It changes a book file only when you tap **Fix missing
  covers**, and it first copies the original to `Books/.neosync-backup`.
  Without this access, sync still works by file name.
- **Notifications.** A permanent notification keeps background sync running.
- **Run at startup, and a foreground service.** These restart sync after the
  Boox reboots.
- **Close other apps' background processes.** NeoSync closes Neo Reader's
  cached process after applying a synced position, because Neo Reader only
  reads a new position when it starts.
- **Network.** Used to reach the sync server and your Picco.

## Problem reports

The **Copy problem report** button builds a text report that you copy and
paste yourself, wherever you choose. NeoSync never sends it. The report holds
your device model, firmware, app version, a few settings and NeoSync's recent
log. It leaves out your password hash, username, book titles, file names,
paths and book identifiers.

## Children

NeoSync is not directed at children and collects nothing from anyone.

## Changes and contact

Changes to this policy are published on this page, and the full history is
in the repository. Questions: open an issue at
<https://github.com/TryingVlad/neosync/issues>.
