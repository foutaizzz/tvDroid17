# Instructions for coding assistants

Read this file and `README.md` before changing tvDroid 17.

## Product and compatibility

- This repository is an Android TV launcher, namespace/application ID `com.gathin.tvhome`.
- Preserve the installed app ID. Changing it would install a second app and lose upgrade compatibility.
- The tested reference is a Xiaomi Mi TV / Android TV 10 firmware. SET and INPUT remote handling and the `picture_backlight` screensaver integration depend on Xiaomi firmware and must not be advertised as universal.
- The screensaver is `ClockDream`, with a black background, a red analog clock, configurable Open-Meteo weather, and `ScreensaverBacklight` to set the Xiaomi picture backlight to 1 and restore its prior value.
- Startup cover remains black until the first complete artwork frame is available. Keep the backdrop/logo-or-title/dock/control chrome reveal synchronized.
- Artwork focus pauses the slideshow timer; on leaving focus, continue the remaining interval. Do not restart a full interval unless the requested behavior changes.
- During grid focus, only the artwork image is blurred; hide its logo and title, and keep the blur/fade synchronized with artwork transitions.
- SET/INPUT use the existing `SourceKeyService` and input panel. Do not replace the firmware-specific mapping without checking the key routing on the reference TV.
- `Regarder` must only be visible, focusable and actionable when PlayTorrio (`com.playtorrio.tv`) is installed and the selected item has a supported IMDb ID. Keep `<queries><package .../></queries>` aligned with that check.

## User configuration

- Edit resource defaults in `app/src/main/res/values/config.xml`: dock package order, hidden packages, grid columns, Live TV package, and weather city/coordinates/time zone.
- Weather must remain a fixed configured location, never inferred from GPS or IP.
- Do not put device IPs, ADB keys, TMDB tokens, personal account data, or private app backups in tracked files.
- TMDB credentials are read at runtime and kept outside the APK/repository.
- Xiaomi hidden package names are not portable; confirm package names on another TV before changing its profile.

## Implementation guardrails

- The launcher intentionally queries installed launcher apps using `LAUNCHER` and `LEANBACK_LAUNCHER`; keep manifest package visibility narrow and purpose-based. Do not add `QUERY_ALL_PACKAGES` when a known package query is sufficient.
- The app grid lists TV/launcher applications, not arbitrary system packages.
- Keep destructive/debloat actions separate from the ordinary build/install flow. Model-specific package removal or disabling must be clearly documented and reversible.
- System settings that Android does not expose to ordinary apps should be opened through Android's own settings UI. Do not claim a feature is supported unless the target firmware API actually permits it.
- Preserve existing remote navigation and focus restoration when changing views.
- Do not run device installation, reset, debloat, or destructive commands unless the user explicitly asks for that action in the current task.

## Project commands

- Linux/macOS build and device setup: `TV_HOST=<TV-IP> ./build-and-install.sh`.
- Windows: `build-and-install.ps1 -TvHost <TV-IP>`.
- Unit/instrumentation test sources are under `app/src/androidTest`; only run tests when asked.
- Keep user-facing docs in French and update README configuration guidance whenever a setting or TV-specific dependency changes.
