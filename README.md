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

The APK is built by GitHub Actions. After every push to `main` the workflow recreates the
pre-release **`latest`** with `F1-Replays.apk` attached, so this download link never changes:

```
https://github.com/nora7297/F1-Real-Debrid/releases/download/latest/F1-Replays.apk
```

The repository has to be **public** for that link to work without a GitHub login (GitHub →
*Settings → General → Danger Zone → Change visibility*). While it is private, download the APK from
the *Releases* page on a computer and use the ADB route below.

### With the Downloader app (Shield and Fire TV, no computer needed)

1. Install **Downloader** (by AFTVnews) from the Play Store on the Shield, or from the Amazon
   Appstore on a Fire TV.
2. Allow it to install apps:
   * Shield: *Settings → Device Preferences → Security & restrictions → Unknown sources → Downloader*.
   * Fire TV: *Settings → My Fire TV → Developer Options → Install unknown apps → Downloader*.
3. Open Downloader and enter the link above. To avoid typing it with the remote, create a short
   code for it once at https://go.aftvnews.com and type just the number, or make a custom short
   link (for example a TinyURL alias) that points at the link above.
4. Downloader fetches the APK and offers to install it. For updates, pick the same entry from
   Downloader's history and install again.

### With ADB from a computer

1. Shield: *Settings → Device Preferences → About → Build* (press 7 times), then
   *Developer options → Network debugging → On*. Fire TV: *Settings → My Fire TV → Developer
   Options → ADB debugging → On*. Note the TV's IP address in its network settings.
2. On the computer:
   ```
   adb connect <tv-ip>:5555
   adb install -r F1-Replays.apk
   ```

Every build is signed with the keystore in `ci/`, so a newer build installs over the old one as an
update and keeps your login and library.

### Updating from inside the app

The app checks the `latest` release for a newer build when it opens (and from *Settings → Check for
updates*). When one exists an **Update to build N** button appears next to *Sync now*: it downloads
the APK, verifies its checksum and opens the system installer. The first time, Android asks you to
allow F1 Replays to install apps; allow it, come back and press *Install* again. The running version
is shown under the title on the home screen and in Settings.

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
| Reddit app client ID | empty | Optional. If Reddit keeps blocking the sync, create an *installed app* at https://www.reddit.com/prefs/apps and enter its client ID here; the app then uses Reddit's official API, which is never blocked. |

Removing a release in the app remembers it so it is not re-added by the next sync.
Nothing is ever deleted from your Real-Debrid account by the app.

## How the app reads Reddit

No Reddit account is needed. The sync tries, in order: Reddit's official API (only if you entered a
client ID in Settings), the public JSON listing with the app's own User-Agent and then a browser
one, and finally the subreddit's RSS feed, which Reddit keeps open for feed readers. The route that
worked last is tried first next time. If every route fails, the home screen shows the HTTP status
each one returned.

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
