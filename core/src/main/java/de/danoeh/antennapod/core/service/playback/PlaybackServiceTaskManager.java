package de.danoeh.antennapod.core.service.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.core.event.QueueEvent;
import de.danoeh.antennapod.core.feed.FeedItem;
import de.danoeh.antennapod.core.storage.DBReader;
import de.danoeh.antennapod.core.util.playback.Playable;
import de.greenrobot.event.EventBus;


/**
 * Manages the background tasks of PlaybackSerivce, i.e.
 * the sleep timer, the position saver, the widget updater and
 * the queue loader.
 * <p/>
 * The PlaybackServiceTaskManager(PSTM) uses a callback object (PSTMCallback)
 * to notify the PlaybackService about updates from the running tasks.
 */
public class PlaybackServiceTaskManager {
    private static final String TAG = "PlaybackServiceTaskMgr";

    /**
     * Update interval of position saver in milliseconds.
     */
    public static final int POSITION_SAVER_WAITING_INTERVAL = 5000;
    /**
     * Notification interval of widget updater in milliseconds.
     */
    public static final int WIDGET_UPDATER_NOTIFICATION_INTERVAL = 1000;

    private static final int SCHED_EX_POOL_SIZE = 2;
    private final ScheduledThreadPoolExecutor schedExecutor;

    private ScheduledFuture<?> positionSaverFuture;
    private ScheduledFuture<?> widgetUpdaterFuture;
    private ScheduledFuture<?> sleepTimerFuture;
    private volatile Future<List<FeedItem>> queueFuture;
    private volatile Future<?> chapterLoaderFuture;

    private SleepTimer sleepTimer;

    private final Context context;
    private final PSTMCallback callback;

    /**
     * Sets up a new PSTM. This method will also start the queue loader task.
     *
     * @param context
     * @param callback A PSTMCallback object for notifying the user about updates. Must not be null.
     */
    public PlaybackServiceTaskManager(@NonNull Context context,
                                      @NonNull PSTMCallback callback) {
        this.context = context;
        this.callback = callback;
        schedExecutor = new ScheduledThreadPoolExecutor(SCHED_EX_POOL_SIZE, r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        loadQueue();
        EventBus.getDefault().register(this);
    }

    public void onEvent(QueueEvent event) {
        Log.d(TAG, "onEvent(QueueEvent " + event +")");
        cancelQueueLoader();
        loadQueue();
    }

    private synchronized boolean isQueueLoaderActive() {
        return queueFuture != null && !queueFuture.isDone();
    }

    private synchronized void cancelQueueLoader() {
        if (isQueueLoaderActive()) {
            queueFuture.cancel(true);
        }
    }

    private synchronized void loadQueue() {
        if (!isQueueLoaderActive()) {
            queueFuture = schedExecutor.submit(DBReader::getQueue);
        }
    }

    /**
     * Returns the queue if it is already loaded or null if it hasn't been loaded yet.
     * In order to wait until the queue has been loaded, use getQueue()
     */
    public synchronized List<FeedItem> getQueueIfLoaded() {
        if (queueFuture.isDone()) {
            try {
                return queueFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Returns the queue or waits until the PSTM has loaded the queue from the database.
     */
    public synchronized List<FeedItem> getQueue() throws InterruptedException {
        try {
            return queueFuture.get();
        } catch (ExecutionException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Starts the position saver task. If the position saver is already active, nothing will happen.
     */
    public synchronized void startPositionSaver() {
        if (!isPositionSaverActive()) {
            Runnable positionSaver = callback::positionSaverTick;
            positionSaver = useMainThreadIfNecessary(positionSaver);
            positionSaverFuture = schedExecutor.scheduleWithFixedDelay(positionSaver, POSITION_SAVER_WAITING_INTERVAL,
                    POSITION_SAVER_WAITING_INTERVAL, TimeUnit.MILLISECONDS);

            Log.d(TAG, "Started PositionSaver");
        } else {
            Log.d(TAG, "Call to startPositionSaver was ignored.");
        }
    }

    /**
     * Returns true if the position saver is currently running.
     */
    public synchronized boolean isPositionSaverActive() {
        return positionSaverFuture != null && !positionSaverFuture.isCancelled() && !positionSaverFuture.isDone();
    }

    /**
     * Cancels the position saver. If the position saver is not running, nothing will happen.
     */
    public synchronized void cancelPositionSaver() {
        if (isPositionSaverActive()) {
            positionSaverFuture.cancel(false);
            Log.d(TAG, "Cancelled PositionSaver");
        }
    }

    /**
     * Starts the widget updater task. If the widget updater is already active, nothing will happen.
     */
    public synchronized void startWidgetUpdater() {
        if (!isWidgetUpdaterActive()) {
            Runnable widgetUpdater = callback::onWidgetUpdaterTick;
            widgetUpdater = useMainThreadIfNecessary(widgetUpdater);
            widgetUpdaterFuture = schedExecutor.scheduleWithFixedDelay(widgetUpdater, WIDGET_UPDATER_NOTIFICATION_INTERVAL,
                    WIDGET_UPDATER_NOTIFICATION_INTERVAL, TimeUnit.MILLISECONDS);

            Log.d(TAG, "Started WidgetUpdater");
        } else {
            Log.d(TAG, "Call to startWidgetUpdater was ignored.");
        }
    }

    /**
     * Starts a new sleep timer with the given waiting time. If another sleep timer is already active, it will be
     * cancelled first.
     * After waitingTime has elapsed, onSleepTimerExpired() will be called.
     *
     * @throws java.lang.IllegalArgumentException if waitingTime <= 0
     */
    public synchronized void setSleepTimer(long waitingTime, boolean shakeToReset, boolean vibrate) {
        if(waitingTime <= 0) {
            throw new IllegalArgumentException("Waiting time <= 0");
        }

        Log.d(TAG, "Setting sleep timer to " + Long.toString(waitingTime) + " milliseconds");
        if (isSleepTimerActive()) {
            sleepTimerFuture.cancel(true);
        }
        sleepTimer = new SleepTimer(waitingTime, shakeToReset, vibrate);
        Runnable runnable = useMainThreadIfNecessary(sleepTimer);
        sleepTimerFuture = schedExecutor.schedule(runnable, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns true if the sleep timer is currently active.
     */
    public synchronized boolean isSleepTimerActive() {
        return sleepTimer != null
                && sleepTimerFuture != null
                && !sleepTimerFuture.isCancelled()
                && !sleepTimerFuture.isDone()
                && sleepTimer.getWaitingTime() > 0;
    }

    /**
     * Disables the sleep timer. If the sleep timer is not active, nothing will happen.
     */
    public synchronized void disableSleepTimer() {
        if (isSleepTimerActive()) {
            Log.d(TAG, "Disabling sleep timer");
            sleepTimerFuture.cancel(true);
        }
    }

    /**
     * Returns the current sleep timer time or 0 if the sleep timer is not active.
     */
    public synchronized long getSleepTimerTimeLeft() {
        if (isSleepTimerActive()) {
            return sleepTimer.getWaitingTime();
        } else {
            return 0;
        }
    }


    /**
     * Returns true if the widget updater is currently running.
     */
    public synchronized boolean isWidgetUpdaterActive() {
        return widgetUpdaterFuture != null && !widgetUpdaterFuture.isCancelled() && !widgetUpdaterFuture.isDone();
    }

    /**
     * Cancels the widget updater. If the widget updater is not running, nothing will happen.
     */
    public synchronized void cancelWidgetUpdater() {
        if (isWidgetUpdaterActive()) {
            widgetUpdaterFuture.cancel(false);
            Log.d(TAG, "Cancelled WidgetUpdater");
        }
    }

    private synchronized void cancelChapterLoader() {
        if (isChapterLoaderActive()) {
            chapterLoaderFuture.cancel(true);
        }
    }

    private synchronized boolean isChapterLoaderActive() {
        return chapterLoaderFuture != null && !chapterLoaderFuture.isDone();
    }

    /**
     * Starts a new thread that loads the chapter marks from a playable object. If another chapter loader is already active,
     * it will be cancelled first.
     * On completion, the callback's onChapterLoaded method will be called.
     */
    public synchronized void startChapterLoader(@NonNull final Playable media) {
        if (isChapterLoaderActive()) {
            cancelChapterLoader();
        }

        Runnable chapterLoader = () -> {
            Log.d(TAG, "Chapter loader started");
            if (media.getChapters() == null) {
                media.loadChapterMarks();
                if (!Thread.currentThread().isInterrupted() && media.getChapters() != null) {
                    callback.onChapterLoaded(media);
                }
            }
            Log.d(TAG, "Chapter loader stopped");
        };
        chapterLoader = useMainThreadIfNecessary(chapterLoader);
        chapterLoaderFuture = schedExecutor.submit(chapterLoader);
    }


    /**
     * Cancels all tasks. The PSTM will be in the initial state after execution of this method.
     */
    public synchronized void cancelAllTasks() {
        cancelPositionSaver();
        cancelWidgetUpdater();
        disableSleepTimer();
        cancelQueueLoader();
        cancelChapterLoader();
    }

    /**
     * Cancels all tasks and shuts down the internal executor service of the PSTM. The object should not be used after
     * execution of this method.
     */
    public synchronized void shutdown() {
        EventBus.getDefault().unregister(this);
        cancelAllTasks();
        schedExecutor.shutdown();
    }

    private Runnable useMainThreadIfNecessary(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Called in main thread => ExoPlayer is used
            // Run on ui thread even if called from schedExecutor
            Handler handler = new Handler();
            return () -> handler.post(runnable);
        } else {
            return runnable;
        }
    }

    /**
     * Sleeps for a given time and then pauses playback.
     */
    class SleepTimer implements Runnable {
        private static final String TAG = "SleepTimer";
        private static final long UPDATE_INTERVAL = 1000L;
        private static final long NOTIFICATION_THRESHOLD = 10000;
        private final long waitingTime;
        private long timeLeft;
        private final boolean shakeToReset;
        private final boolean vibrate;
        private ShakeListener shakeListener;

        public SleepTimer(long waitingTime, boolean shakeToReset, boolean vibrate) {
            super();
            this.waitingTime = waitingTime;
            this.timeLeft = waitingTime;
            this.shakeToReset = shakeToReset;
            this.vibrate = vibrate;
        }

        @Override
        public void run() {
            Log.d(TAG, "Starting");
            boolean notifiedAlmostExpired = false;
            long lastTick = System.currentTimeMillis();
            while (timeLeft > 0) {
                try {
                    Thread.sleep(UPDATE_INTERVAL);
                    long now = System.currentTimeMillis();
                    timeLeft -= now - lastTick;
                    lastTick = now;

                    if(timeLeft < NOTIFICATION_THRESHOLD && !notifiedAlmostExpired) {
                        Log.d(TAG, "Sleep timer is about to expire");
                        if(vibrate) {
                            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                            if(v != null) {
                                v.vibrate(500);
                            }
                        }
                        if(shakeListener == null && shakeToReset) {
                            shakeListener = new ShakeListener(context, this);
                        }
                        callback.onSleepTimerAlmostExpired();
                        notifiedAlmostExpired = true;
                    }
                    if (timeLeft <= 0) {
                        Log.d(TAG, "Sleep timer expired");
                        if(shakeListener != null) {
                            shakeListener.pause();
                            shakeListener = null;
                        }
                        if (!Thread.currentThread().isInterrupted()) {
                            callback.onSleepTimerExpired();
                        } else {
                            Log.d(TAG, "Sleep timer interrupted");
                        }
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Thread was interrupted while waiting");
                    e.printStackTrace();
                    break;
                }
            }
        }

        public long getWaitingTime() {
            return timeLeft;
        }

        public void onShake() {
            setSleepTimer(waitingTime, shakeToReset, vibrate);
            callback.onSleepTimerReset();
            shakeListener.pause();
            shakeListener = null;
        }

    }

    public interface PSTMCallback {
        void positionSaverTick();

        void onSleepTimerAlmostExpired();

        void onSleepTimerExpired();

        void onSleepTimerReset();

        void onWidgetUpdaterTick();

        void onChapterLoaded(Playable media);
    }
}
