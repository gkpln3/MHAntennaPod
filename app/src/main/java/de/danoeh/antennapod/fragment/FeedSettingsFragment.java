package de.danoeh.antennapod.fragment;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.util.Log;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.core.dialog.ConfirmationDialog;
import de.danoeh.antennapod.core.feed.Feed;
import de.danoeh.antennapod.core.feed.FeedFilter;
import de.danoeh.antennapod.core.feed.FeedPreferences;
import de.danoeh.antennapod.core.feed.VolumeReductionSetting;
import de.danoeh.antennapod.core.preferences.UserPreferences;
import de.danoeh.antennapod.core.service.playback.PlaybackService;
import de.danoeh.antennapod.core.storage.DBReader;
import de.danoeh.antennapod.core.storage.DBWriter;
import de.danoeh.antennapod.dialog.AuthenticationDialog;
import de.danoeh.antennapod.dialog.EpisodeFilterDialog;
import io.reactivex.Maybe;
import io.reactivex.MaybeOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FeedSettingsFragment extends PreferenceFragmentCompat {
    private static final CharSequence PREF_EPISODE_FILTER = "episodeFilter";
    private static final String EXTRA_FEED_ID = "de.danoeh.antennapod.extra.feedId";
    private static final String TAG = "FeedSettingsFragment";

    private Feed feed;
    private Disposable disposable;
    private FeedPreferences feedPreferences;

    public static FeedSettingsFragment newInstance(Feed feed) {
        FeedSettingsFragment fragment = new FeedSettingsFragment();
        Bundle arguments = new Bundle();
        arguments.putLong(EXTRA_FEED_ID, feed.getId());
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.feed_settings);

        postponeEnterTransition();
        long feedId = getArguments().getLong(EXTRA_FEED_ID);
        disposable = Maybe.create((MaybeOnSubscribe<Feed>) emitter -> {
            Feed feed = DBReader.getFeed(feedId);
            if (feed != null) {
                emitter.onSuccess(feed);
            } else {
                emitter.onComplete();
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    feed = result;
                    feedPreferences = feed.getPreferences();
                    ((MainActivity) getActivity()).getSupportActionBar().setSubtitle(feed.getTitle());

                    setupAutoDownloadPreference();
                    setupKeepUpdatedPreference();
                    setupAutoDeletePreference();
                    setupVolumeReductionPreferences();
                    setupAuthentificationPreference();
                    setupEpisodeFilterPreference();

                    updateAutoDeleteSummary();
                    updateVolumeReductionValue();
                    updateAutoDownloadEnabled();
                }, error -> Log.d(TAG, Log.getStackTraceString(error)),
                this::startPostponedEnterTransition);
    }

    @Override
    public void onResume() {
        super.onResume();
        ((MainActivity) getActivity()).getSupportActionBar().setTitle(R.string.feed_settings_label);
        if (feed != null) {
            ((MainActivity) getActivity()).getSupportActionBar().setSubtitle(feed.getTitle());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        ((MainActivity) getActivity()).getSupportActionBar().setSubtitle(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void setupEpisodeFilterPreference() {
        findPreference(PREF_EPISODE_FILTER).setOnPreferenceClickListener(preference -> {
            new EpisodeFilterDialog(getContext(), feedPreferences.getFilter()) {
                @Override
                protected void onConfirmed(FeedFilter filter) {
                    feedPreferences.setFilter(filter);
                    feed.savePreferences();
                }
            }.show();
            return false;
        });
    }

    private void setupAuthentificationPreference() {
        findPreference("authentication").setOnPreferenceClickListener(preference -> {
            new AuthenticationDialog(getContext(),
                    R.string.authentication_label, true, false,
                    feedPreferences.getUsername(), feedPreferences.getPassword()) {
                @Override
                protected void onConfirmed(String username, String password, boolean saveUsernamePassword) {
                    feedPreferences.setUsername(username);
                    feedPreferences.setPassword(password);
                    feed.savePreferences();
                }
            }.show();
            return false;
        });
    }

    private void setupAutoDeletePreference() {
        ListPreference autoDeletePreference = (ListPreference) findPreference("autoDelete");
        autoDeletePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            switch ((String) newValue) {
                case "global":
                    feedPreferences.setAutoDeleteAction(FeedPreferences.AutoDeleteAction.GLOBAL);
                    break;
                case "always":
                    feedPreferences.setAutoDeleteAction(FeedPreferences.AutoDeleteAction.YES);
                    break;
                case "never":
                    feedPreferences.setAutoDeleteAction(FeedPreferences.AutoDeleteAction.NO);
                    break;
            }
            feed.savePreferences();
            updateAutoDeleteSummary();
            return false;
        });
    }

    private void updateAutoDeleteSummary() {
        ListPreference autoDeletePreference = (ListPreference) findPreference("autoDelete");

        switch (feedPreferences.getAutoDeleteAction()) {
            case GLOBAL:
                autoDeletePreference.setSummary(R.string.feed_auto_download_global);
                autoDeletePreference.setValue("global");
                break;
            case YES:
                autoDeletePreference.setSummary(R.string.feed_auto_download_always);
                autoDeletePreference.setValue("always");
                break;
            case NO:
                autoDeletePreference.setSummary(R.string.feed_auto_download_never);
                autoDeletePreference.setValue("never");
                break;
        }
    }

    private void setupVolumeReductionPreferences() {
        ListPreference volumeReductionPreference = (ListPreference) findPreference("volumeReduction");
        volumeReductionPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            switch ((String) newValue) {
                case "off":
                    feedPreferences.setVolumeReductionSetting(VolumeReductionSetting.OFF);
                    break;
                case "light":
                    feedPreferences.setVolumeReductionSetting(VolumeReductionSetting.LIGHT);
                    break;
                case "heavy":
                    feedPreferences.setVolumeReductionSetting(VolumeReductionSetting.HEAVY);
                    break;
            }
            feed.savePreferences();
            updateVolumeReductionValue();
            sendVolumeReductionChangedIntent();

            return false;
        });
    }

    private void sendVolumeReductionChangedIntent() {
        Context context = getContext();
        Intent intent = new Intent(PlaybackService.ACTION_VOLUME_REDUCTION_CHANGED).setPackage(context.getPackageName());
        intent.putExtra(PlaybackService.EXTRA_VOLUME_REDUCTION_AFFECTED_FEED, feed.getIdentifyingValue());
        intent.putExtra(PlaybackService.EXTRA_VOLUME_REDUCTION_SETTING, feedPreferences.getVolumeReductionSetting());
        context.sendBroadcast(intent);
    }

    private void updateVolumeReductionValue() {
        ListPreference volumeReductionPreference = (ListPreference) findPreference("volumeReduction");

        switch (feedPreferences.getVolumeReductionSetting()) {
            case OFF:
                volumeReductionPreference.setValue("off");
                break;
            case LIGHT:
                volumeReductionPreference.setValue("light");
                break;
            case HEAVY:
                volumeReductionPreference.setValue("heavy");
                break;
        }
    }

    private void setupKeepUpdatedPreference() {
        SwitchPreference pref = (SwitchPreference) findPreference("keepUpdated");

        pref.setChecked(feedPreferences.getKeepUpdated());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean checked = newValue == Boolean.TRUE;
            feedPreferences.setKeepUpdated(checked);
            feed.savePreferences();
            pref.setChecked(checked);
            return false;
        });
    }

    private void setupAutoDownloadPreference() {
        SwitchPreference pref = (SwitchPreference) findPreference("autoDownload");

        pref.setEnabled(UserPreferences.isEnableAutodownload());
        if (UserPreferences.isEnableAutodownload()) {
            pref.setChecked(feedPreferences.getAutoDownload());
        } else {
            pref.setChecked(false);
            pref.setSummary(R.string.auto_download_disabled_globally);
        }

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean checked = newValue == Boolean.TRUE;

            feedPreferences.setAutoDownload(checked);
            feed.savePreferences();
            updateAutoDownloadEnabled();
            ApplyToEpisodesDialog dialog = new ApplyToEpisodesDialog(getActivity(), checked);
            dialog.createNewDialog().show();
            pref.setChecked(checked);
            return false;
        });
    }

    private void updateAutoDownloadEnabled() {
        if (feed != null && feed.getPreferences() != null) {
            boolean enabled = feed.getPreferences().getAutoDownload() && UserPreferences.isEnableAutodownload();
            findPreference(PREF_EPISODE_FILTER).setEnabled(enabled);
        }
    }

    private class ApplyToEpisodesDialog extends ConfirmationDialog {
        private final boolean autoDownload;

        ApplyToEpisodesDialog(Context context, boolean autoDownload) {
            super(context, R.string.auto_download_apply_to_items_title,
                    R.string.auto_download_apply_to_items_message);
            this.autoDownload = autoDownload;
            setPositiveText(R.string.yes);
            setNegativeText(R.string.no);
        }

        @Override
        public  void onConfirmButtonPressed(DialogInterface dialog) {
            DBWriter.setFeedsItemsAutoDownload(feed, autoDownload);
        }
    }
}
