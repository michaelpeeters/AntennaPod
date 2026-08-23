package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import de.danoeh.antennapod.net.common.RedirectChecker;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class ExoPlayerUtils {
    private static volatile SimpleCache simpleCache;

    @OptIn(markerClass = UnstableApi.class)
    public static ExoPlayer buildPlayer(Context context) {
        if (simpleCache == null) {
            simpleCache = new SimpleCache(new File(context.getCacheDir(), "streaming"),
                    new LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024),
                    new StandaloneDatabaseProvider(context));
        }
        return new ExoPlayer.Builder(context)
                .setLoadControl(new VideoAwareLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMsForStreaming(
                                (int) TimeUnit.HOURS.toMillis(1),
                                (int) TimeUnit.HOURS.toMillis(3),
                                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                        .setBackBuffer((int) TimeUnit.MINUTES.toMillis(5), true)
                        .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
                        .build()))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), true)
                .setMediaSourceFactory(new ApMediaSourceFactory(context, simpleCache))
                .setSeekParameters(SeekParameters.EXACT)
                .setHandleAudioBecomingNoisy(UserPreferences.isPauseOnHeadsetDisconnect())
                .build();
    }

    /**
     * Caps the streaming buffer duration for video tracks: a 1h/3h duration target is fine for
     * audio (~57MB/hour at typical bitrates), but at video bitrates it can require buffering
     * hundreds of MB to reach the same time-based target. Audio-only streams keep the full target.
     */
    @OptIn(markerClass = UnstableApi.class)
    static final class VideoAwareLoadControl implements LoadControl {
        private static final long MAX_VIDEO_BUFFERED_DURATION_US = TimeUnit.MINUTES.toMicros(5);
        private final DefaultLoadControl delegate;
        private volatile boolean currentTrackSelectionHasVideo;

        VideoAwareLoadControl(DefaultLoadControl delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPrepared(PlayerId playerId) {
            delegate.onPrepared(playerId);
        }

        @Override
        public void onTracksSelected(LoadControl.Parameters parameters, TrackGroupArray trackGroups,
                ExoTrackSelection[] trackSelections) {
            boolean hasVideo = false;
            for (ExoTrackSelection trackSelection : trackSelections) {
                if (trackSelection != null
                        && MimeTypes.getTrackType(trackSelection.getSelectedFormat().sampleMimeType)
                                == C.TRACK_TYPE_VIDEO) {
                    hasVideo = true;
                    break;
                }
            }
            currentTrackSelectionHasVideo = hasVideo;
            delegate.onTracksSelected(parameters, trackGroups, trackSelections);
        }

        @Override
        public void onStopped(PlayerId playerId) {
            delegate.onStopped(playerId);
        }

        @Override
        public void onReleased(PlayerId playerId) {
            delegate.onReleased(playerId);
        }

        @Override
        public Allocator getAllocator(PlayerId playerId) {
            return delegate.getAllocator(playerId);
        }

        @Override
        public long getBackBufferDurationUs(PlayerId playerId) {
            return delegate.getBackBufferDurationUs(playerId);
        }

        @Override
        public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
            return delegate.retainBackBufferFromKeyframe(playerId);
        }

        @Override
        public boolean shouldContinueLoading(LoadControl.Parameters parameters) {
            if (currentTrackSelectionHasVideo && parameters.bufferedDurationUs >= MAX_VIDEO_BUFFERED_DURATION_US) {
                return false;
            }
            return delegate.shouldContinueLoading(parameters);
        }

        @Override
        public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
            return delegate.shouldStartPlayback(parameters);
        }

        @Override
        public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline,
                MediaSource.MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
            return delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
        }
    }

    public static void releaseCache() {
        if (simpleCache != null) {
            simpleCache.release();
            simpleCache = null;
        }
    }

    public static String translateErrorReason(@NonNull PlaybackException error, Context context) {
        if (NetworkUtils.wasDownloadBlocked(error)) {
            return context.getString(R.string.download_error_blocked);
        }

        Throwable cause = error.getCause();
        if (cause instanceof HttpDataSource.HttpDataSourceException) {
            if (cause.getCause() != null) {
                cause = cause.getCause();
            }
        }
        if (cause != null && "Source error".equals(cause.getMessage())) {
            cause = cause.getCause();
        }
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        } else if (error.getMessage() != null && cause != null) {
            return error.getMessage() + ": " + cause.getClass().getSimpleName();
        } else {
            return "Unknown error";
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public static class ApMediaSourceFactory implements MediaSource.Factory {
        private LoadErrorHandlingPolicy loadErrorHandlingPolicy;
        private DrmSessionManagerProvider drmSessionManagerProvider;
        private final DefaultExtractorsFactory extractorsFactory;
        private final DefaultMediaSourceFactory defaultFactory;
        private final Context context;
        private final SimpleCache simpleCache;
        private final ConcurrentHashMap<String, String> redirectCache = new ConcurrentHashMap<>();

        public ApMediaSourceFactory(Context context, SimpleCache simpleCache) {
            super();
            this.context = context;
            this.simpleCache = simpleCache;
            this.extractorsFactory = new DefaultExtractorsFactory();
            this.extractorsFactory.setConstantBitrateSeekingEnabled(true);
            this.extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);
            this.defaultFactory = new DefaultMediaSourceFactory(context, extractorsFactory);
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(
                @NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
            this.drmSessionManagerProvider = drmSessionManagerProvider;
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy policy) {
            this.loadErrorHandlingPolicy = policy;
            return this;
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
            defaultFactory.setDataSourceFactory(buildDataSourceFactory(mediaItem));
            if (loadErrorHandlingPolicy != null) {
                defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            }
            if (drmSessionManagerProvider != null) {
                defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
            }
            return defaultFactory.createMediaSource(mediaItem);
        }

        private DataSource.Factory buildDataSourceFactory(MediaItem mediaItem) {
            DefaultHttpDataSource.Factory httpDataSourceFactory =
                    new DefaultHttpDataSource.Factory();
            httpDataSourceFactory.setUserAgent(UserAgentInterceptor.USER_AGENT);
            httpDataSourceFactory.setAllowCrossProtocolRedirects(true);
            httpDataSourceFactory.setKeepPostFor302Redirects(true);
            String authHeader = mediaItem.requestMetadata.extras != null
                    ? mediaItem.requestMetadata.extras.getString(
                            MediaItemAdapter.KEY_AUTHORIZATION_HEADER)
                    : null;
            if (authHeader != null) {
                httpDataSourceFactory.setDefaultRequestProperties(
                        Collections.singletonMap("Authorization", authHeader));
            }
            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
            DataSource.Factory resolvingFactory = new ResolvingDataSource.Factory(dataSourceFactory, dataSpec -> {
                String originalUrl = dataSpec.uri.toString();
                if (!originalUrl.startsWith("http")) {
                    return dataSpec;
                }
                String resolvedUrl = redirectCache.get(originalUrl);
                if (resolvedUrl == null) {
                    resolvedUrl = RedirectChecker.getFinalUrl(originalUrl);
                    redirectCache.putIfAbsent(originalUrl, resolvedUrl);
                }
                if (resolvedUrl.equals(originalUrl)) {
                    return dataSpec;
                }
                return dataSpec.withUri(Uri.parse(resolvedUrl));
            });
            String uri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri.toString() : "";
            if (uri.startsWith("http")) {
                return new CacheDataSource.Factory()
                        .setCache(simpleCache)
                        .setUpstreamDataSourceFactory(resolvingFactory);
            }
            return resolvingFactory;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return defaultFactory.getSupportedTypes();
        }
    }
}
