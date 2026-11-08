# TV Home Launcher (televisionOS)

A clean Android TV home screen inspired by Apple TV — no ads, no bloat, just your apps,
a hero artwork slideshow, and a control center.

Built with classic Android Views (API 26+), not Compose. Targets Android TV / Google TV
devices over Wi-Fi ADB (no root required).

## Features

- **Dock + app grid** — pinned favorites row at the bottom, scrollable app grid above
- **Hero artwork slideshow** — movies/series backdrops from Cinemeta (public metadata),
  with crossfade transitions and optional logo/title
- **Frosted-glass UI** — dock, control panel, status capsule and "Regarder" button share
  a live blurred backdrop sampled from the artwork
- **Control center** — power, settings, Wi-Fi, audio, timer, Bluetooth, captions, sources,
  apps (toggle with the SETTINGS/MENU key or the remote's control button)
- **Inputs panel** — HDMI/AV/Live TV passthrough inputs with connection state
- **Screensaver** — clock dream with weather (`ClockDream`)
- **French descriptions** — TMDB French summaries (token read from the TV at runtime,
  never compiled into the APK)
- **Trailers** — YouTube trailer preview on artwork focus (5 s dwell)

## Prerequisites

- A computer on the same Wi-Fi as the TV
- The TV in **Developer mode** with **USB debugging** (ADB over Wi-Fi) enabled
- JDK 17+ (`JAVA_HOME` set, or auto-detected in `~/android-tools/jdk`, `/usr/lib/jvm/*`)
- Android SDK (`ANDROID_HOME` set, or auto-detected)
- Python 3 with `adb-shell`, `rsa`, `cryptography` (`pip install -r tools/requirements.txt`)

## Build & install on the TV

```bash
# Linux / macOS
TV_HOST=192.168.1.xxx ./build-and-install.sh

# Windows (PowerShell)
.\build-and-install.ps1 -TvHost 192.168.1.xxx
```

The script:
1. Compiles the debug APK with Gradle (`./gradlew assembleDebug`)
2. Pushes it to the TV over ADB (`tools/b64push.py` + `tools/tv.py`)
3. Installs it (`pm install -r`), grants Wi-Fi permissions
4. Sets it as the default HOME app and disables the stock launcher
5. Re-binds the accessibility service (`SourceKeyService`) so the SET/INPUT keys work
6. Copies the TMDB token from `~/.config/tvhome/tmdb-token` onto the TV (optional)

## Run the instrumentation tests

```bash
TV_HOST=192.168.1.xxx ./run-tests.sh              # all tests
TV_HOST=192.168.1.xxx ./run-tests.sh DockBlur      # filter by name
```

## Project layout

```
launcher/
├── app/
│   ├── build.gradle              # module config (namespace com.gathin.tvhome)
│   └── src/
│       ├── main/                 # app source (Java + res/)
│       │   ├── java/com/gathin/tvhome/   # 29 classes
│       │   ├── res/              # layouts, drawables, values, fonts
│       │   └── AndroidManifest.xml
│       └── androidTest/          # 17 instrumentation tests
├── gradle/                       # wrapper (Gradle 9.7.1)
├── tools/                        # ADB helper scripts (tv.py, b64push.py, adbkey/)
├── build-and-install.sh          # Linux/macOS install
├── build-and-install.ps1         # Windows install
├── run-tests.sh                  # test runner
└── .gitignore
```

## Configuration

- `app/src/main/res/values/config.xml` — `dock_apps` (pinned dock order) and
  `hidden_apps` (packages hidden from the grid), column count
- TMDB token: place it in `~/.config/tvhome/tmdb-token` (one line) before installing —
  enables trailers and French summaries. Never committed to the repo.

## Restore the stock launcher

```bash
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher
adb shell pm enable-user --user 0 com.google.android.tvlauncher
```

## License

See `FONT-LICENSE-OFL.txt` for the bundled font license.
