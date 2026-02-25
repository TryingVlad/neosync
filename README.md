# Onyx Progress Sync
Syncs KOReader reading progress into the Onyx Boox library so progress stays visible in the Onyx library.

## Features
- Updates Onyx metadata with in-flow page/total pages.
- Marks the book as reading, unopened or finished and updates last access timestamp.
- Debounced syncing on page turns plus immediate sync on lifecycle events.

## Requirements
- KOReader on an Android-based Onyx device.

## How It Works
The plugin communicates with a small companion app running as a background service. This is necessary because KOReader's JNI bridge is not stable enough to write to the Onyx content provider directly from Lua. The companion app has no UI — it runs silently in the background and handles all content provider writes on behalf of the plugin.

## Installation

### 1. Companion App
1. Download and install `onyx-sync.apk` from the [latest release](../../releases/latest).
2. Once installed, open the app **once** — nothing will appear on screen, this is expected. It starts the background service and can be closed immediately.
3. Grant the following permissions (usually prompted on first launch, or find them in **Settings → Apps → Onyx Sync**):
   - **Autostart** — allows the service to start automatically on boot.
   - **Background activity** — keeps the service running while KOReader is in use.
   - **Battery optimization exemption** — prevents Android from killing the service. Look for "Battery" or "Power" in the app settings and set it to **Unrestricted** or **No restrictions**.
4. Make sure the app is **not frozen**. Go to **Settings → Freeze Settings**, find Onyx Sync and ensure it is not in the frozen list. Freezing the app will prevent it from running in the background entirely, which will break syncing.

### 2. Plugin
1. Create the folder `koreader/plugins/onyx_sync.koplugin` on your device.
2. Copy `main.lua` and `_meta.lua` into the folder.
3. Restart KOReader.
4. Ensure the plugin is enabled in KOReader settings.

## When It Syncs
- On page updates (debounced, ~3s).
- When a document is closed.
- When settings are saved.
- When the app is suspended (sent to background).
- When end of book is reached.

## Notes
- Only runs on Android devices (no effect on other platforms).
- Only tested on Boox Go 7.
- Completion is detected from KOReader summary status or when the last page in the main flow is reached.
- The companion app has no UI. Launching it once is only needed to initialize the background service.

## Bulk Update
The plugin adds a menu entry under **Onyx Progress Sync → Update all books in Onyx library** in KOReader's FILE BROWSER menu (only visible in the library).

This scans the current folder and pushes progress for every book, so the Onyx library shows up-to-date percentages and reading statuses without having to open each book individually.

![menu](__/asset/librarymenudetail.png__)

## ADB Cheat-Sheet

**Query all Onyx metadata (reading progress)**
```sh
adb shell content query --uri content://com.onyx.content.database.ContentProvider/Metadata
```

**Query Onyx reading statistics**
```sh
adb shell content query --uri content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel
```

**Deploy the plugin during development**
```sh
adb push ./main.lua /sdcard/koreader/plugins/onyx_sync.koplugin/main.lua
```

**View plugin logs**
```sh
adb logcat -s KOReader:*
```

## License
MIT. See [LICENSE](__LICENSE__).