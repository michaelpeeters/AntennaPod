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
