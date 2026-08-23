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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class VideoAwareLoadControlTest {
    private static final long VIDEO_CAP_US = TimeUnit.MINUTES.toMicros(5);

    @Test
    public void videoTrack_stopsLoadingAtVideoCapEvenThoughDelegateWouldContinue() {
        boolean shouldContinue = shouldContinueLoadingAt(MimeTypes.VIDEO_H264, VIDEO_CAP_US);

        assertFalse("Expected video buffering to stop once the 5-minute cap is reached", shouldContinue);
    }

    @Test
    public void audioTrack_ignoresVideoCapAndDefersToDelegate() {
        boolean shouldContinue = shouldContinueLoadingAt(MimeTypes.AUDIO_MPEG, VIDEO_CAP_US + TimeUnit.MINUTES.toMicros(1));

        assertTrue("Audio-only playback should not be capped at the video buffer limit", shouldContinue);
    }

    private boolean shouldContinueLoadingAt(String sampleMimeType, long bufferedDurationUs) {
        DefaultLoadControl delegate = new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(
                        (int) TimeUnit.HOURS.toMillis(1), (int) TimeUnit.HOURS.toMillis(3),
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
                .build();
        ExoPlayerUtils.VideoAwareLoadControl loadControl = new ExoPlayerUtils.VideoAwareLoadControl(delegate);

        PlayerId playerId = new PlayerId(/* playerName= */ "");
        loadControl.onPrepared(playerId);

        MediaItem mediaItem = MediaItem.fromUri("https://example.com/test");
        Timeline timeline = new SinglePeriodTimeline(10_000_000L, true, true, false, null, mediaItem);
        MediaSource.MediaPeriodId mediaPeriodId =
                new MediaSource.MediaPeriodId(timeline.getPeriod(0, new Timeline.Period(), true).uid);
        TrackGroup trackGroup = new TrackGroup(new Format.Builder().setSampleMimeType(sampleMimeType).build());
        TrackGroupArray trackGroupArray = new TrackGroupArray(trackGroup);

        loadControl.onTracksSelected(
                new LoadControl.Parameters(playerId, timeline, mediaPeriodId,
                        0L, 0L, 1.0f, true, false, C.TIME_UNSET, C.TIME_UNSET),
                trackGroupArray,
                new ExoTrackSelection[] {new FixedTrackSelection(trackGroup, 0)});

        return loadControl.shouldContinueLoading(
                new LoadControl.Parameters(playerId, timeline, mediaPeriodId,
                        0L, bufferedDurationUs, 1.0f, true, false, C.TIME_UNSET, C.TIME_UNSET));
    }
}
