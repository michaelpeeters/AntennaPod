package de.danoeh.antennapod.playback.service.internal;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class BufferPriorityRegressionTest {
    private static final int SEGMENT_SIZE = 1024;
    private static final long SIMULATED_BYTES_PER_SECOND = 5_000_000L / 8;

    @Test
    public void legacyBuffer_withoutPriorityFlag_stallsFarBelowConfiguredMinimum() {
        long minBufferUs = TimeUnit.HOURS.toMicros(1);
        long stalledAtUs = simulateVideoLoad(
                (int) TimeUnit.HOURS.toMillis(1), (int) TimeUnit.HOURS.toMillis(3), false, minBufferUs,
                MediaItem.EMPTY);

        assertTrue("Expected to stall on the byte budget long before the 1h duration target; stalled at "
                + TimeUnit.MICROSECONDS.toSeconds(stalledAtUs) + "s", stalledAtUs < minBufferUs);
    }

    @Test
    public void legacyBuffer_withPriorityFlag_keepsLoadingPastByteCapCheckpoint() {
        long checkpointUs = TimeUnit.MINUTES.toMicros(10);
        long stalledAtUs = simulateVideoLoad(
                (int) TimeUnit.HOURS.toMillis(1), (int) TimeUnit.HOURS.toMillis(3), true, checkpointUs,
                MediaItem.EMPTY);

        // DefaultLoadControl factors in real elapsed wall-clock time internally, so under CPU
        // load the checkpoint can be reached a little early; allow some tolerance for that.
        long toleranceUs = TimeUnit.SECONDS.toMicros(90);
        assertTrue("Expected to keep loading up to (near) the " + TimeUnit.MICROSECONDS.toSeconds(checkpointUs)
                + "s checkpoint; stalled at " + TimeUnit.MICROSECONDS.toSeconds(stalledAtUs) + "s",
                stalledAtUs >= checkpointUs - toleranceUs);
    }

    @Test
    public void localPlayback_ignoresStreamingConfig_stallsAtMedia3LocalDefault() {
        long streamingTargetUs = TimeUnit.HOURS.toMicros(1);
        long stalledAtUs = simulateVideoLoad(
                (int) TimeUnit.HOURS.toMillis(1), (int) TimeUnit.HOURS.toMillis(3), true, streamingTargetUs,
                MediaItem.fromUri("file:///test.mp4"));

        assertTrue("Expected local playback to stall at Media3's own local-playback default, "
                + "not the streaming target; stalled at " + TimeUnit.MICROSECONDS.toSeconds(stalledAtUs) + "s",
                stalledAtUs < streamingTargetUs);
        assertEquals(TimeUnit.MILLISECONDS.toMicros(DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS),
                stalledAtUs);
    }

    private long simulateVideoLoad(int minBufferMs, int maxBufferMs, boolean prioritizeTime, long capUs,
            MediaItem mediaItem) {
        DefaultAllocator allocator = new DefaultAllocator(true, SEGMENT_SIZE);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMsForStreaming(minBufferMs, maxBufferMs,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(prioritizeTime)
                .build();
        PlayerId playerId = new PlayerId(/* playerName= */ "");
        loadControl.onPrepared(playerId);

        Timeline timeline = new SinglePeriodTimeline(10_000_000L, true, true, false, null, mediaItem);
        MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(
                timeline.getPeriod(0, new Timeline.Period(), true).uid);
        TrackGroup videoTrackGroup =
                new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
        TrackGroupArray videoTrackGroupArray = new TrackGroupArray(videoTrackGroup);

        loadControl.onTracksSelected(
                new LoadControl.Parameters(playerId, timeline, mediaPeriodId,
                        0L, 0L, 1.0f, true, false, C.TIME_UNSET, C.TIME_UNSET),
                videoTrackGroupArray,
                new ExoTrackSelection[] {new FixedTrackSelection(videoTrackGroup, 0)});

        Allocator playerAllocator = loadControl.getAllocator(playerId);
        long bufferedDurationUs = 0;
        while (bufferedDurationUs < capUs) {
            bufferedDurationUs += TimeUnit.SECONDS.toMicros(1);
            long bytesNeeded = SIMULATED_BYTES_PER_SECOND * TimeUnit.MICROSECONDS.toSeconds(bufferedDurationUs);
            while (playerAllocator.getTotalBytesAllocated() < bytesNeeded) {
                playerAllocator.allocate();
            }

            boolean shouldContinue = loadControl.shouldContinueLoading(
                    new LoadControl.Parameters(playerId, timeline, mediaPeriodId,
                            0L, bufferedDurationUs, 1.0f, true, false, C.TIME_UNSET, C.TIME_UNSET));
            if (!shouldContinue) {
                return bufferedDurationUs;
            }
        }
        return bufferedDurationUs;
    }
}
