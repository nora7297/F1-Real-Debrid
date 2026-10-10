# F1 Replays – Reddit → Real-Debrid for Android TV / Fire TV

A small Android TV app (NVIDIA Shield, Fire TV Stick, any Android TV box) that automates the
"find the F1 replay on Reddit, paste the magnet into Real-Debrid, play it" routine:

1. It reads the public JSON feed of **r/MotorsportsReplays**.
2. It keeps only **Formula 1** posts (not F1 Academy / F2 / F3 / MotoGP …) in **1080p or 4K**.
3. It pulls the **magnet links** out of the post (or out of the comments if the poster put them there).
4. It adds each new magnet to **your Real-Debrid account** and selects the video files.
5. Every release shows up in the app grouped by Grand Prix and session (Race, Qualifying, Sprint …).
   Press **OK** on one and it plays – in the built-in player or in Kodi / VLC / MX Player.

Steps 1–4 run **automatically in the background** (every 6 hours by default) and again every time
you open the app, so the links are normally already there when you sit down to watch.

## Installing

The APK is built by GitHub Actions. After every push to `main` the workflow publishes a
pre-release called **`latest`** on the repository's *Releases* page with `F1-Replays.apk` attached,
and the same file is also available as a workflow artifact.

> This repository is private, so the release download URL needs a GitHub login. The easiest way to
> get the APK onto the TV is therefore via a computer:

### NVIDIA Shield (and other Android TV devices)

1. On the Shield: *Settings → Device Preferences → About → Build* (press 7 times) to enable developer
   options, then *Settings → Device Preferences → Developer options → Network debugging → On*.
   Note the Shield's IP address (*Settings → Network*).
2. On your computer, download `F1-Replays.apk` from the Releases page and install it with ADB:
   ```
   adb connect <shield-ip>:5555
   adb install -r F1-Replays.apk
   ```
   (Alternatives: the "Send files to TV" app, or copy the APK onto a USB stick and open it with a
   file manager such as X-plore.)

### Amazon Fire TV Stick

1. *Settings → My Fire TV → Developer Options → ADB debugging → On* and *Apps from Unknown Sources → On*
   (on newer Fire OS versions the second one is per-app under *Install unknown apps*).
2. Either use ADB exactly as above (`adb connect <firestick-ip>:5555`), or install the **Downloader**
   app from the Amazon store and point it at a URL where you host the APK (a public GitHub release
   would work directly; a private one does not because Downloader cannot log in).

The app is signed with the keystore in `ci/`, so a newer build installs over the old one as an update
and keeps your login and library.

## First run

1. Open **F1 Replays**. It shows a short code and the address `https://real-debrid.com/device`.
2. On your phone or computer, open that page, log in to Real-Debrid and type the code.
3. The TV app picks the login up within a few seconds and immediately runs its first sync.

If you prefer, choose *Use an API token instead* and type the private token from
https://real-debrid.com/apitoken.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Quality | 4K preferred | `4K preferred` adds 1080p only when no 4K post exists for that session (and still adds the 4K one later if it shows up). Other options: both, 4K only, 1080p only. |
| Sessions | Race, Qualifying, Sprint, Sprint Qualifying, full weekend packs | Which sessions are added automatically. Practice and pre/post-race shows can be switched on. |
| Look back | 21 days | How far back in the subreddit to scan. |
| Background sync | every 6 h | Interval of the WorkManager job (also runs on app open if the last sync is older than 15 min). |
| Player | Built-in | Built-in ExoPlayer, an external player via the system "Play with…" chooser, or ask every time. |
| Read comments | on | Look in the comments of F1 posts that have no magnet in the body. |

Removing a release in the app remembers it so it is not re-added by the next sync.
Nothing is ever deleted from your Real-Debrid account by the app.

## How playback works

A Real-Debrid torrent that is already cached finishes in a couple of seconds; one that is not is
shown as *Downloading on Real-Debrid xx %* until it completes. When you press play, the app asks
Real-Debrid for a direct (unrestricted) link for that file and hands it to the player. The built-in
player remembers where you stopped.

## Development

```
./gradlew -PcoreOnly :core:test      # pure-JVM logic + unit tests, no Android SDK needed
./gradlew :app:assembleRelease       # needs the Android SDK (compileSdk 36)
```

* `core/` – Reddit feed parsing, F1 title/session/quality detection, magnet parsing,
  Real-Debrid REST + OAuth device-flow client, and the sync engine (all plain Kotlin, unit-tested).
* `app/` – Android TV UI (Compose for TV), background sync (WorkManager), ExoPlayer playback.

Signing: `ci/f1debrid.jks` (password `f1debrid`) is used by default so builds stay installable as
updates. To use your own key set `F1DEBRID_KEYSTORE`, `F1DEBRID_KEYSTORE_PASSWORD`,
`F1DEBRID_KEY_ALIAS` and `F1DEBRID_KEY_PASSWORD` (for GitHub Actions: as repository secrets and
pass them through in `.github/workflows/build.yml`).
