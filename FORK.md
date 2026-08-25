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
- On failure, the workflow tries to file a `[fork-rebase-failure]` issue for the next weekly
  Claude session to pick up — but **GitHub Issues are disabled on this repo** (confirmed: `gh
  issue create`/`list_issues` return `410 Issues has been disabled`, silently, i.e. `list`
  returns an empty result rather than erroring). This was true for all 3 of the workflow's
  historical failures so far (runs #3, #8, #9 — all otherwise already resolved by later commits:
  a flaky-test fix and a YAML indentation bug in the issue-filing step itself), so the
  issue-based notification path has never actually worked. A fix is written (see Questions for
  review below) that has the failure step also write `FORK_REBASE_FAILURE.md` to a dedicated
  `fork-rebase-status` branch (force-pushed, never touches `mine`) as a fallback that doesn't
  depend on Issues being enabled — but it isn't pushed to `fork-rebase.yml` yet, since this
  session's credentials lack the `workflow` OAuth scope needed to modify files under
  `.github/workflows/`.
- Personal patches land on `mine` as cherry-picks of finished commits from their own topic
  branch, not as fresh commits directly on `mine` and not as a branch merge.

## Releases / Obtainium

When `fork-rebase.yml` actually advances `mine` (i.e. upstream had new commits), it also
builds the PlayDebug variant and publishes it to a single rolling GitHub Release tagged
`fork-latest` — the previous release/tag is deleted first, so there's always exactly one
release, not one per rebase. Nothing is published on a no-op rebase.

The APK is signed with a persistent debug keystore stored as the `FORK_DEBUG_KEYSTORE_B64`
repo secret (base64), restored to `~/.android/debug.keystore` before the build — the same
key used for local debug builds on this fork, so releases install as an in-place update
rather than requiring an uninstall/reinstall each time.

To track this fork's builds in [Obtainium](https://github.com/ImranR98/Obtainium), add
`https://github.com/michaelpeeters/AntennaPod` as a GitHub app source — Obtainium finds
`fork-latest` and its APK asset automatically.

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

### Deferred, not on `mine` yet

A further branch, `buffer-control-followups`, has two more refinements on top of the above,
both implemented and tested:
- splitting local/downloaded playback onto Media3's own (much smaller) local-playback buffer
  defaults instead of the streaming target, since local files don't need it;
- capping the streaming buffer duration specifically when a video track is present, since the
  same duration target costs far more memory for video than audio.

Left off `mine` for now: they diverge further from upstream for benefit that's currently more
theoretical than demonstrated. Revisit if real-world evidence (e.g. actual OOM/freeze reports
on video streams) justifies it.

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

## Investigation notes: anti-kill (playback process killed after pause)

Symptom (real device, Moto G73 5G, 2026-08-24): once playback pauses/stops, the app process
gets killed very quickly by the OS. Pressing play on a Bluetooth headphone/remote afterward
does nothing until the app is manually reopened — the media button never reaches a live
receiver.

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
in Android Settings). Not yet confirmed whether this alone resolves the symptom in practice —
needs a real-world pause/resume-via-headphone test over the following days. This only
addresses cause 1; cause 2 (foreground-service status genuinely dropping on pause) is a
separate, code-level gap and needs its own fix — candidates worth evaluating: requesting the
battery-optimization exemption from within the app (with user consent, since this is a
system permission prompt) so it isn't dependent on a manual device setting, and/or keeping a
lighter-weight but still-alive component (e.g. a shorter-lived foreground grace period, or
relying on `MediaSessionService`'s own lifecycle rather than manual `stopForeground` calls)
so a paused-but-resumable session survives long enough for a media-button press to reach it.
Needs upstream-compatibility judgment before implementing — this touches core service
lifecycle behavior shared with stock AntennaPod, not a fork-only corner.

## Investigation notes: battery usage

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
   app UI isn't visible, since the EventBus position broadcast is only needed for UI. Not
   implemented.
3. Delete the dead `LocalPSMP`/legacy `PlaybackService` wifi-lock code during a future cleanup,
   to avoid confusion (not urgent, not user-facing). Not implemented.
4. ~~Verify/add `Constraints.Builder().setRequiresBatteryNotLow(true)`~~ — **implemented**: on
   the hourly feed-refresh `PeriodicWorkRequest`, so refreshes defer under low battery.

Items 2 and 3 remain investigation-only write-ups, same as the anti-kill section above.

## Questions for review

- **GitHub Issues are disabled on this repo** (see Branch structure above) — normally these
  questions would each get filed as a `[claude-question]` issue per the weekly-session
  workflow, but `gh issue create` returns `410 Issues has been disabled`. Enabling Issues is a
  one-click toggle under repo Settings → General → Features, if you want the issue-based
  workflow to actually work going forward; otherwise these questions will keep landing here in
  FORK.md instead, which is easier to miss. Your call — no strong reason to prefer either, given
  this is a private/personal fork.
- **The `fork-rebase-status` fallback fix (above) is written and YAML-validated, but couldn't be
  pushed this session**: `git push` to `.github/workflows/fork-rebase.yml` was rejected with
  `refusing to allow an OAuth App to create or update workflow ... without workflow scope`, and
  the GitHub API (`push_files`/`create_or_update_file`) hit the same restriction (404 on the
  tree write). Neither this session's git credentials nor its GitHub App token can modify files
  under `.github/workflows/` — that needs a token with the `workflow` OAuth scope, which a
  Claude Code session apparently isn't granted here by default. The fix is fully written below;
  apply it directly (e.g. paste as a patch, or have a session with `workflow` scope push it) —
  replace the final `File an issue on failure` step in `fork-rebase.yml` with:

  ```yaml
      - name: Record failure for review
        if: failure()
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          MARKER="[fork-rebase-failure]"
          RUN_URL="${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}"
          EXISTING=$(gh issue list --repo "${{ github.repository }}" --search "$MARKER in:title is:open" --json number -q '.[0].number' 2>/dev/null || true)
          if [ -z "$EXISTING" ]; then
            BODY=$'Run: '"$RUN_URL"$'\nTrigger: ${{ github.event_name }}\nNeeds review at the next weekly Claude session (or sooner if urgent).'
            gh issue create --repo "${{ github.repository }}" \
              --title "$MARKER Rebase/build failed on $(date -u +%Y-%m-%d)" \
              --body "$BODY" 2>&1 || echo "Issue creation failed (Issues may be disabled on this repo) -- falling back to FORK_REBASE_FAILURE.md on the fork-rebase-status branch."
          else
            echo "Open failure issue #$EXISTING already exists, not creating a duplicate."
          fi
          git fetch origin mine
          git checkout -B fork-rebase-status origin/mine
          printf '# Fork rebase failure\n\nDetected: %s\nRun: %s\nTrigger: %s\n\nNeeds review at the next weekly Claude session (or sooner if urgent).\nThis file/branch is a fallback for GitHub issue notifications, which are disabled on this repo.\n' \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RUN_URL" "${{ github.event_name }}" > FORK_REBASE_FAILURE.md
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add FORK_REBASE_FAILURE.md
          git commit -m "Record fork-rebase failure: $RUN_URL"
          git push --force origin fork-rebase-status
  ```

  Until this lands, the failure-notification path stays broken (both the issue-based one, since
  Issues are disabled, and this fallback, since it isn't pushed yet) — the only way to notice a
  real rebase/build failure right now is checking the Actions tab directly, or a weekly session
  doing so proactively rather than trusting a notification to exist.
- ~~This session's Gradle builds were blocked entirely (dl.google.com 403)~~ — **resolved**:
  the environment's egress allowlist now includes `dl.google.com`, `plugins.gradle.org`,
  `repo.maven.apache.org`, and `services.gradle.org`. Confirmed working: a from-scratch Android
  SDK install (`cmdline-tools`, `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`
  under a session-local `sdk.dir`, since no SDK ships in this environment) plus
  `:storage:importexport:test` and `:app:assembleDebug` both pass. Note for future sessions
  here: `maven.google.com` is *not* a usable fallback if `dl.google.com` is ever blocked again —
  it 301-redirects every artifact request straight to `dl.google.com`, so it fails identically.
- **DB+preferences export**: landed on `mine` (cherry-picked from the `db-preferences-export`
  topic branch, now verified — build and tests green). Still open: should
  `SynchronizationCredentials` (the gpodder.net username/password, currently excluded) be
  included in the exported preferences table? It's the only preference data that's an actual
  credential rather than a setting, and the export is a plaintext SQLite file that could end up
  copied to cloud storage, email, etc. Current implementation only exports the default
  SharedPreferences file and `SleepTimerPreferences`; `SynchronizationSettings` (non-credential
  sync config) and `UsageStatistics` were also left out as lower-value, not for privacy
  reasons — happy to add either on request.
