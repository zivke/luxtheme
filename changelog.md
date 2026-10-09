# Changelog

All notable user-visible changes to Lux Theme. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions are the
`versionName` in `AndroidManifest.xml`.

## [Unreleased]

## [1.4.0] - 2026-10-09

### Added
- "About" in the menu (⋮ at the top right): version, what the app does,
  licence, links to the source code and to donations, and the disclaimer.

## [1.3.0] - 2026-10-09

### Fixed
- A countdown could survive the light level going back to the other side for
  longer than 3 seconds while the device was asleep, and then switch as soon
  as it woke. It now starts over, and the event list says so.
- After the device slept, a countdown could finish late because its timer did
  not count the time asleep. It is re-checked when the screen turns on.
- A failed switch no longer throws away a countdown back to the current theme
  that started while the root command was running.
- Light levels below 1 lux are shown with two decimals instead of as `0.0`.
- A countdown restored from a backup on another device could be picked up as
  if it were still running. That state is no longer backed up.

## [1.2.0] - 2026-10-09

### Added
- "Recent events" list at the bottom of the screen: when the monitor started
  (and whether the previous run had been killed), countdowns started and
  cancelled with the light reading that caused it, switches and failures. It
  is kept across restarts and also written to logcat under the tag `LuxTheme`.

### Changed
- A countdown is no longer cancelled by a single reading on the other side.
  The light level has to stay there for 3 seconds (a quarter of the debounce
  time if that is shorter). Shorter interruptions are ignored and counted in
  the event list.

### Fixed
- A running countdown was lost when Android killed and restarted the monitor,
  so it began again from the full debounce time. It is now saved and carries
  on from where it was.

## [1.1.0] - 2026-10-08

### Changed
- The debounce time is entered in seconds instead of minutes (default 300).
  A value saved by 1.0.0 is converted.

## [1.0.0] - 2026-10-08

### Added
- Switches the system theme to dark when the light sensor reading stays below
  a threshold for the whole debounce time, and back to light when it stays at
  or above it. One reading on the other side restarts the countdown.
- Settings screen with the live light level, the threshold in lux (default
  10), the debounce time, and an on/off switch.
- "Dark now" and "Light now" buttons to check that root access works.
- Foreground service with a permanent notification that keeps watching in the
  background and restarts after a reboot.
- A failed switch is shown in the app and the notification and retried after
  the next debounce period.
