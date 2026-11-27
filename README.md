# Stride

A Strava-style activity tracker for Android that runs entirely on your phone. There's **no account, no server and no uploads**. You can record runs, rides, walks and hikes (14 sports), look at maps and stats, plan routes, make your own segments, track PRs and goals, text safety check-ins to people you choose, and export everything to other apps. It can also make **PNG images and animated MP4s** of your route and stats for Instagram Stories and Reels.

> Stride is an original app. It doesn't use Strava's name, logo, colours or any of its assets, and it doesn't connect to Strava.
> It does export standard GPX/TCX files, and Strava, Garmin Connect, Komoot and others can import those.

---

## Build & install

You need **Android Studio** (Ladybug or newer) or JDK 17 with the Android SDK (platform 35).

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # installs on a connected phone (USB debugging on)
./gradlew test                   # runs the 26 unit tests for the tracking logic
```

Or open the folder in Android Studio and press **Run**.

- Minimum Android 8.0 (API 26). Targets Android 15 (API 35).
- The release build is signed with the debug key so you can sideload it straight away. Use your own keystore if you publish it.

> **Build status (please read):** I couldn't compile this project here, because my environment has no Android SDK. All the pure logic (GPS maths, stats, splits, PRs, segment matching, goals, GPX/TCX, storage, crash recovery and the recording state machine) **was compiled and passes 26 unit tests**. The Android/Compose layer was checked by hand against the API surface, but it hasn't been through a real build yet. If the first Gradle build reports anything, paste the error to me and I'll fix it.

---

## What's in it

| Area | What you get |
|---|---|
| **Recording** | GPS foreground service that keeps going with the screen off. It uses the phone's own GPS, so no Google Play services are needed. Has auto-pause and manual pause, spoken split announcements that duck your music, and a live map. You can follow a route with an "X km to go / off route" readout. If you started recording before your fix was precise, it warns you. |
| **Crash-safe** | Each GPS point is written to disk as soon as it arrives. If the app or phone dies mid-run, Stride offers to **Resume / Save / Discard** next time you open it. If Android kills the service, it restarts and carries on by itself. |
| **Maps** | OpenStreetMap (osmdroid), with no API key. Tiles are inverted in dark mode and cached on the phone (up to 300 MB). The "Everywhere you've been" map overlays all your activities. |
| **Stats** | Distance, moving time vs elapsed time, average and best pace or speed, elevation gain (with GPS noise filtered out), calorie estimate, per-km or per-mile splits with bars, and elevation and pace charts. |
| **Routes** | Draw a route by tapping the map. **Follow roads & paths** snaps it to real streets and trails using the free OSM routing service. You can also save any activity as a route, or import GPX/TCX. Then record while following it. |
| **Segments** | Pick any stretch of an activity with a range slider and save it as a segment. Stride finds every past and future time you did it, ranks your efforts, and gives you a trophy for a new best. |
| **Personal records** | Best efforts are found automatically: 400 m, ½ mile, 1 km, mile, 2 mile, 5K, 10K, 15K, 10 mile, 20K, half marathon, 30K and marathon on foot, plus 5–160 km on wheels. Records are kept separately for each sport. If you delete an activity, its records pass back to the next-best effort. |
| **Goals** | Weekly, monthly or yearly goals for distance, time, elevation or activity count, for all sports or just one. Each shows "on track / X to go" and the days left. There's also a week streak, a 12-week chart and an 18-week activity calendar. |
| **Safety check-ins** | Texts up to 5 contacts an OpenStreetMap link to your location. Texts go out when you start, every 5–30 min, if you haven't moved for X min, when your battery drops to 15% and when you finish. They're plain SMS, so your contacts don't need any app. |
| **Share to Instagram** | Story/Reel (9:16), 4:5 post or square image. Backgrounds can be **transparent** (to lay over your own photo or video), dark, gradient or your own photo. You pick which stats appear, the route colour, and whether to show the title. There's also an **animated MP4** where the route draws itself while the numbers count up. |
| **Export** | GPX or TCX for each activity. You can also export **all activities as a GPX .zip** for bulk upload elsewhere. Routes export as GPX, and you can "Open with Stride" on a .gpx or .tcx file. |
| **Privacy** | "Hide start & finish" trims the first and last 0–1000 m from every image and export, so your home address isn't exposed. You can turn it on or off for each activity. |
| **Backup** | One .zip containing everything, plus a Restore option. This is how you move to a new phone, since there's no cloud copy. |

### Honest limits

- **Instagram:** the buttons open Instagram, which then asks whether you want Story, Reel, Post or Message. Instagram's direct "add to Story" shortcut needs a registered Facebook developer app ID, and that goes against the "no accounts" idea, so Stride doesn't use it. To use the transparent PNG as a sticker on a Story, open a Story with your photo or video, then add the PNG from your gallery.
- **Animated video:** it's rendered on the phone's hardware H.264 encoder. That works on nearly all phones, but I haven't been able to test it on a real device yet. It has no sound, so add music inside Instagram.
- **Safety texts:** sending them automatically needs the SMS permission, which Stride asks for when you turn the feature on. If you say no, "Share location" opens your messaging app with the text filled in, and you tap Send. Standard SMS rates apply. Google Play restricts the SMS permission for apps that aren't the default SMS app, so this is fine for sideloading. If you ever publish to Play, you'd switch to the manual mode.
- **Follow roads** needs an internet connection and uses the public OSRM servers that openstreetmap.org also uses (FOSSGIS). Only your tapped waypoints are sent. If it fails, the leg is drawn as a straight line.
- **Map tiles** come from openstreetmap.org's tile servers, which is fine for personal use under their tile policy. If you ever distribute the app widely, switch to a tile provider that's meant for apps.
- There's no heart-rate, Bluetooth sensor or watch support, and no social feed or clubs. Those need accounts and servers, so they're out of scope on purpose.

---

## Project layout

```
app/src/main/java/com/stride/
├── domain/            Pure Kotlin, unit-tested on the JVM
│   ├── Model.kt         Sport, TrackPoint, Activity, Route, Segment, Goal, Settings…
│   ├── Geo.kt           Haversine distance, point-to-line, Douglas–Peucker, privacy trim
│   ├── Analyzer.kt      Summary, moving time, elevation dead-band, splits, best efforts,
│   │                    chart series, GpsFilter (accuracy/teleport/jitter), AutoPause
│   ├── Segments.kt      SegmentMatcher (start/end gates + corridor check), RouteFollower
│   ├── Goals.kt         Goal progress, streaks, weekly totals, PR table merge
│   ├── Gpx.kt           GPX/TCX export, tolerant GPX/TCX import
│   ├── Codec.kt         Binary track format, tiny JSON, mappers
│   ├── Format.kt        Metric/imperial formatting
│   └── Safety.kt        Safety SMS text + map link
├── data/
│   ├── Store.kt         Plain files, atomic writes, in-progress recording file
│   ├── Library.kt       Save/delete/rebuild records, segment matching (tested)
│   ├── Repo.kt          App-wide StateFlow + background I/O
│   ├── RouteSnap.kt     Optional OSRM road-following
│   └── Backup.kt        Zip backup / restore (zip-slip safe)
├── record/
│   ├── RecorderCore.kt  Recording state machine (tested)
│   ├── RecordingService.kt  Foreground GPS service, notification, safety loop
│   ├── Safety.kt        SMS sending / messaging-app fallback
│   └── VoiceCues.kt     Text-to-speech splits
├── share/
│   ├── ShareCard.kt     PNG renderer (Canvas)
│   ├── RouteVideo.kt    Animated MP4 (MediaCodec + MediaMuxer)
│   └── Sharing.kt       FileProvider, Instagram/share sheet, save to gallery
└── ui/                  Jetpack Compose (Material 3) screens
    home/ record/ activity/ routes/ progress/ you/ share/ components/ theme/
```

### Where your data lives

Your data is in the app's private storage, under `files/stride/`:

- `activities.json` holds the summaries.
- `tracks/<id>.pbt` holds the GPS track for each activity (about 21 bytes per point, so 3 hours ≈ 230 KB).
- Routes, segments, efforts, goals, records and settings each have their own JSON file.

Nothing leaves the phone unless you share or export it.

### Permissions

| Permission | Why |
|---|---|
| Precise location | Recording |
| Foreground service (location), wake lock, notifications | Keep recording with the screen off and show the recording notification |
| Internet | Map tiles and the optional road-following |
| Send SMS (optional) | Automatic safety texts. Only requested when you enable them. |
| Storage (Android 8–9 only) | Saving images and videos to the gallery |
