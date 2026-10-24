<div align="center">

<img src=".github/weatherglass-icon.png" width="96" alt="">

# Weatherglass TV

**A TV home screen whose sky follows the real weather.**

A modified version of [Free My TV](https://github.com/simbasjungles/free-my-tv) by simbasjungles: the same fast, ad-free home screen, with a few changes of its own.<br>
Modified by xACTDx · English and Bahasa Indonesia

[GitHub](https://github.com/faizaldjoo/weatherglass-tv) · [Free My TV](https://github.com/simbasjungles/free-my-tv) · [What's different](#whats-different) · [Install](#install) · [Settings](#settings)

<img src=".github/weatherglass-sky.webp" width="880" alt="An animated day on the ink-mountain wallpaper: sunrise, noon, golden hour, sunset, a rain shower and a starry night, with the clock, greeting and weather changing along">

</div>

## What's different

Weatherglass TV starts from Free My TV and adds or changes:

- **A CCTV camera on the shelf (optional).** Type the camera's RTSP address in *Settings → Widgets → Camera address* (or set `cctv_url`) and the camera gets its own tile with a still picture that refreshes every 5 minutes. OK opens it live in a popup over the home screen; OK again goes full screen in VLC. It speaks RTSP itself (Digest or Basic login, H.264 or H.265), so cameras Android's player can't log in to still work. No camera? Leave it empty, or turn *CCTV camera* off, and the shelf has no camera tile. See [Connecting a CCTV camera](#connecting-a-cctv-camera).
- **Settings as a frosted-glass drawer.** Settings slide in from the right over the home screen, which stays where it is behind the glass: grey by day, black at night. → opens a section, ← goes back. Sections: Home Screen, Screensaver, Appearance, Theme, Widgets, Shelf, Remote Buttons, About and TV Settings.
- **English and Bahasa Indonesia.** The language is English, Bahasa Indonesia, or automatic from the TV's own setting; prayer times use the Indonesian names (Subuh, Dzuhur, Ashar, Maghrib, Isya).
- **A greeting.** "Good Evening, Haikal" (or "Selamat Malam") with a waving hand, on the home screen and the screensaver, following the TV's clock; the hand waves when the home screen appears. Each TV keeps its own name: *Settings → Appearance → Your name* (`greeting_name` in `config.properties` fills it in the first time).
- **A glass clock.** Over the sky wallpapers, the home screen's greeting, clock, weather and date sit on frosted glass cards, like the settings drawer; the shelf and the bottom drawer match. The glass catches the light from the side of the ink mountains' sun or moon. *Settings → Appearance → Glass* picks Frosted or Transparent; *Clock widget* sets its size.
- **For You on the Movie wallpaper.** With a [TMDB key](#the-tmdb-key), *Settings → Theme → Movie* turns the top of the home screen into a film billboard: TMDB's Popular, Now Playing, Upcoming and Top Rated movies in one glass pill, the selected film's artwork filling the top right (it breathes slowly with *Motion* on), and the film's card on a deck at the left, with its service, title, details and story; → and ← move through the films. On this theme the screensaver shows the same films, labeled by category. OK opens the film in its streaming app, or plays its YouTube trailer when it isn't streaming yet. The clock, weather, city and the next prayer sit at the top right.
- **A control page on your Wi‑Fi.** The TV serves its own dashboard at `http://<the TV's address>:8765` (the address and a four-digit pairing code are in *Settings → About*): a remote shaped like the TV's, with haptic taps on Android phones; the app in front, memory, storage and uptime; one-tap app launches; and care actions for when the TV feels slow: free memory, clear caches, restart the home screen, a sleep timer, a reboot. It works only inside your network; nothing goes through the internet.
- **Softer clouds at sunrise and sunset.** On the ink mountains, the clouds take the sky's own warm colour while the sun is low, instead of standing out white.
- **Posters on the cards, and a clear ink sky.** The two tall cards show the title's poster. Over the ink mountains, the picks are hidden and ↑ from the shelf goes straight to the corner buttons.
- **Exchange rates.** Up to four currencies (e.g. USD, AUD, JPY, EUR) priced in yours.
- **A new name and logo, and no ambient sound.**


<p align="center"><img src=".github/weatherglass-drawer.webp" width="760" alt="The settings drawer sliding in over the home screen in black frosted glass: the focus moves down to Appearance, → opens it, a switch flips, ← goes back and the drawer slides out"><br><sub>The settings drawer at night: black frosted glass over the home screen, opened and worked with the arrow keys.</sub></p>

Everything else is Free My TV's work.

<p align="center"><img src=".github/weatherglass-preview.webp" width="760" alt="Weatherglass TV at night: the ink mountains under the moon, the greeting and date, a big clock, the weather, the next prayer, exchange rates and a glass shelf of apps with the CCTV tile"><br><sub>On a Hisense 43″ TV at night.</sub></p>

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos. Not one.
- **Your apps, front and center.** The streaming apps you actually use, on a glass shelf. Pick them and their order right on the TV.
- **Trending, not sponsored.** Three fresh picks every hour from what's trending this week and the latest releases, only from apps you already have. Movies and Shows get their own tabs.
- **Weather and markets, one press away.** The hour-by-hour forecast, sunrise and sunset, and live crypto prices.
- **Light by day, dark by night.** It follows your city's real sunset, the way your phone does.
- **A screensaver to match.** Full-screen photos of the picks with a big glass clock.
- **Fast.** 60 fps, every frame in 16.6 ms, on the TV's own chip. The home screen uses 96 MB of memory instead of 242 MB (−60%).<br><sub>Measured on a 98″ TCL Google TV running Android 14.</sub>
- **Set up on the TV.** Language, your city, temperature, theme, the weather and prices cards, which coins, and the apps on the shelf, in its own settings.
- **Safe.** No root. Nothing is uninstalled. One button brings back the factory home screen.

## Install

About ten minutes. You need a Google TV or Android TV (Android 9 or newer). Without a computer, use the download just below; to build it yourself, a computer on the same Wi-Fi.

Works on Android TV and Google TV 9+ (Sharp, Xiaomi, Polytron, Coocaa, Sony, Philips…). Tested on TCL (Android 11) and Hisense (Android 12).

### Without a computer: download the APK

A ready-made build is on the [Releases](https://github.com/faizaldjoo/weatherglass-tv/releases/latest) page: no TMDB key (no trending picks), no camera, the city set to Jakarta until you pick yours, prayer times and exchange rates in rupiah on.

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer options → turn on **USB debugging**. The switch needs it: Weatherglass TV uses the TV's own debugging to change the home screen.
2. **Install [Downloader](https://play.google.com/store/apps/details?id=com.esaba.downloader)** (by AFTVnews) from Google Play on the TV, and allow it to install apps (Settings → Apps → Security & restrictions → Unknown sources → Downloader).
3. **In Downloader**, type `github.com/faizaldjoo/weatherglass-tv/releases/latest/download/WeatherglassTV.apk` and install it.
4. **Open Weatherglass TV** → *Home Screen* → *Weatherglass TV*, and accept the debugging prompt (tick *Always allow*).
5. **Your city:** Settings → Widgets → City.

It works on TVs that open debugging on the network when USB debugging is on (TCL, Hisense and many others). On TVs that only offer *Wireless debugging* with a pairing code, it can't switch yet. To have your own TMDB key, camera or name in it, build it yourself (below).

### The easy way: an AI assistant

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer options → turn on **USB debugging**. Write down the TV's IP address (Settings → Network & Internet).
2. **On your computer**, open an AI coding assistant ([Claude Code](https://claude.com/claude-code), Codex or Antigravity) and say:
   > Install Weatherglass TV (github.com/faizaldjoo/weatherglass-tv) on my TV at 192.168.1.50. I live in Jakarta.

   It downloads the code, installs the tools it needs, builds the app and puts it on your TV, and asks before anything important. Swap in your TV's IP and your city.
3. **On the TV**, open *Weatherglass TV* → *Home Screen* → *Weatherglass TV*, and accept the debugging prompt (tick *Always allow*).

### The manual way

1. **On the TV**, do step 1 above.
2. **Tools:** a JDK (17+), the Android SDK command-line tools (build-tools 34, platform android-34) and adb. On a Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   On Linux, get OpenJDK 17, Android's command-line tools and adb from your package manager, then run the same `sdkmanager` line. On Windows, use Git Bash (which Claude Code uses there) or WSL.
3. **Build:**
   ```bash
   ./build.sh
   ```
   The first run creates `config.properties`. Set your city and your [TMDB key](#the-tmdb-key) there, then run it again.
4. **Install:**
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   On TCL TVs, also let it run in the background (TCL blocks it by default, and the switch never starts):
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
5. **On the TV**, open *Weatherglass TV* → *Home Screen* → *Weatherglass TV*.

## LG TVs (webOS)

*The LG and Samsung versions are Free My TV's own web apps, unchanged in Weatherglass TV: the changes above are for Android TV and Google TV.*

The same home screen as an LG app: the picks, the glass shelf with your apps, the drawer with inputs, weather and prices, day and night, Home Settings and the shelf editor. It installs through LG's own Developer Mode, with nothing rooted or changed on the TV. LG's home screen stays where it is, and Free My TV is one press of a number key away.

You need an LG TV (webOS 3 or newer) and a computer on the same Wi-Fi, with Python 3.

1. **On the TV**, open the LG Content Store and install **Developer Mode**. Sign in with a free [LG developer account](https://webostv.developer.lge.com), turn on **Dev Mode Status** (the TV restarts), then turn on **Key Server**. Write down the IP address and the passphrase it shows.
2. **On your computer**, get the code and build. `config.properties` works as for Google TV (your city, your [TMDB key](#the-tmdb-key)):
   ```bash
   git clone https://github.com/simbasjungles/free-my-tv && cd free-my-tv
   python3 webos/build.py
   ```
3. **Install.** The first time, it asks for the TV's passphrase; updates don't:
   ```bash
   python3 webos/install.py 192.168.1.50
   ```
4. **On the TV**, while Free My TV is open, hold a number key on the remote. From then on, that key opens it from anywhere.

With an AI assistant, say *"Install Free My TV (github.com/simbasjungles/free-my-tv) on my LG TV at 192.168.1.50. I live in Lisbon."* It builds the app and hands you the install command, so you type the passphrase yourself.

Developer Mode lasts about 40 days: open the Developer Mode app now and then and press **EXTEND**. To remove Free My TV, run `python3 webos/install.py 192.168.1.50 --uninstall`.

## Samsung TVs (Tizen)

The same home screen as a Samsung app, with everything the LG one has: the picks, the shelf, the drawer with the inputs, the weather and the prices, day and night, and its own settings. It installs through Samsung's own Developer Mode, with nothing rooted or changed on the TV, and Samsung's home screen stays where it is.

You need a Samsung TV from 2017 or newer (Tizen 3+) and a computer on the same Wi-Fi.

1. **On the TV**, open **Apps** and type `1 2 3 4 5` on the remote. Turn **Developer mode** on, type your computer's IP address in **Host PC IP**, and restart the TV.
2. **On your computer**, download [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller/releases/latest), the community's installer for Samsung TVs, open it and connect to your TV.
3. **Install from GitHub:** type `simbasjungles/free-my-tv`. On 2023 and newer TVs it asks you to sign in to your Samsung account once, to sign the app for your TV.
4. **Straight to it:** in the TV's settings, under Start Screen Options, turn on **Autorun Last App**: the TV turns on into Free My TV.

This build takes your city from the TV's time zone. For your exact city and the trending picks, build it with your own `config.properties` and [TMDB key](#the-tmdb-key): open the folder in Claude Code and say *"Install Free My TV on my Samsung TV at 192.168.1.50. I live in Lisbon."* It sets up Samsung's tools, and on 2023 and newer TVs you sign in to your Samsung account in the browser to make the certificate. Your signed build installs with `python3 tizen/install.py 192.168.1.50`, which needs only Python: no Tizen Studio, and no Rosetta on Apple Silicon Macs.

## Settings

The first values come from `config.properties`, created by the first build (Google TV, LG and Samsung use the same file). Most of them can also be changed on the TV, in the settings drawer (*Appearance*, *Theme* and *Widgets*), and the TV's choice wins:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Your free TMDB key, for the picks | empty |
| `tmdb_region` | Your country (two letters): which titles are on which app | `US` |
| `watchmode_key` | Optional, free [Watchmode](https://api.watchmode.com/) key: a pick opens the title's page in its app | empty |
| `latitude`, `longitude` | Your city, for the weather and the sunset | New York |
| `city` | The name on the weather card | `Weather` |
| `hide_animation` | Leave cartoons and anime out of the picks | `false` |
| `language` | `auto` (follows the TV: Bahasa Indonesia if the TV is in Indonesian, else English), `en` or `id` | `auto` |
| `show_crypto` | Show Binance crypto prices on home and screensaver | `true` |
| `currency` | Crypto prices in `USD`, `EUR`, `BRL`… | `USD` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `disable_assistant` | Also turn off Google Assistant | `false` |
| `cctv_url` | A CCTV camera's RTSP address (`rtsp://user:password@ip:port/path`): a live tile at the end of the shelf. Also *Widgets → Camera address* on the TV | empty: no camera |
| `cctv_name` | The camera's name, read by TalkBack | `CCTV` |
| `greeting_name` | The name in the greeting before the date ("Good Evening, Haikal"). Also *Appearance → Your name* on the TV | empty: just the greeting |

Changed something? Run `./build.sh` and install again. Keep `freemytv.keystore`: updates are signed with it.

### Connecting a CCTV camera

Optional: without a camera, there's simply no camera tile. Any camera that streams RTSP on your home network works; it was made and tested with an **Eyesec** Wi-Fi camera.

1. **Find the camera's address on your network**, for example `192.168.1.20`: in the camera's app (device info), or in your router's list of connected devices. It helps to reserve that address in the router, so it doesn't change.
2. **Know its username and password**: the camera's own login, set in its app (often `admin` and the password you chose), not your account in the app.
3. **Write its RTSP address.** For an Eyesec camera:
   ```
   rtsp://admin:yourpassword@192.168.1.20:554/stream0
   ```
   `stream0` is the full picture, `stream1` the small one (the tile's still uses `stream1` by itself). Other brands use other paths: look up "RTSP URL" for your model. Check the address first in VLC on a phone or computer (*Open Network Stream*): if VLC shows the picture, the TV will too.
4. **Put it in**, either on the TV (*Settings → Widgets → Camera address*, typed with the remote or the Google TV app's keyboard on your phone) or in `config.properties` (`cctv_url=`) before building. *CCTV camera* turns the tile on and off without forgetting the address.
5. **Install VLC** on the TV (Play Store) to watch it full screen: OK on the tile opens the live popup, OK again opens VLC.

The address, password included, stays on the TV (and in your `config.properties`, which stays out of git). Settings shows only the camera's IP.

### The TMDB key

Free, and it takes two minutes: create an account at [themoviedb.org](https://www.themoviedb.org/signup), then open [Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Paste the *API Read Access Token* (or the *API Key*) after `tmdb_key=`. It's your key: `config.properties` stays out of git.

### Opening a title, not just its app (optional)

With a free [Watchmode](https://api.watchmode.com/) key (`watchmode_key=` in `config.properties`), a pick opens its own page in the streaming app instead of the app's home. The free plan (2,500 credits a month) is enough: each title costs two requests the first time and is remembered after that. It works where the app takes the title's web address (tested: Prime Video and JioHotstar); where it doesn't, the pick just opens the app, as without a key.

## Going back

<p align="center"><img src=".github/weatherglass-original.webp" width="760" alt="Going back to the TV's own home screen: ↑ to the gear, OK opens Settings, Home Screen, pick Original, wait while it switches and measures, and the TV's own home screen, screensaver and apps are back"><br><sub>Back to the TV's own home screen in four steps: the gear, Home Screen, Original, a few seconds' wait.</sub></p>

On the TV: *Weatherglass TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app come back exactly as they were. If the new home screen ever crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall *Weatherglass TV* (Settings → Apps). Uninstalled it while it was on? Install it again and pick *Original*: the list of what it turned off is kept in the TV's settings, so everything comes back.

## What it changes

- It becomes the home screen and the screensaver.
- It turns off, without uninstalling:
  - the stock home screen and screensaver;
  - Google TV's recovery home, Play Games and YouTube Music;
  - on TCL TVs, the TCL Channel, messages, store demo, hotel menu, manuals, mirroring and logging apps;
  - on Philco TVs, AI PONT (promo channels and app downloads) and the manual;
  - on Multilaser TVs, Anoki's screen-content recognition (ACR), the store demo and the manual;
  - on KTC-made TVs (NextStar), Anoki's screen-content recognition (ACR), the store demo and the manual;
  - Google Assistant, only with `disable_assistant=true`.
- On TCL TVs, the remote's Netflix, YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons. Pick what each one opens in *Weatherglass TV* → *Remote Buttons*; *Original* turns the service off. On Vestel-made TVs (F&U and other store brands) and Xiaomi (Formovie) projectors the same goes for the Netflix button, and on KTC-made TVs (NextStar) for the Tabii button. On Sony TVs, the BRAVIA CORE, Netflix, Disney+, Prime Video, YouTube and Crunchyroll buttons can each open the app you pick, or keep Sony's default.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before every change.
- Apps that saved a Wi-Fi network are never turned off, since Android would erase the network. If the TV's first setup saved yours, the switch asks you to forget the network and connect again in Settings first (saved networks out of range don't show there; Claude Code can remove them).

## Any TV

It runs on Google TV and Android TV: TCL, Sony, Hisense, Philips, Xiaomi, Vestel-made TVs (F&U), KTC-made TVs (NextStar) and more, plus streamers like Chromecast with Google TV, the Google TV Streamer, onn 4K and NVIDIA Shield. And on [LG](#lg-tvs-webos) and [Samsung](#samsung-tvs-tizen) TVs, as an app.

**Any other TV:** plug a Google TV streamer into HDMI and free that one. Same steps, same home screen.

**A different brand or model:** ask Claude. It is made to be adapted by AI. Open the folder in Claude Code and say *"set it up for my Sony"*: it reads your TV, finds its home screen and its promo apps, updates the lists below and walks you through a test. [CLAUDE.md](CLAUDE.md) tells it how.

| What | Where |
|---|---|
| Apps to turn off | `DEBLOAT` in [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps never to touch | `PROTECTED` in the same file |
| Apps on the shelf | `SHELF` in [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| Your brand's live TV app | `LIVE_TV_APPS` in the same file |

## Make it yours

It's your code, and Claude Code finds its way around it. Try:

- *"Add Crunchyroll to the shelf."*
- *"Show the prices in euros."*
- *"Add Spanish."*
- *"Switch to the night look an hour after sunset."*

## How it works

- **A plain Android app, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign → apksigner and makes a 2 MB APK.
- **It uses the TV's own ADB, from inside the TV.** The app connects to `127.0.0.1:5555` with its own key (that's the *Allow USB debugging?* prompt) and runs the same commands you would: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`. Nothing listens to the network.
- **Drawn by hand on one Canvas**, with Android 13 shaders for the frosted glass and the sky (plain fills on Android 9 to 12, with lighter shadows and no GPU layers on 9 to 11 for their older GPUs).
- **A safety net.** A watchdog in a separate process brings back the stock home screen after three crashes in five minutes, and a boot check keeps your choice after TV updates.
- **On LG and Samsung, the same design in plain HTML, CSS and JavaScript** ([`web/`](web)), written for the Chromium of older TVs, with no frameworks. [`webos/build.py`](webos/build.py) packs it with `config.properties` into an `.ipk` without LG's tools, and [`webos/install.py`](webos/install.py) installs it through Developer Mode; on LG it lists and opens apps through the TV's own service bus (Luna), as LG's launcher does. [`tizen/build.py`](tizen/build.py) packs the same app into a Samsung `.wgt`, which uses Tizen's web APIs for the apps and the inputs. To preview it on a computer: `python3 webos/build.py --preview`, then open `out/webos/index.html` (a stand-in lists sample apps).
- **Data** from TMDB (trending titles, new releases and which app streams what in your country), Open-Meteo (forecast, sunrise and sunset), and optionally Binance (24-hour prices when `show_crypto=true`).

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/io/freemytv/HomeView.java) | The home screen |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | Switching between Free My TV and Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | The TV's own ADB, from inside the TV |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | The picks (TMDB) |
| [`HomeData.java`](app/src/io/freemytv/HomeData.java) | Weather and prices |
| [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) | The shelf, inputs and All Apps |
| [`DreamView.java`](app/src/io/freemytv/DreamView.java) | The screensaver |
| [`SettingsActivity.java`](app/src/io/freemytv/SettingsActivity.java) | The settings screen (the app's icon) |
| [`Prefs.java`](app/src/io/freemytv/Prefs.java) | Settings changed on the TV |
| [`RemoteKeys.java`](app/src/io/freemytv/RemoteKeys.java) | The TCL remote's app buttons |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | The safety net |
| [`web/home.js`](web/home.js) | The home screen on LG TVs |
| [`web/tv.js`](web/tv.js) | The TV's apps and inputs, on LG and Samsung |
| [`webos/build.py`](webos/build.py), [`webos/install.py`](webos/install.py) | The LG app package and its install |
| [`tizen/build.py`](tizen/build.py) | The Samsung app package |

The website ([freemytv.io](https://freemytv.io)) is in [`docs/`](docs): plain HTML, CSS and JS on GitHub Pages.

## Privacy

No accounts, no analytics, no tracking. The app talks to TMDB, Open-Meteo (with the coordinates you set, or your time zone's main city), and Binance only when `show_crypto=true`, and Watchmode only when you set a `watchmode_key`. Your settings stay on your computer and your TV.

## Credits

- Based on [Free My TV](https://github.com/simbasjungles/free-my-tv) by simbasjungles (MIT). Weatherglass TV keeps its code, design and license.
- Built with [Claude Code](https://claude.com/claude-code).
- The LG version builds on what [@danielthejoker18](https://github.com/danielthejoker18) found in [FreeMyTvWebOS](https://github.com/danielthejoker18/FreeMyTvWebOS) (MIT): installing through Developer Mode and opening apps through the TV's service bus.
- On Samsung TVs it installs with [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller) by [@reisxd](https://github.com/reisxd).
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Links to the titles in the streaming apps by [Watchmode](https://api.watchmode.com/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Prices from the [Binance](https://www.binance.com/) public API.
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1). Coin logos: [cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons) (CC0). Samsung app logos: [Simple Icons](https://simpleicons.org) (CC0).
- Not affiliated with Google, TCL, LG, Samsung or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE), as Free My TV. The original copyright notice is kept in the license.
