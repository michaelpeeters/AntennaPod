package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterfaceStub;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences.EnqueueLocation;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
public class AutomaticDownloadAlgorithmTest {

    private Context context;
    private DownloadingStub downloadService;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        NetworkUtils.init(context);
        context.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING));
        downloadService = new DownloadingStub();
        DownloadServiceInterface.setImpl(downloadService);
        AutoDownloadManager.setInstance(mock(AutoDownloadManager.class));
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());

        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();

        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(UserPreferences.PREF_AUTODL_GLOBAL, true)
                .putBoolean(UserPreferences.PREF_ENABLE_AUTODL_ON_BATTERY, true)
                .putString(UserPreferences.PREF_EPISODE_CACHE_SIZE,
                        String.valueOf(UserPreferences.EPISODE_CACHE_SIZE_UNLIMITED))
                .commit();
        UserPreferences.setAllowMobileAutoDownload(true);
        UserPreferences.setEnqueueLocation(EnqueueLocation.FRONT);
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
        DBWriter.tearDownTests();
    }

    @Test
    public void testNewEpisodesAreQueuedOldestFirst() throws Exception {
        List<FeedItem> items = saveFeedWithNewItems("chapter 1", "chapter 2", "chapter 3");

        new AutomaticDownloadAlgorithm().autoDownloadUndownloadedItems(context).run();

        List<String> queue = new ArrayList<>();
        for (FeedItem item : DBReader.getQueue()) {
            queue.add(item.getTitle());
        }
        assertEquals(List.of("chapter 1", "chapter 2", "chapter 3"), queue);
        assertEquals(items.size(), downloadService.downloading.size());
    }

    private List<FeedItem> saveFeedWithNewItems(String... titles) {
        Feed feed = new Feed(0, null, "feed", "link", "descr", null, null,
                null, null, "id", null, null, "url", System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        long pubDate = new Date().getTime() - 60L * 60 * 1000;
        for (String title : titles) {
            FeedItem item = new FeedItem(0, title, title, "link", new Date(pubDate), FeedItem.NEW, feed);
            item.setMedia(new FeedMedia(item, "http://example.com/" + title, 1, "audio/mp3"));
            feed.getItems().add(item);
            pubDate += 60L * 1000;
        }
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();
        return feed.getItems();
    }

    private class DownloadingStub extends DownloadServiceInterfaceStub {
        final Set<String> downloading = new HashSet<>();

        @Override
        public void download(Context context, FeedItem item) {
            downloading.add(item.getMedia().getDownloadUrl());
            if (!item.isTagged(FeedItem.TAG_QUEUE) && UserPreferences.enqueueDownloadedEpisodes()) {
                try {
                    DBWriter.addQueueItem(context, item).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public boolean isDownloadingEpisode(String url) {
            return downloading.contains(url);
        }
    }
}
