# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
LG TVs (webOS) and Samsung TVs (Tizen) get the same home screen as a web app: `web/`, packed by `webos/` and `tizen/`
(see below).
The website (freemytv.io) is in `docs/`: static HTML, CSS and JS, no build step. A push to `main` that changes it
publishes it to GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (English) and `docs/pt/index.html`
(Brazilian Portuguese) are the same page in two languages, so change both; visitors from Brazil land on the
Portuguese one by themselves, and the PT/EN switch in the menu is remembered.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Installing it on an LG TV (webOS)

On LG it's an app, installed through LG's Developer Mode, and it changes nothing else on the TV: LG's home screen
stays, and Free My TV opens with a long press of a number key.

1. **Ask** for the TV's IP address and their city, if they didn't say. Tools: Python 3 and OpenSSH (`ssh`,
   `ssh-keygen`), already on macOS, Linux and Windows 10+.
2. **On the TV** (they do it): LG Content Store → *Developer Mode* → sign in with a free LG developer account
   (webostv.developer.lge.com) → *Dev Mode Status* on (the TV restarts) → *Key Server* on. It shows the IP and a
   passphrase.
3. **Settings:** the same `config.properties` as on Android (the first `python3 webos/build.py` creates it). The
   TMDB key rules above apply.
4. **Build:** `python3 webos/build.py` must end with `Built FreeMyTV-webos.ipk`.
5. **Install:** the first time needs the TV's passphrase, typed by them: ask them to run
   `python3 webos/install.py <IP>` in their own terminal (yours can't answer its prompt, and the passphrase doesn't go
   in the chat). It keeps the unlocked key in `~/.ssh/freemytv_webos_<IP>`, so later updates run from your terminal
   with the same command.
6. **Tell them:** while Free My TV is open, holding a number key pins it to that key. Developer Mode lasts about 40
   days; *EXTEND* in that app renews it (if it runs out, the TV removes developer apps, and installing again brings it
   back). `--uninstall` removes Free My TV.

## Installing it on a Samsung TV (Tizen)

On Samsung it's an app too (2017 and newer, Tizen 3+), installed through Samsung's Developer Mode; Samsung's home
screen stays. Two ways:

- **The release, no tools:** TizenBrew Installer (github.com/reisxd/TizenBrewInstaller) installs the latest GitHub
  release when they type `simbasjungles/free-my-tv`, and on 2023+ TVs signs it with their Samsung account. That build
  has no TMDB key (no picks) and takes the city from the TV's time zone. Walk them through the README's steps.
- **Their own build** (their city, their TMDB key), below.

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **On the TV** (they do it): *Apps* → type `1 2 3 4 5` on the remote → *Developer mode* on, *Host PC IP* = this
   computer's IP → restart the TV.
3. **Tools:** Samsung's `tizen` command to sign (Tizen Studio's CLI, or Samsung's Claude Code plugin
   `tizen-sdk-skills` from github.com/Samsung/tizen-agent-skills, which installs it and makes Samsung certificates),
   and `python3 tizen/install.py` to install, which speaks SDB itself. `tizen` runs on Java; Samsung's `sdb`, its new
   `tz` and Tizen Studio's installer (with its own JDK) are Intel binaries on macOS. On Apple Silicon without Rosetta,
   take the CLI from Samsung's package repository (download.tizen.org/sdk/tizenstudio/official, `pkg_list_macos-64`;
   check each zip's SHA256 there): unzip the `data/` of `new-common-cli`, `certificate-generator`, `sdk-utils` and
   `sdk-utils-core` into `~/tizen-studio`, then write `~/tizen-studio/sdk.info` with three lines,
   `TIZEN_SDK_INSTALLED_PATH=<that folder>`, `TIZEN_SDK_DATA_PATH=<home>/tizen-studio-data` and `JDK_PATH=<a native
   JDK 17's .jdk folder>` (Homebrew: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk`). That `tizen` signs natively.
4. **Certificate, once:** 2023+ TVs (Tizen 7+) need a Samsung certificate, made with their Samsung account for this TV
   (its DUID): the plugin's Samsung profile opens the browser for them to sign in, or Tizen Studio's Certificate
   Manager does it. 2017-2022 TVs take a Tizen certificate (`tizen certificate`, then `tizen security-profiles add`
   and `tizen cli-config "profiles.path=<home>/tizen-studio-data/profile/profiles.xml"`; on macOS the passwords go to
   the login Keychain). Certificate passwords are theirs: they type them in their own terminal, never in the chat.
5. **Settings:** the same `config.properties`; the TMDB key rules above apply.
6. **Build and sign:** `python3 tizen/build.py --sign <profile>` must end with `Built FreeMyTV-tizen.wgt (...,
   signed with "<profile>")`.
7. **Install:** `python3 tizen/install.py <IP>`: it pushes the app over SDB (port 26101), installs it with the TV's
   `0 vd_appinstall` and opens it with `0 was_execute` (tested on a 2017 Tizen 3.0 TV); `--uninstall` removes it.
   With Samsung's tools instead: `sdb connect <IP>`, `tizen install -n FreeMyTV-tizen.wgt -t <serial from sdb
   devices>` and `tizen run -p FreeMyTVio.FreeMyTV -t <serial>`. Updates need the same certificate: the TV refuses
   one signed by another (uninstall first). A retail TV's sdbd only takes files (`filesync_support:push`): nothing
   can be pulled from it. To look inside the running app, `0 debug FreeMyTVio.FreeMyTV 300` over SDB opens it with
   a Web Inspector port (Runtime.evaluate works on Tizen 3; screenshots don't).
8. **Tell them:** *Autorun Last App* in the TV's settings (under Start Screen Options; where it is changes by year)
   makes the TV turn on into Free My TV.

## Releases

TizenBrew Installer takes the first `.wgt` of the latest GitHub release, so every release carries
`FreeMyTV-tizen.wgt`: built with `--generic` (no config.properties: no key, the city from the time zone) and signed
with a Tizen certificate (`--sign`), which 2017-2022 TVs install as it is; on 2023+ TVs the installer signs it again
with the person's Samsung certificate. Sign every release with the same author certificate: older TVs refuse an
update signed by another. `python3 webos/build.py --generic` makes the LG `.ipk` that goes along.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

LG and Samsung: the web app above. Other TVs that don't run Google TV or Android TV: the way is a Google TV streamer on
HDMI (Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 28 (Android 9), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 has no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 9 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step.
  To check for newer APIs, compile against `platforms/android-28/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded) and `Recording.Node` should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 9 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("pt", "en")`, with the Spanish, German and Russian in `Spanish.java`, `German.java` and
  `Russian.java` (keyed by the English); text built at runtime uses `Ui.tr(pt, en, es)`, and its German and Russian
  come from those files too (patterns for the parts built at runtime). Add them all. Comments in English, short, only where the why
  isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons) wins over those first values.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar) and Sony TVs and Formovie projectors, the service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons and `KEYCODE_BUTTON_4` for Netflix, global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.
- LG and Samsung (`web/`): plain ES5 JavaScript and CSS without variables, grid or backdrop-filter, since older TVs
  (webOS 3 and 4, Tizen 3) run a Chromium from 2015 to 2017; only transform and opacity animate. `web/tv.js` is the
  TV's side: on LG, Luna (`listLaunchPoints`, `launch`, the inputs; where webOS 6 and newer deny `listLaunchPoints`
  to Developer Mode apps, the list comes from the TV's remote control API on `ws://127.0.0.1:3000` once the TV has
  allowed it (it asks once; a "no" isn't asked again), and until then from `getAppLoadStatus` over a list of known
  app ids, which shows their names without icons); on Samsung, `tizen.application` (`getAppsInfo`,
  `launch`), the `VIDEOSOURCE` property and `tizen.tvwindow.setSource` for the inputs, then Samsung's viewer
  (`org.tizen.tv-viewer`) to show them. Samsung's own data is unreliable: on Tizen 3 no app's `iconPath` loads (on
  Tizen 4 and 5, YouTube's and Prime Video's are a placeholder), so logos are bundled in `web/icons/` and keyed by app
  id in `SAMSUNG_ICONS` (SVGs need a width and height for old WebKit); some names are empty or long
  (`SAMSUNG_TITLES`); and Tizen 3 answers no "settings" app control, so TV Settings opens `org.tizen.menu`.
  `web/home.js` follows `HomeView`'s layout and behavior, so a design change on
  Android belongs there too. Text: `tr(pt, en, es)`, with the German and Russian in `web/strings.js` (keyed by the English). Built without a city (`--generic`), `web/zones.js`
  (`tools/make_zones.py`, from the tz database) turns the TV's time zone into the city, the currency and the region.
  `tools/webapp.py` is what both builds share. Preview: `python3 webos/build.py --preview` (or `tizen/build.py`), then
  open `out/webos/index.html` in a browser (a stand-in lists sample apps).

## Always

- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`).
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
