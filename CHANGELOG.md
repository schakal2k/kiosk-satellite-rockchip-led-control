# Changelog

## 0.3.0-dual.1

Dual-bar variant for the Allnet 174716 (RK3288) and similar panels. Not verified on hardware.

- Two independent LED bars, each with its own power, color, brightness, effect and Home Assistant light.
- New protocol layer matching the vendor demo app: per bar seven color channels (requests 0xa1-0xa7 and 0xb1-0xb7, level 0-15). The selected color is written last after the other six are zeroed.
- Colors are snapped to the seven-color palette; brightness uses 16 levels. Maximum channel drive is now capped at 15.
- Added a setting to swap left and right.
- Removed the old three-channel RGB write, which on this hardware left the bar stuck on blue.

## 0.2.5-20260915

No functional change. This release exists to carry a build fix and the
documentation correction that 0.2.4 shipped without a build of its own.

- **Build: `setup-android` no longer installs the legacy `tools` package.**
  `android-actions/setup-android` defaults to installing `tools`, which has
  been dropped from the cmdline-tools shipped on `ubuntu-24.04`. The step now
  fails before any build runs. 0.2.4 released green on 14 September; the
  runner image changed under it the next day, so this repo was one release
  away from being unbuildable without anything here having changed. Caught on
  a sibling plugin whose first release hit it.
- The corrected account of why 0.2.3's guard appeared not to fire — the
  `EACCES` was a stray file created by a diagnostic session, not an SELinux
  property — was committed after 0.2.4 was cut and so had never appeared in a
  released build. It is in this one.

## 0.2.4-20260914

- Widen the guard: it now refuses on **any** probe failure, not on a missing node specifically.

  The reasoning first published here was wrong, and is corrected in full because it would mislead anyone reasoning about `/dev` access later. This entry originally claimed 0.2.3's guard "never fired" because SELinux denies `untrusted_app` the lookup in `/dev`, so `open()` supposedly returns `EACCES` before it can discover a file is absent. That is not what happens. On a panel with genuinely no `/dev/ledjni`, the probe returns `ENOENT` exactly as 0.2.3 assumed, and 0.2.3's guard fires correctly.

  The `EACCES` (errno 13) that prompted the rewrite was self-inflicted: a diagnostic session opened `/dev/ledjni` read-write **as root** while testing, and `>` on a missing path creates it — leaving a stray zero-byte regular file labelled `device:s0` that the app could not open. The plugin was reading a mistake in the test environment, not a property of SELinux. Removing the stray file restored `ENOENT`.

  0.2.3's premise was therefore sound, and this release is a genuine widening rather than a correction of a broken guard.
- Widening it is still worth doing, for a case 0.2.3 did miss: a node that exists but cannot be opened, on a panel with no root fallback, cannot drive an LED either. 0.2.3 enabled there and reported an error; this release refuses, which is the condition actually meant — this plugin cannot open the device, so it cannot work.
- Three exemptions, all of which defer instead of refusing. Simulation mode never opens the device. Root fallback, when armed, defers to `detect()` — the root helper may reach a node this process cannot, and asking it during `start()` would block the host's enable call behind a root-manager prompt that can sit unanswered for minutes. A native library that will not load leaves the plugin unable to probe at all, which makes the question unanswerable rather than answered no.
- The refusal message names both escape hatches. A plugin's settings page stays reachable while it is disabled ("Enable this plugin from its entry row to run it"), so Root fallback and Simulation can both be switched on after a refusal — verified on a panel, since refusing otherwise risked stranding users outside the settings that would fix it.

## 0.2.3-20260914

- Change: a panel with no `/dev/ledjni` node can no longer enable this plugin at all. The hardware probe ran on the worker thread, after `start()` had already returned, so Kiosk Satellite recorded the plugin as enabled and the missing device showed up only as an error status — on a plugin that stayed switched on, kept re-probing hardware that will never appear, and offered settings that could never do anything. The probe now runs synchronously in `start()`, and a missing node throws; the host's `PluginBridge.enable` routes that through `fail()`, which writes `enabled=false` and keeps the message. Enabling the plugin on a panel without the LED driver now fails with an explanation instead of appearing to succeed.
- Only a genuinely absent node blocks enabling (`ENOENT`/`ENODEV`). A node that exists but is unreadable (`EACCES`) is untouched, because the root fallback may still reach it. Simulation mode is exempt and stays enableable on any panel — it never opens the device. A native library that will not load is also exempt: it leaves the plugin unable to probe, so the question has no answer and the old deferred reporting applies.

## 0.2.2-20260911

- Fix: enabling the plugin failed with "Failed requirement." on every real device. Cause: Kiosk Satellite's host caps a plugin's published light at 24 effects (`PluginBridge.kt`'s `publishLight`), and this plugin's 6 original + 19 ported effects totaled 25 — one over, with no error message attached to that check on the host side. Fixed by dropping "None" from the list published to Home Assistant's effect dropdown (24 entries); the host already accepts "None" as a light-state value even when it's absent from the declared list, so sending it (e.g. from this plugin's own on-device Settings screen) still works. The one real change: "None" (solid color, no animation) is no longer a pickable option in Home Assistant's effect dropdown specifically.

## 0.2.1-20260911

- Correction: an earlier attempt at this release used a 4-component date-based version (`2026.09.11.01`), which Kiosk Satellite's plugin manifest validator rejects (`FormatException: Invalid plugin ID or version`) — it requires 3-component semver, optionally with a `-suffix`. That broken release has been removed; this one embeds the date as a semver prerelease suffix instead.
- Metadata only otherwise: author field and AI-assisted note in the README.

## 0.2.0

### Added

- Port 19 additional effects from [davidcoulson/kiosk-satellite](https://github.com/davidcoulson/kiosk-satellite)'s `feature/rgb-led-support` branch: Sunrise/Sunset, Moonlight Glow, Lightning Storm, Wake-Up Alarm, Candle Flicker, Fairytwinkle, Fireworks Burst, Beacon Pulse, Heartbeat Pulse, Soft Glow, Rolling Fog (Pronounced), three Pacifica variants, three Aurora variants, Bubbles and Disco Sparkle — alongside this plugin's original six (None/Pulse/Blink/Rainbow/Candle/Random). Every ported effect is independently stateful (a fresh instance per selection, matching this plugin's own effect lifecycle) and covered by an elapsed-time sweep in `LedTest` checking every channel stays in range across each effect's full period, including the two slowest: Sunrise/Sunset's 300s cycle and Wake-Up Alarm's one-shot 600s ramp.

## Unreleased

- Plugin 0.2.5-20261019.1: the package build now includes `assets/`, so the icon added in 0.2.5-20261019 is in the ZIP (it was missing, which hid the plugin from Community Plugins).

- Plugin 0.2.5-20261019: adds an icon for Community Plugins and Browse plugins.

### Changed

- Document automatic setting saves and the on-device text edit dialog.

- Consolidate the manifest and interfaces into the first public SDK 1, including optional KS observations and transient controls. The LED plugin requests neither host capability.
- Document GitHub Actions releases as the installation path and local ZIPs as developer testing only.

- Document gesture assignments, drawer shortcuts and Home Assistant buttons for plugin actions.

- Build and attach plugin assets when a GitHub release is published instead of on pushes or pull requests. Keep manual runs on existing release tags and verify that the tag matches the manifest version.

### Fixed

- Handle dotted Android platform directories such as `android-37.0` without crashing. Release builds explicitly select Android 35 and tests cover mixed platform installations.

- Set up the Android SDK in GitHub Actions before installing build tools so `sdkmanager` is available on the runner.

## 0.1.0

### Added

- Initial SDK 1 plugin for panels exposing `/dev/ledjni`.
- Color picker, brightness, configurable channel drive and six effects.
- Direct JNI access and an optional root helper with bounded requests.
- Home Assistant RGB light with settings synchronization.
- Hardware status, RGB test and simulation mode.
- Standalone build tools, protocol tests and maintainer handoff documentation.
