# About this fork

`michaelpeeters/AntennaPod` is a personal, permanent fork used as a daily podcast app,
maintained with help from Claude Code. This file documents the fork's workflow and the
reasoning behind fork-only changes, for anyone (including future-me) who wants to understand
why `mine` differs from upstream.

## Branch structure

- `mine` is the branch actually used day to day. It tracks `origin/master` (AntennaPod's
  stable release branch), not `develop` — this is a daily-driver app, so stability outranks
  freshness.
- `.github/workflows/fork-rebase.yml` polls `upstream/master` daily (plus manual dispatch),
  rebases `mine` onto it, runs unit tests, and pushes only if green.
- On failure, the workflow files a `[fork-rebase-failure]` issue for the next weekly Claude
  session to pick up. This was silently broken for the workflow's first 3 historical failures
  (runs #3, #8, #9 — all otherwise already resolved by later commits: a flaky-test fix and a
  YAML indentation bug in the issue-filing step itself) because GitHub Issues were disabled on
  this repo by default; enabled directly (`gh repo edit --enable-issues`), so the mechanism
  works going forward.
- Personal patches land on `mine` as cherry-picks of finished commits from their own topic
  branch, not as fresh commits directly on `mine` and not as a branch merge.

## Releases / Obtainium

When `fork-rebase.yml` actually advances `mine` (i.e. upstream had new commits), it also
builds the PlayDebug variant and publishes it as a new GitHub Release tagged `fork-<run
number>` (e.g. `fork-42`), then prunes older `fork-*` releases down to the 3 most recent
(APK storage is the only real cost) — the new one is created *before* any old one is removed,
so there's never a window with no release at all. Nothing is published on a no-op rebase.

Pruning only deletes the *release* (and its APK asset), never the underlying git tag — every
`fork-<run number>` tag stays in the repo permanently, even once its release is gone. Tags are
just cheap refs, and keeping them means any past build can still be identified/reverted to (or
rebuilt from) by commit, even years later, without having to keep every APK around.

A per-build tag (rather than a single reused `fork-latest` tag edited in place) is
deliberate: Obtainium tracks a release's own GitHub metadata (tag/id/publish date), not the
APK's internal `versionCode`, so an in-place-edited release can never look "new" to it no
matter what changed inside the APK — confirmed the hard way (2026-08-25): a real fix was
built and uploaded, but Obtainium kept reporting "Installed / Latest" because the tag/release
identity never changed. `app/build.gradle`'s `versionCode`/`versionName` are also static
across all commits, so CI additionally bumps `versionCode` per build (base + `run_number`,
patched only in the workflow, never committed) as a secondary signal.

The APK is signed with a persistent debug keystore stored as the `FORK_DEBUG_KEYSTORE_B64`
repo secret (base64), restored to `~/.android/debug.keystore` before the build — the same
key used for local debug builds on this fork, so releases install as an in-place update
rather than requiring an uninstall/reinstall each time.

To track this fork's builds in [Obtainium](https://github.com/ImranR98/Obtainium), add
`https://github.com/michaelpeeters/AntennaPod` as a GitHub app source — Obtainium picks up
whichever `fork-*` release is newest automatically; no specific tag needs to be configured.

## Origin PR vs. fork-only

Two different kinds of change live here, and they're tracked differently:

- **Candidate for an upstream PR**: gets its own topic branch off the appropriate upstream
  base (e.g. `fix-video-freeze-buffer-control`), opened as a normal AntennaPod PR following
  upstream's contribution conventions. That branch/PR stays open (or stays available even if
  closed) independent of whatever ends up on `mine`, so a maintainer can still review or
  revisit it later.
- **Fork-only** (rejected upstream, or judged not worth proposing — e.g. because it diverges
  too far from upstream's own direction for the payoff): cherry-picked directly onto `mine`
  from its topic branch, permanently, regardless of the upstream PR's fate.

A change can be both at once: proposed upstream on its own branch/PR *and* cherry-picked onto
`mine` so it's not waiting on a maintainer decision to be usable day to day.

## Current fork-only changes

- **Buffer size/duration mismatch fix** (issue #8673 / PR #8674, closed upstream, not
  merged): video/audio playback would freeze in an infinite PLAYING/BUFFERING loop because
  `DefaultLoadControl` was configured with a 1–3h duration target but no matching byte
  budget. Fix: prioritize the duration target over the byte-size threshold
  (`setPrioritizeTimeOverSizeThresholds`). Kept on `mine` regardless of upstream's decision,
  along with a media3 1.11.0 bump (adds an OOM-avoidance heap-headroom fallback) and a
  Robolectric regression test (`BufferPriorityRegressionTest`) that reproduces the stall
  mechanism and proves the fix.
- **Hardware media-button (e.g. Bluetooth remote) skip fix** (PR #8671): needed no separate
  action — already merged into `origin/master`, so `mine` gets it automatically by tracking
  master.
- **Video screen-off-after-resume / non-fullscreen fix** (fork-only, from
  `fix-video-fullscreen-lock-resume`): reported symptom — pause a fullscreen video, wait, press
  play again: audio resumes but the screen stays black; unlocking shows the video but not
  fullscreen, and the screen goes dark again after a while (audio keeps playing). Root cause:
  `Media3VideoPlayerActivity` only synced `FLAG_KEEP_SCREEN_ON` reactively inside
  `Player.Listener.onIsPlayingChanged`, and only applied the immersive fullscreen UI flags once
  in `onCreate`. If the activity is stopped (screen locked) and playback resumes from the lock
  screen before the activity restarts, the controller is already playing by the time the
  listener reattaches in `onStart`, so `onIsPlayingChanged` never fires and the screen-on flag
  is never re-added; the fullscreen flags also never got reapplied on resume. Fix: explicitly
  sync `FLAG_KEEP_SCREEN_ON` with `mediaController.isPlaying()` when the controller reattaches
  in `onStart`, and reapply `setupFullScreenMode()` in `onResume`. Not proposed upstream yet
  (see Questions for review).

- **Buffer duration follow-ups** (cherry-picked from `buffer-control-followups`), two
  refinements on top of the buffer size/duration mismatch fix above:
  - splitting local/downloaded playback onto Media3's own (much smaller) local-playback buffer
    defaults instead of the streaming target, since local files don't need it;
  - capping the streaming buffer duration specifically when a video track is present, since the
    same duration target costs far more memory for video than audio.

  Promoted onto `mine` on 2026-09-08 after real-world evidence: a hard `OutOfMemoryError` crash
  (`FATAL EXCEPTION: ExoPlayer:Playback`, in `ExoPlayerImplInternal.shouldContinueLoading`) hit
  a real device (Moto G73 5G) while `Media3PlaybackService` was playing in the background during
  a mass feed refresh. The crash confirms the media3 1.11.0 heap-headroom guard alone isn't
  sufficient protection; couldn't conclusively confirm from on-device logs whether a video or
  audio stream was playing at the time (the per-process logcat ring had already rotated past the
  relevant lines), but this is exactly the scenario the deferred follow-ups were held back
  pending.

- **Anti-kill mitigations** (fork-only): `ExoPlayerWrapper` forces synchronous `MediaCodec`
  callbacks (avoids a native async-callback abort seen on-device) and keeps CPU/WiFi awake
  during screen-off streaming playback, to guard against the stall/kill risk documented in the
  anti-kill investigation below. Matches upstream issue #8666; not yet proposed upstream (see
  Questions for review). Does not address anti-kill cause 2 (foreground-service status
  genuinely dropping on pause) — still open.
- **Defer hourly feed refresh when battery is low** (fork-only): skips the periodic
  auto-refresh `WorkManager` job while the device reports low battery, motivated by the battery
  usage investigation below (possibly related to upstream issue #8185).
- **Mini player play/pause button missing for video episodes** (landed directly on `mine`,
  2026-09-13): `ExternalPlayerFragment.updateUi()` set `butPlay.setVisibility(View.GONE)`
  whenever the current episode's `MediaType` was `VIDEO`, so the bottom mini player showed the
  title/feed name but no play/pause control at all — confirmed on-device (Moto G73) via
  screenshot. This is a regression of upstream issue **#4223** (2020), originally fixed by PR
  **#4485**; that fix was lost when `4cc6a755e` ("Re-add skip silence setting to new playback
  service", PR #8308, Media3 rewrite) reintroduced the same `setVisibility(View.GONE)` line.
  Fix here just keeps `butPlay` visible for video too. Not upstream-specific in any way — a
  good candidate for an upstream PR (see Questions for review); kept on `mine` in the meantime
  since it's a plain regression fix with no fork-specific reasoning behind it.

## Investigation notes: the buffer freeze fix

### Why prioritize duration over byte-size

`DefaultLoadControl` normally stops loading once *either* its duration target or its byte
budget is hit, whichever comes first. With a 1–3h duration target but only Media3's default
byte budget (sized for a much shorter buffer), higher-bitrate content hits the byte cap almost
immediately — far short of the duration target — causing the player to stall, resume once
played-back bytes free up allocator space, then stall again: an infinite
PLAYING/BUFFERING loop.

### Why the local/streaming split (deferred branch)

`DefaultLoadControl.Builder` (media3 1.9.0+) has separate setters for local vs. streaming:
`setBufferDurationsMsForStreaming`/`ForLocalPlayback`,
`setPrioritizeTimeOverSizeThresholdsForStreaming`/`ForLocalPlayback`.
`DefaultLoadControl.isLocalPlayback()` picks the right one automatically, per
`LoadControl.Parameters`, by checking `Timeline.Window.mediaItem.localConfiguration.uri`'s
scheme against a `LOCAL_PLAYBACK_SCHEMES` allowlist (`file`, `content`, `data`,
`android.resource`, `rawresource`, `asset`) — no extra wiring needed.

This matters because the two objections raised on the upstream PR are both streaming-specific:
- **#7409** (playback jumps/skips on dynamic-ad-insertion servers): plausible real link to
  buffer *duration* — a short buffer spreads requests out over more real time, increasing the
  odds of hitting an ad server serving different content for the same nominal byte range. Only
  applies to network requests, i.e. streaming.
- **#8487** (re-buffer/slow restart after a short pause): the actual fix was the on-disk
  `SimpleCache`/`CacheDataSource` layer added in #8552, not the buffer duration bump that
  happened to land in the same commit — no evidence this is duration-dependent, and it doesn't
  apply to local files (already fully on disk).

Local/downloaded episodes are exposed to neither issue, which the split (plus Media3's
already-safe local defaults) fully addresses.

Also worth noting: `Media3PlaybackService` didn't exist yet in AntennaPod 3.11.4, so the 1h/3h
duration value's apparent track record from earlier versions never ran against the new
playback engine's media source/cache setup — it isn't evidence the value is safe here.

### Measured evidence (real device, flag-only fix + media3 1.11.0, before the local/streaming
split)

`dumpsys media_session` + `dumpsys meminfo` snapshot while paused mid-stream:
- Buffered position ~50 minutes ahead of playback position
- Total RSS ~773MB — higher than the ~723MB freezer-kill hit measured earlier without the
  media3 1.11.0 bump

This confirms the memory-cost concern raised upstream is real: the 1.11.0 heap-headroom
fallback only protects against a hard OOM kill, it doesn't stop the flag from growing buffer
size substantially under normal (non-memory-pressure) conditions.

### Why a video/audio duration split, not just local/streaming (deferred branch)

The per-hour memory cost differs enormously by media type: at typical bitrates, 1h buffered
is roughly 57MB for audio (128kbps) vs. potentially 1–2GB for video — the 773MB RSS measured
above is a video-buffering cost specifically. The bug itself is bitrate-driven, not
video-vs-audio categorical; Media3 already splits the *byte budget* per track type internally
(a much larger default for video than audio), so what's missing is a *duration* split by track
type, which `DefaultLoadControl` doesn't expose as a builder setting (only local vs. streaming
has dedicated setters).

Implementation constraint: `ExoPlayerUtils.buildPlayer(context)` is called once per playback
service instance, not per episode, so the `LoadControl` is fixed at player-build time — media
type isn't known until an episode is actually loaded. Two implementation paths exist:
1. Rebuild the player per episode based on its mime type/track content — simplest
   conceptually, but risks disrupting gapless-queue transitions; a much bigger behavior change
   than the rest of this fix. Higher regression risk.
2. A custom `LoadControl` (wrapping or subclassing `DefaultLoadControl`) that inspects the
   `TrackGroupArray`/`Format` passed into `onTracksSelected`/`shouldContinueLoading` at
   runtime — the same mechanism Media3 already uses internally for its own per-track-type byte
   budget — and picks a shorter duration target when a video track is present. More surgical,
   no player rebuild, but meaningfully more code than a config tweak.

`buffer-control-followups` implements path 2, with its own Robolectric coverage
(`VideoAwareLoadControlTest`).

Local/streaming and video/audio duration splits are not alternatives — they fix different,
non-overlapping cases. Local/streaming does nothing for a *streamed* video podcast, which is
exactly the case the 773MB RSS measurement above covers.

### `BufferPriorityRegressionTest` intermittent CI failures (2026-09-06)

`legacyBuffer_withPriorityFlag_keepsLoadingPastByteCapCheckpoint` failed twice in a row on
`fork-rebase.yml` CI (stalling around 374-375s instead of the ~600s checkpoint), while passing
consistently locally (6/6 runs, including once under real 8-core CPU contention) and on a
subsequent CI run. The test's own inline comment blamed "real elapsed wall-clock time," which
turned out to be wrong — bytecode inspection of media3 1.11.0's `DefaultLoadControl` (added by
this fork's own media3 bump above) showed `shouldContinueLoading()` only honors
`prioritizeTimeOverSizeThresholds` while a private `heapHasEnoughHeadroomForPrioritizeTimeOverSizeThreshold()`
guard holds: once the JVM heap has grown to its max size, it requires `freeMemory() +
allocator.getUnusedBytesAllocated() >= maxMemory() / 25` (4% of max heap), logging "Stopped
loading before minBufferUs reached due to memory pressure" when it doesn't.

A diagnostic step temporarily added to a throwaway branch confirmed the actual runner spec:
GitHub's public-repo `ubuntu-latest` runner gives 4 vCPUs and ~15GiB RAM, with the JVM's
ergonomic (unset `-Xmx`) default max heap coming out to ~3.9GB — plausible headroom on paper,
but `org.gradle.parallel=true` (set by this workflow) runs multiple modules' test JVMs
concurrently against that same fixed 15GiB budget, so any one JVM's actual free heap at the
moment of the check is contention-dependent, not deterministic. Confirmed borderline/intermittent
rather than a hard per-run cap: the test passed on the very next CI run with no code change.

Fix: gave `:playback:service`'s unit-test JVM an explicit `maxHeapSize = "2g"` (via
`testOptions.unitTests.all` in its `build.gradle`) so it doesn't have to rely on ergonomic
defaults shared with concurrent test JVMs, and corrected the test's inline comment to describe
the real mechanism instead of the wrong wall-clock guess.

## Investigation notes: anti-kill (playback process killed after pause)

Symptom (real device, Moto G73 5G, 2026-08-24): once playback pauses/stops, the app process
gets killed very quickly by the OS. Pressing play on a Bluetooth headphone/remote afterward
does nothing until the app is manually reopened — the media button never reaches a live
receiver.

Matches upstream issue **#8666** ("Earbud button playback resumption does not work after 1
minute", open as of 2026-09-08) almost exactly: pause playback, wait ~1 minute, the
notification/media-button play control stops working until the app is manually reopened.
Not yet linked/proposed upstream (see Questions for review) — this session's fixes
(`ffe96cf09` synchronous MediaCodec, `2b1ede38f` CPU/WiFi wake during screen-off streaming)
are relevant mitigations, but the deeper code-level gap (cause 2 below) is still open.

Two concrete causes confirmed directly on-device (`adb shell dumpsys deviceidle whitelist`,
plus reading `PlaybackServiceStateManager.java`):

1. **The app is not exempt from Doze/battery-optimization.** `dumpsys deviceidle whitelist`
   showed no entry for `de.danoeh.antennapod.debug` before this was manually added for
   testing. Nothing in the codebase ever requests this exemption (no
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` anywhere). On Motorola devices in particular
   (aggressive OEM-level standby/kill policies beyond stock AOSP Doze), an app with no
   exemption is a prime target for fast background kills.
2. **`prefPersistNotify` (default `true`, "persist notification" setting) does not actually
   keep the service protected.** In `PlaybackServiceStateManager.stopForeground()`
   (`playback/service/src/main/java/.../internal/PlaybackServiceStateManager.java`), pausing
   playback always calls `ServiceCompat.stopForeground(...)` — with `STOP_FOREGROUND_DETACH`
   when persist-notification is on, `STOP_FOREGROUND_REMOVE` when it's off — and either way
   sets `isInForeground = false`. `DETACH` only keeps the *notification* visible; it does not
   keep the service's actual Android foreground-service status, which is what protects the
   process from OS-level reclaiming. So even with the setting at its default, the process
   loses real foreground protection the instant playback pauses — the setting's name promises
   more than the code delivers.

Tested as a manual, reversible mitigation: `adb shell dumpsys deviceidle whitelist
+de.danoeh.antennapod.debug` (equivalent to enabling "Unrestricted battery usage" for the app
in Android Settings) — **resolved: option B (manual workaround, no code change), confirmed
active on-device.** This addresses the standard AOSP Doze whitelist only.

**Motorola devices need a second, separate exclusion.** On top of the AOSP Doze whitelist,
Moto phones ship a proprietary background-app killer, `com.motorola.batterycare`
(`smartbackground.activity.BackgroundSettingActivity`, exposed in Settings as "Smart Use" /
Battery Care → background app management), which is independent of `dumpsys deviceidle` and
not visible via `dumpsys`/`cmd appops` from adb. Confirmed via an on-device test: with the
process paused and backgrounded but *before* setting Smart Use, `adb shell am kill` could not
kill the process at all (`oom adj=50`, still protected under stock Android's own memory
management) — meaning the real-world kill reports are from this OEM layer, not from Doze or
from `stopForeground()`. Mitigation: set AntennaPod's Smart Use entry to **"Always allow."**
No adb equivalent exists — Smart Use only has a private `ContentProvider`
(`com.motorola.batterycare/.provider.SummaryProvider`, guarded by proprietary
`READ_MODE`/`WRITE_MODE` permissions not exposed to `shell`), so this is a **GUI-only**
setting. Expected to survive app updates (keyed by package name, same as the Doze whitelist
entry), resets only on uninstall or package-name change (e.g. debug vs. release variant).

This only addresses cause 1; cause 2 (foreground-service status genuinely dropping on pause)
is a separate, code-level gap. Note Android already has a built-in wake-without-foreground
hook for this: `MediaSession` registers a `PendingIntent` targeting a manifest-declared
`<receiver>` (AntennaPod has both `androidx.media3.session.MediaButtonReceiver` and the
legacy `de.danoeh.antennapod.playback.service.MediaButtonReceiver` in
`playback/service/src/main/AndroidManifest.xml`) with the platform's `MediaSessionManager`;
the OS can cold-start the app via that receiver on a media-button/BT AVRCP press without any
live foreground process, the same way a `BOOT_COMPLETED` receiver works. The likely remaining
gap isn't lack of foreground status — it's that `Media3PlaybackService.onDestroy()`
unconditionally calls `mediaSession.release()`, which actively unregisters that
receiver/session association with `MediaSessionManager` if `onDestroy()` runs as part of a
kill. Worth confirming with a real kill (now that both the Doze and Smart Use exclusions are
set) before deciding whether cause 2 needs any code change at all — candidates if it does:
requesting the battery-optimization exemption from within the app (with user consent), a
bounded foreground grace period after pause, or guarding the `release()` call in `onDestroy()`
so a kill doesn't tear down the receiver registration. Needs upstream-compatibility judgment
before implementing — this touches core service lifecycle behavior shared with stock
AntennaPod, not a fork-only corner.

## Investigation notes: battery usage

Plausibly related to upstream issue **#8185** ("Very High Battery Drain reported in Android
Battery Setting", open as of 2026-09-08) — reported drain is mostly background/screen-off
listening time, consistent with the wakelock/buffer findings below. Less exact a match than
#8666 above (drain vs. specifically background-kill); not yet linked/proposed upstream.

Read-only investigation of `:playback:service` (the active `Media3PlaybackService`/
`ExoPlayerWrapper` implementation; the legacy `PlaybackService`/`LocalPSMP` classes throw if
started and are effectively dead code).

**Wakelocks**: no explicit wakelock or WiFi lock is acquired anywhere in the active code path.
`ExoPlayerWrapper.createPlayer()` builds the `ExoPlayer` without calling `setWakeMode(...)`, so
it defaults to `C.WAKE_MODE_NONE`. Good (no leaked/long-held wakelock risk), but also no safety
net if the device's CPU/WiFi sleeps mid-buffer during screen-off streaming — a playback-stall
risk adjacent to the anti-kill issue above, not itself a drain. The only wifi-lock code left in
the tree is in the unused legacy `LocalPSMP`.

**Notification/position-update frequency**: the foreground notification uses Media3's
`DefaultMediaNotificationProvider`, which only rebuilds on actual player state/metadata
changes, not on a per-second tick — battery-friendly. A 1-second `Observable.interval` position
observer does run while playing (posts an EventBus event each tick, and checks — cheaply, via
an early-return when no widget is enabled — whether to update the home-screen widget), but the
actual DB position write is throttled separately to every 5s (`POSITION_SAVE_INTERVAL_MS`), not
every tick.

**Background jobs**: all periodic background work uses WorkManager, not raw `AlarmManager`, and
is already infrequent/constraint-respecting: hourly feed auto-refresh (network-constrained),
DB export and DB maintenance every 3 days. None of this is a red flag on its own.

**GPS/sensors**: no location/Bluetooth/camera APIs in the playback path. One `SensorManager`
accelerometer listener (`ShakeListener`) is used only while a sleep timer is active, and is
unregistered on pause — minor, opt-in, bounded.

**Buffer size tradeoff**: the larger `DefaultLoadControl` buffer documented above (fewer,
larger network fetches instead of frequent small ones) is a plausible battery-*positive*
tradeoff against its RSS memory cost, since it can reduce radio/WiFi wake-ups per hour of
playback.

### Candidate improvements

1. ~~Explicitly set `exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK)`~~ — **implemented**: so
   screen-off streaming doesn't stall/retry due to CPU/WiFi sleep, instead of relying entirely
   on implicit foreground-service protection (which the anti-kill investigation above shows is
   already unreliable on pause).
2. Consider relaxing the 1s position-observer cadence to match the 5s DB-save interval when the
   app UI isn't visible, since the EventBus position broadcast is only needed for UI. **Revisited
   2026-09-09: not a safe quick fix as originally scoped.** The same 1s tick in
   `Media3PlaybackService.setupPositionObserver()` also drives `SkipUtils.skipEndingIfNecessary()`,
   which fires only within a narrow (~1s, speed-scaled) window before the configured skip point —
   unlike the EventBus/widget broadcast, this has nothing to do with UI visibility, so relaxing the
   whole tick to 5s while backgrounded would very likely make "skip ending" silently stop working
   for background playback, the most common case for audio podcasts. There's also no existing
   "UI visible" signal inside the playback service to gate on (no controller-connection or
   activity-lifecycle tracking) — that would need new plumbing. Would need `skipEndingIfNecessary`
   decoupled onto its own independent 1s timer before the EventBus/widget cadence could be safely
   relaxed. Still not implemented; now correctly scoped as a larger change than originally
   described.
3. Delete the dead `LocalPSMP`/legacy `PlaybackService` wifi-lock code during a future cleanup,
   to avoid confusion (not urgent, not user-facing). Not implemented.
4. ~~Verify/add `Constraints.Builder().setRequiresBatteryNotLow(true)`~~ — **implemented**: on
   the hourly feed-refresh `PeriodicWorkRequest`, so refreshes defer under low battery.

Items 2 and 3 remain investigation-only write-ups, same as the anti-kill section above.

## Questions for review

- ~~GitHub Issues were disabled on this repo, so `[fork-rebase-failure]`/`[claude-question]`
  issue filing silently failed every time~~ — **resolved**: Issues enabled directly via
  `gh repo edit --enable-issues`. The existing `gh issue create` step in `fork-rebase.yml`
  needs no further change; the drafted `fork-rebase-status`-branch fallback a session wrote
  for this is unnecessary now and wasn't applied.
- ~~Should `SynchronizationCredentials` (gpodder.net username/password) be included in the
  DB/preferences export?~~ — **resolved: no.** It's the only actual credential among the
  exported preferences, and the export is a plaintext SQLite file that could end up copied to
  cloud storage, email, etc. Keep excluding it (current behavior). Moot in practice for now
  since this fork doesn't use gpodder sync anyway.
- ~~This session's Gradle builds were blocked entirely (dl.google.com 403)~~ — **resolved**:
  the environment's egress allowlist now includes `dl.google.com`, `plugins.gradle.org`,
  `repo.maven.apache.org`, and `services.gradle.org`. Confirmed working: a from-scratch Android
  SDK install (`cmdline-tools`, `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`
  under a session-local `sdk.dir`, since no SDK ships in this environment) plus
  `:storage:importexport:test` and `:app:assembleDebug` both pass. Note for future sessions
  here: `maven.google.com` is *not* a usable fallback if `dl.google.com` is ever blocked again —
  it 301-redirects every artifact request straight to `dl.google.com`, so it fails identically.
- **Video screen-off/non-fullscreen fix**: kept fork-only for now (2026-09-06), not filed as an
  upstream issue or PR yet. No matching open upstream issue was found — the closest is #7933
  ("Playback behaviour when locking phone different for audio vs video podcasts", closed as a
  duplicate with no linked issue), which is adjacent (video + lock screen) but doesn't describe
  this exact symptom. Revisit filing upstream once this has some real-world runtime on-device.
- **Anti-kill fixes vs. #8666**: matching upstream issue identified (2026-09-08), but not
  proposing a PR yet — the two mitigations already on `mine` (synchronous MediaCodec, CPU/WiFi
  wake) are unconfirmed as a full fix, and the deeper code-level gap (cause 2: foreground-service
  status genuinely dropping on pause) still needs upstream-compatibility judgment before any
  code change. Deliberately holding off proposing anything upstream until there's more
  confidence/runtime behind it.
- **DB+preferences export**: landed on `mine` (cherry-picked from the `db-preferences-export`
  topic branch, now verified — build and tests green). The `SynchronizationCredentials`
  question above covers this feature's only open credential question (resolved: no). Current
  implementation only exports the default SharedPreferences file and `SleepTimerPreferences`;
  `SynchronizationSettings` (non-credential sync config) and `UsageStatistics` were also left
  out as lower-value, not for privacy reasons — happy to add either on request.
- **Mini player play/pause button fix vs. #4223**: landed directly on `mine` (2026-09-13), not
  proposed upstream yet. Good upstream PR candidate — plain regression fix of previously-fixed
  upstream behavior, no fork-specific reasoning. The original PR #4485 also made tapping play on
  a paused video open the video activity directly (not just toggle in place); this fix doesn't
  restore that extra behavior, only the button's visibility/toggle. Revisit both before
  proposing upstream.
