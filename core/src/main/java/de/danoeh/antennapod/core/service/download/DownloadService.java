package de.danoeh.antennapod.core.service.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import de.danoeh.antennapod.core.ClientConfig;
import de.danoeh.antennapod.core.R;
import de.danoeh.antennapod.core.event.DownloadEvent;
import de.danoeh.antennapod.core.event.FeedItemEvent;
import de.danoeh.antennapod.core.feed.Feed;
import de.danoeh.antennapod.core.feed.FeedItem;
import de.danoeh.antennapod.core.feed.FeedMedia;
import de.danoeh.antennapod.core.preferences.GpodnetPreferences;
import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.service.GpodnetSyncService;
import de.danoeh.antennapod.core.service.download.handler.FailedDownloadHandler;
import de.danoeh.antennapod.core.service.download.handler.FeedSyncThread;
import de.danoeh.antennapod.core.service.download.handler.MediaDownloadedHandler;
import de.danoeh.antennapod.core.storage.DBReader;
import de.danoeh.antennapod.core.storage.DBTasks;
import de.danoeh.antennapod.core.storage.DBWriter;
import de.danoeh.antennapod.core.storage.DownloadRequester;
import de.danoeh.antennapod.core.util.DownloadError;
import de.danoeh.antennapod.core.util.gui.NotificationUtils;
import org.apache.commons.io.FileUtils;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the download of feedfiles in the app. Downloads can be enqueued via the startService intent.
 * The argument of the intent is an instance of DownloadRequest in the EXTRA_REQUEST field of
 * the intent.
 * After the downloads have finished, the downloaded object will be passed on to a specific handler, depending on the
 * type of the feedfile.
 */
public class DownloadService extends Service {
    private static final String TAG = "DownloadService";

    /**
     * Cancels one download. The intent MUST have an EXTRA_DOWNLOAD_URL extra that contains the download URL of the
     * object whose download should be cancelled.
     */
    public static final String ACTION_CANCEL_DOWNLOAD = "action.de.danoeh.antennapod.core.service.cancelDownload";

    /**
     * Cancels all running downloads.
     */
    public static final String ACTION_CANCEL_ALL_DOWNLOADS = "action.de.danoeh.antennapod.core.service.cancelAllDownloads";

    /**
     * Extra for ACTION_CANCEL_DOWNLOAD
     */
    public static final String EXTRA_DOWNLOAD_URL = "downloadUrl";

    /**
     * Extra for ACTION_ENQUEUE_DOWNLOAD intent.
     */
    public static final String EXTRA_REQUEST = "request";

    /**
     * Contains all completed downloads that have not been included in the report yet.
     */
    private List<DownloadStatus> reportQueue;

    private ExecutorService syncExecutor;
    private CompletionService<Downloader> downloadExecutor;
    private FeedSyncThread feedSyncThread;

    private DownloadRequester requester;


    private NotificationCompat.Builder notificationCompatBuilder;
    private static final int NOTIFICATION_ID = 2;
    private static final int REPORT_ID = 3;

    /**
     * Currently running downloads.
     */
    private List<Downloader> downloads;

    /**
     * Number of running downloads.
     */
    private AtomicInteger numberOfDownloads;

    /**
     * True if service is running.
     */
    public static boolean isRunning = false;

    private Handler handler;

    private NotificationUpdater notificationUpdater;
    private ScheduledFuture<?> notificationUpdaterFuture;
    private static final int SCHED_EX_POOL_SIZE = 1;
    private ScheduledThreadPoolExecutor schedExecutor;

    private final Handler postHandler = new Handler();

    private final IBinder mBinder = new LocalBinder();

    private class LocalBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }

    private final Thread downloadCompletionThread = new Thread("DownloadCompletionThread") {
        private static final String TAG = "downloadCompletionThd";

        @Override
        public void run() {
            Log.d(TAG, "downloadCompletionThread was started");
            while (!isInterrupted()) {
                try {
                    Downloader downloader = downloadExecutor.take().get();
                    Log.d(TAG, "Received 'Download Complete' - message.");
                    removeDownload(downloader);
                    DownloadStatus status = downloader.getResult();
                    boolean successful = status.isSuccessful();

                    final int type = status.getFeedfileType();
                    if (successful) {
                        if (type == Feed.FEEDFILETYPE_FEED) {
                            Log.d(TAG, "Handling completed Feed Download");
                            feedSyncThread.submitCompletedDownload(downloader.getDownloadRequest());
                        } else if (type == FeedMedia.FEEDFILETYPE_FEEDMEDIA) {
                            Log.d(TAG, "Handling completed FeedMedia Download");
                            syncExecutor.execute(() -> {
                                MediaDownloadedHandler handler = new MediaDownloadedHandler(DownloadService.this,
                                        status, downloader.getDownloadRequest());
                                handler.run();
                                saveDownloadStatus(handler.getUpdatedStatus());
                                numberOfDownloads.decrementAndGet();
                                queryDownloadsAsync();
                            });
                        }
                    } else {
                        numberOfDownloads.decrementAndGet();
                        if (!status.isCancelled()) {
                            if (status.getReason() == DownloadError.ERROR_UNAUTHORIZED) {
                                postAuthenticationNotification(downloader.getDownloadRequest());
                            } else if (status.getReason() == DownloadError.ERROR_HTTP_DATA_ERROR
                                    && Integer.parseInt(status.getReasonDetailed()) == 416) {

                                Log.d(TAG, "Requested invalid range, restarting download from the beginning");
                                FileUtils.deleteQuietly(new File(downloader.getDownloadRequest().getDestination()));
                                DownloadRequester.getInstance().download(DownloadService.this, downloader.getDownloadRequest());
                            } else {
                                Log.e(TAG, "Download failed");
                                saveDownloadStatus(status);
                                syncExecutor.execute(new FailedDownloadHandler(downloader.getDownloadRequest()));

                                if (type == FeedMedia.FEEDFILETYPE_FEEDMEDIA) {
                                    FeedItem item = getFeedItemFromId(status.getFeedfileId());
                                    if (item == null) {
                                        return;
                                    }
                                    boolean httpNotFound = status.getReason() == DownloadError.ERROR_HTTP_DATA_ERROR
                                            && String.valueOf(HttpURLConnection.HTTP_NOT_FOUND).equals(status.getReasonDetailed());
                                    boolean forbidden = status.getReason() == DownloadError.ERROR_FORBIDDEN
                                            && String.valueOf(HttpURLConnection.HTTP_FORBIDDEN).equals(status.getReasonDetailed());
                                    boolean notEnoughSpace = status.getReason() == DownloadError.ERROR_NOT_ENOUGH_SPACE;
                                    boolean wrongFileType = status.getReason() == DownloadError.ERROR_FILE_TYPE;
                                    if (httpNotFound || forbidden || notEnoughSpace || wrongFileType) {
                                        DBWriter.saveFeedItemAutoDownloadFailed(item).get();
                                    }
                                    // to make lists reload the failed item, we fake an item update
                                    EventBus.getDefault().post(FeedItemEvent.updated(item));
                                }
                            }
                        } else {
                            // if FeedMedia download has been canceled, fake FeedItem update
                            // so that lists reload that it
                            if (status.getFeedfileType() == FeedMedia.FEEDFILETYPE_FEEDMEDIA) {
                                FeedItem item = getFeedItemFromId(status.getFeedfileId());
                                if (item == null) {
                                    return;
                                }
                                EventBus.getDefault().post(FeedItemEvent.updated(item));
                            }
                        }
                        queryDownloadsAsync();
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "DownloadCompletionThread was interrupted");
                } catch (ExecutionException e) {
                    Log.e(TAG, "ExecutionException in DownloadCompletionThread: " + e.getMessage());
                    numberOfDownloads.decrementAndGet();
                }
            }
            Log.d(TAG, "End of downloadCompletionThread");
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getParcelableExtra(EXTRA_REQUEST) != null) {
            onDownloadQueued(intent);
        } else if (numberOfDownloads.get() == 0) {
            stopSelf();
        }
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "Service started");
        isRunning = true;
        handler = new Handler();
        reportQueue = Collections.synchronizedList(new ArrayList<>());
        downloads = Collections.synchronizedList(new ArrayList<>());
        numberOfDownloads = new AtomicInteger(0);

        IntentFilter cancelDownloadReceiverFilter = new IntentFilter();
        cancelDownloadReceiverFilter.addAction(ACTION_CANCEL_ALL_DOWNLOADS);
        cancelDownloadReceiverFilter.addAction(ACTION_CANCEL_DOWNLOAD);
        registerReceiver(cancelDownloadReceiver, cancelDownloadReceiverFilter);
        syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        Log.d(TAG, "parallel downloads: " + UserPreferences.getParallelDownloads());
        downloadExecutor = new ExecutorCompletionService<>(
                Executors.newFixedThreadPool(UserPreferences.getParallelDownloads(),
                        r -> {
                            Thread t = new Thread(r);
                            t.setPriority(Thread.MIN_PRIORITY);
                            return t;
                        }
                )
        );
        schedExecutor = new ScheduledThreadPoolExecutor(SCHED_EX_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }, (r, executor) -> Log.w(TAG, "SchedEx rejected submission of new task")
        );
        downloadCompletionThread.start();
        feedSyncThread = new FeedSyncThread(DownloadService.this, new FeedSyncThread.FeedSyncCallback() {
            @Override
            public void finishedSyncingFeeds(int numberOfCompletedFeeds) {
                numberOfDownloads.addAndGet(-numberOfCompletedFeeds);
                queryDownloadsAsync();
            }

            @Override
            public void failedSyncingFeed() {
                numberOfDownloads.decrementAndGet();
            }

            @Override
            public void downloadStatusGenerated(DownloadStatus downloadStatus) {
                saveDownloadStatus(downloadStatus);
            }
        });
        feedSyncThread.start();

        setupNotificationBuilders();
        requester = DownloadRequester.getInstance();
        startForeground(NOTIFICATION_ID, updateNotifications());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service shutting down");
        isRunning = false;

        if (ClientConfig.downloadServiceCallbacks.shouldCreateReport() &&
                UserPreferences.showDownloadReport()) {
            updateReport();
        }

        postHandler.removeCallbacks(postDownloaderTask);
        EventBus.getDefault().postSticky(DownloadEvent.refresh(Collections.emptyList()));

        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID);

        downloadCompletionThread.interrupt();
        syncExecutor.shutdown();
        schedExecutor.shutdown();
        feedSyncThread.shutdown();
        cancelNotificationUpdater();
        unregisterReceiver(cancelDownloadReceiver);

        // if this was the initial gpodder sync, i.e. we just synced the feeds successfully,
        // it is now time to sync the episode actions
        if (GpodnetPreferences.loggedIn() &&
                GpodnetPreferences.getLastSubscriptionSyncTimestamp() > 0 &&
                GpodnetPreferences.getLastEpisodeActionsSyncTimestamp() == 0) {
            GpodnetSyncService.sendSyncActionsIntent(this);
        }

        // start auto download in case anything new has shown up
        DBTasks.autodownloadUndownloadedItems(getApplicationContext());
    }

    private void setupNotificationBuilders() {
        notificationCompatBuilder = new NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID_DOWNLOADING)
                .setOngoing(true)
                .setContentIntent(ClientConfig.downloadServiceCallbacks.getNotificationContentIntent(this))
                .setSmallIcon(R.drawable.stat_notify_sync);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notificationCompatBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }

        Log.d(TAG, "Notification set up");
    }

    /**
     * Updates the contents of the service's notifications. Should be called
     * after setupNotificationBuilders.
     */
    private Notification updateNotifications() {
        if (notificationCompatBuilder == null) {
            return null;
        }

        String contentTitle = getString(R.string.download_notification_title);
        int numDownloads = requester.getNumberOfDownloads();
        String downloadsLeft = (numDownloads > 0) ?
                getResources().getQuantityString(R.plurals.downloads_left, numDownloads, numDownloads) :
                getString(R.string.downloads_processing);
        String bigText = compileNotificationString(downloads);

        notificationCompatBuilder.setContentTitle(contentTitle);
        notificationCompatBuilder.setContentText(downloadsLeft);
        notificationCompatBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));
        return notificationCompatBuilder.build();
    }

    private Downloader getDownloader(String downloadUrl) {
        for (Downloader downloader : downloads) {
            if (downloader.getDownloadRequest().getSource().equals(downloadUrl)) {
                return downloader;
            }
        }
        return null;
    }

    private final BroadcastReceiver cancelDownloadReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (TextUtils.equals(intent.getAction(), ACTION_CANCEL_DOWNLOAD)) {
                String url = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
                if (url == null) {
                    throw new IllegalArgumentException("ACTION_CANCEL_DOWNLOAD intent needs download url extra");
                }

                Log.d(TAG, "Cancelling download with url " + url);
                Downloader d = getDownloader(url);
                if (d != null) {
                    d.cancel();
                    DownloadRequester.getInstance().removeDownload(d.getDownloadRequest());

                    FeedItem item = getFeedItemFromId(d.getDownloadRequest().getFeedfileId());
                    if (item != null) {
                        EventBus.getDefault().post(FeedItemEvent.updated(item));
                    }
                } else {
                    Log.e(TAG, "Could not cancel download with url " + url);
                }
                postDownloaders();

            } else if (TextUtils.equals(intent.getAction(), ACTION_CANCEL_ALL_DOWNLOADS)) {
                for (Downloader d : downloads) {
                    d.cancel();
                    Log.d(TAG, "Cancelled all downloads");
                }
                postDownloaders();
            }
            queryDownloads();
        }

    };

    private void onDownloadQueued(Intent intent) {
        Log.d(TAG, "Received enqueue request");
        DownloadRequest request = intent.getParcelableExtra(EXTRA_REQUEST);
        if (request == null) {
            throw new IllegalArgumentException(
                    "ACTION_ENQUEUE_DOWNLOAD intent needs request extra");
        }

        writeFileUrl(request);

        Downloader downloader = getDownloader(request);
        if (downloader != null) {
            numberOfDownloads.incrementAndGet();
            // smaller rss feeds before bigger media files
            if (request.getFeedfileType() == Feed.FEEDFILETYPE_FEED) {
                downloads.add(0, downloader);
            } else {
                downloads.add(downloader);
            }
            downloadExecutor.submit(downloader);

            postDownloaders();
        }

        queryDownloads();
    }

    @VisibleForTesting
    public interface DownloaderFactory {
        @Nullable
        Downloader create(@NonNull DownloadRequest request);
    }

    private static class DefaultDownloaderFactory implements DownloaderFactory {
        @Nullable
        @Override
        public Downloader create(@NonNull DownloadRequest request) {
            if (!URLUtil.isHttpUrl(request.getSource()) && !URLUtil.isHttpsUrl(request.getSource())) {
                Log.e(TAG, "Could not find appropriate downloader for " + request.getSource());
                return null;
            }
            return new HttpDownloader(request);
        }
    }

    private static DownloaderFactory downloaderFactory = new DefaultDownloaderFactory();

    @VisibleForTesting
    public static DownloaderFactory getDownloaderFactory() {
        return downloaderFactory;
    }

    // public scope rather than package private,
    // because androidTest put classes in the non-standard de.test.antennapod hierarchy
    @VisibleForTesting
    public static void setDownloaderFactory(DownloaderFactory downloaderFactory) {
        DownloadService.downloaderFactory = downloaderFactory;
    }

    private Downloader getDownloader(@NonNull DownloadRequest request) {
        return downloaderFactory.create(request);
    }

    /**
     * Remove download from the DownloadRequester list and from the
     * DownloadService list.
     */
    private void removeDownload(final Downloader d) {
        handler.post(() -> {
            Log.d(TAG, "Removing downloader: " + d.getDownloadRequest().getSource());
            boolean rc = downloads.remove(d);
            Log.d(TAG, "Result of downloads.remove: " + rc);
            DownloadRequester.getInstance().removeDownload(d.getDownloadRequest());
            postDownloaders();
        });
    }

    /**
     * Adds a new DownloadStatus object to the list of completed downloads and
     * saves it in the database
     *
     * @param status the download that is going to be saved
     */
    private void saveDownloadStatus(DownloadStatus status) {
        reportQueue.add(status);
        DBWriter.addDownloadStatus(status);
    }

    /**
     * Creates a notification at the end of the service lifecycle to notify the
     * user about the number of completed downloads. A report will only be
     * created if there is at least one failed download excluding images
     */
    private void updateReport() {
        // check if report should be created
        boolean createReport = false;
        int successfulDownloads = 0;
        int failedDownloads = 0;

        // a download report is created if at least one download has failed
        // (excluding failed image downloads)
        for (DownloadStatus status : reportQueue) {
            if (status.isSuccessful()) {
                successfulDownloads++;
            } else if (!status.isCancelled()) {
                createReport = true;
                failedDownloads++;
            }
        }

        if (createReport) {
            Log.d(TAG, "Creating report");
            // create notification object
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID_ERROR)
                    .setTicker(getString(R.string.download_report_title))
                    .setContentTitle(getString(R.string.download_report_content_title))
                    .setContentText(
                            String.format(
                                    getString(R.string.download_report_content),
                                    successfulDownloads, failedDownloads)
                    )
                    .setSmallIcon(R.drawable.stat_notify_sync_error)
                    .setContentIntent(
                            ClientConfig.downloadServiceCallbacks.getReportNotificationContentIntent(this)
                    )
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(REPORT_ID, builder.build());
        } else {
            Log.d(TAG, "No report is created");
        }
        reportQueue.clear();
    }

    /**
     * Calls query downloads on the services main thread. This method should be used instead of queryDownloads if it is
     * used from a thread other than the main thread.
     */
    private void queryDownloadsAsync() {
        handler.post(DownloadService.this::queryDownloads);
    }

    /**
     * Check if there's something else to download, otherwise stop
     */
    private void queryDownloads() {
        Log.d(TAG, numberOfDownloads.get() + " downloads left");

        if (numberOfDownloads.get() <= 0 && DownloadRequester.getInstance().hasNoDownloads()) {
            Log.d(TAG, "Number of downloads is " + numberOfDownloads.get() + ", attempting shutdown");
            stopSelf();
        } else {
            setupNotificationUpdater();
            startForeground(NOTIFICATION_ID, updateNotifications());
        }
    }

    private void postAuthenticationNotification(final DownloadRequest downloadRequest) {
        handler.post(() -> {
            final String resourceTitle = (downloadRequest.getTitle() != null) ?
                    downloadRequest.getTitle() : downloadRequest.getSource();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(DownloadService.this, NotificationUtils.CHANNEL_ID_USER_ACTION);
            builder.setTicker(getText(R.string.authentication_notification_title))
                    .setContentTitle(getText(R.string.authentication_notification_title))
                    .setContentText(getText(R.string.authentication_notification_msg))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(getText(R.string.authentication_notification_msg)
                            + ": " + resourceTitle))
                    .setSmallIcon(R.drawable.ic_notification_key)
                    .setAutoCancel(true)
                    .setContentIntent(ClientConfig.downloadServiceCallbacks.getAuthentificationNotificationContentIntent(DownloadService.this, downloadRequest));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(downloadRequest.getSource().hashCode(), builder.build());
        });
    }

    @Nullable
    private FeedItem getFeedItemFromId(long id) {
        FeedMedia media = DBReader.getFeedMedia(id);
        if (media != null) {
            return media.getItem();
        } else {
            return null;
        }
    }

    /**
     * Creates the destination file and writes FeedMedia File_url directly after starting download
     * to make it possible to resume download after the service was killed by the system.
     */
    private void writeFileUrl(DownloadRequest request) {
        if (request.getFeedfileType() != FeedMedia.FEEDFILETYPE_FEEDMEDIA) {
            return;
        }

        File dest = new File(request.getDestination());
        if (!dest.exists()) {
            try {
                dest.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Unable to create file");
            }
        }

        if (dest.exists()) {
            Log.d(TAG, "Writing file url");
            FeedMedia media = DBReader.getFeedMedia(request.getFeedfileId());
            if (media == null) {
                Log.d(TAG, "No media");
                return;
            }
            media.setFile_url(request.getDestination());
            try {
                DBWriter.setFeedMedia(media).get();
            } catch (InterruptedException e) {
                Log.e(TAG, "writeFileUrl was interrupted");
            } catch (ExecutionException e) {
                Log.e(TAG, "ExecutionException in writeFileUrl: " + e.getMessage());
            }
        }
    }

    /**
     * Schedules the notification updater task if it hasn't been scheduled yet.
     */
    private void setupNotificationUpdater() {
        Log.d(TAG, "Setting up notification updater");
        if (notificationUpdater == null) {
            notificationUpdater = new NotificationUpdater();
            notificationUpdaterFuture = schedExecutor.scheduleAtFixedRate(
                    notificationUpdater, 5L, 5L, TimeUnit.SECONDS);
        }
    }

    private void cancelNotificationUpdater() {
        boolean result = false;
        if (notificationUpdaterFuture != null) {
            result = notificationUpdaterFuture.cancel(true);
        }
        notificationUpdater = null;
        notificationUpdaterFuture = null;
        Log.d(TAG, "NotificationUpdater cancelled. Result: " + result);
    }

    private class NotificationUpdater implements Runnable {
        public void run() {
            handler.post(() -> {
                Notification n = updateNotifications();
                if (n != null) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(NOTIFICATION_ID, n);
                }
            });
        }

    }


    private long lastPost = 0;

    private final Runnable postDownloaderTask = new Runnable() {
        @Override
        public void run() {
            List<Downloader> runningDownloads = new ArrayList<>();
            for (Downloader downloader : downloads) {
                if (!downloader.cancelled) {
                    runningDownloads.add(downloader);
                }
            }
            List<Downloader> list = Collections.unmodifiableList(runningDownloads);
            EventBus.getDefault().postSticky(DownloadEvent.refresh(list));
            postHandler.postDelayed(postDownloaderTask, 1500);
        }
    };

    private void postDownloaders() {
        long now = System.currentTimeMillis();
        if (now - lastPost >= 250) {
            postHandler.removeCallbacks(postDownloaderTask);
            postDownloaderTask.run();
            lastPost = now;
        }
    }

    private static String compileNotificationString(List<Downloader> downloads) {
        List<String> lines = new ArrayList<>(downloads.size());
        for (Downloader downloader : downloads) {
            if (downloader.cancelled) {
                continue;
            }
            StringBuilder line = new StringBuilder("• ");
            DownloadRequest request = downloader.getDownloadRequest();
            switch (request.getFeedfileType()) {
                case Feed.FEEDFILETYPE_FEED:
                    if (request.getTitle() != null) {
                        line.append(request.getTitle());
                    }
                    break;
                case FeedMedia.FEEDFILETYPE_FEEDMEDIA:
                    if (request.getTitle() != null) {
                        line.append(request.getTitle())
                                .append(" (")
                                .append(request.getProgressPercent())
                                .append("%)");
                    }
                    break;
                default:
                    line.append("Unknown: ").append(request.getFeedfileType());
            }
            lines.add(line.toString());
        }
        return TextUtils.join("\n", lines);
    }
}
