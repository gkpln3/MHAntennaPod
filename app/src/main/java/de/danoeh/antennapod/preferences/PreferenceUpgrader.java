package de.danoeh.antennapod.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import androidx.preference.PreferenceManager;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.error.CrashReportWriter;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.preferences.UserPreferences.EnqueueLocation;
import de.danoeh.antennapod.core.util.download.AutoUpdateManager;
import de.danoeh.antennapod.fragment.QueueFragment;
import de.danoeh.antennapod.fragment.swipeactions.SwipeAction;
import de.danoeh.antennapod.fragment.swipeactions.SwipeActions;

public class PreferenceUpgrader {
    private static final String PREF_CONFIGURED_VERSION = "version_code";
    private static final String PREF_NAME = "app_version";

    private static SharedPreferences prefs;

    public static void checkUpgrades(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences upgraderPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // MH: Guyk - Ignore the MH part of the version code.
        int oldVersion = upgraderPrefs.getInt(PREF_CONFIGURED_VERSION, -1) % 10000000;

        // MH: Guyk - Ignore the MH part of the version code.
        int newVersion = BuildConfig.VERSION_CODE % 10000000;

        if (oldVersion != newVersion) {
            AutoUpdateManager.restartUpdateAlarm(context);
            CrashReportWriter.getFile().delete();

            upgrade(oldVersion, context);
            upgraderPrefs.edit().putInt(PREF_CONFIGURED_VERSION, newVersion).apply();
        }
    }

    private static void upgrade(int oldVersion, Context context) {
        if (oldVersion == -1) {
            //New installation
            return;
        }
        if (oldVersion < 1070196) {
            // migrate episode cleanup value (unit changed from days to hours)
            int oldValueInDays = UserPreferences.getEpisodeCleanupValue();
            if (oldValueInDays > 0) {
                UserPreferences.setEpisodeCleanupValue(oldValueInDays * 24);
            } // else 0 or special negative values, no change needed
        }
        if (oldVersion < 1070197) {
            if (prefs.getBoolean("prefMobileUpdate", false)) {
                prefs.edit().putString("prefMobileUpdateAllowed", "everything").apply();
            }
        }
        if (oldVersion < 1070300) {
            prefs.edit().putString(UserPreferences.PREF_MEDIA_PLAYER,
                    UserPreferences.PREF_MEDIA_PLAYER_EXOPLAYER).apply();

            if (prefs.getBoolean("prefEnableAutoDownloadOnMobile", false)) {
                UserPreferences.setAllowMobileAutoDownload(true);
            }
            switch (prefs.getString("prefMobileUpdateAllowed", "images")) {
                case "everything":
                    UserPreferences.setAllowMobileFeedRefresh(true);
                    UserPreferences.setAllowMobileEpisodeDownload(true);
                    UserPreferences.setAllowMobileImages(true);
                    break;
                case "images":
                    UserPreferences.setAllowMobileImages(true);
                    break;
                case "nothing":
                    UserPreferences.setAllowMobileImages(false);
                    break;
            }
        }
        if (oldVersion < 1070400) {
            int theme = UserPreferences.getTheme();
            if (theme == R.style.Theme_AntennaPod_Light) {
                prefs.edit().putString(UserPreferences.PREF_THEME, "system").apply();
            }

            UserPreferences.setQueueLocked(false);
            UserPreferences.setStreamOverDownload(false);

            if (!prefs.contains(UserPreferences.PREF_ENQUEUE_LOCATION)) {
                final String keyOldPrefEnqueueFront = "prefQueueAddToFront";
                boolean enqueueAtFront = prefs.getBoolean(keyOldPrefEnqueueFront, false);
                EnqueueLocation enqueueLocation = enqueueAtFront ? EnqueueLocation.FRONT : EnqueueLocation.BACK;
                UserPreferences.setEnqueueLocation(enqueueLocation);
            }
        }
        if (oldVersion < 2010300) {
            // Migrate hardware button preferences
            if (prefs.getBoolean("prefHardwareForwardButtonSkips", false)) {
                prefs.edit().putString(UserPreferences.PREF_HARDWARE_FORWARD_BUTTON,
                        String.valueOf(KeyEvent.KEYCODE_MEDIA_NEXT)).apply();
            }
            if (prefs.getBoolean("prefHardwarePreviousButtonRestarts", false)) {
                prefs.edit().putString(UserPreferences.PREF_HARDWARE_PREVIOUS_BUTTON,
                        String.valueOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS)).apply();
            }
        }
        if (oldVersion < 2040000) {
            SharedPreferences swipePrefs = context.getSharedPreferences(SwipeActions.PREF_NAME, Context.MODE_PRIVATE);
            swipePrefs.edit().putString(SwipeActions.KEY_PREFIX_SWIPEACTIONS + QueueFragment.TAG,
                    SwipeAction.REMOVE_FROM_QUEUE + "," + SwipeAction.REMOVE_FROM_QUEUE).apply();
        }
        if (oldVersion < 2050000) {
            prefs.edit().putBoolean(UserPreferences.PREF_PAUSE_PLAYBACK_FOR_FOCUS_LOSS, true).apply();
        }
    }
}
