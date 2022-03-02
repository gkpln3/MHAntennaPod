package de.danoeh.antennapod.core.making_history;

import android.content.Context;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

import de.danoeh.antennapod.model.playback.Playable;

public class MHAnalytics {
    private FirebaseAnalytics mFirebaseAnalytics;

    public MHAnalytics(Context context)
    {
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(context);
    }

    public void reportStartOfPlayback(Playable playable)
    {
        if (playable == null) return;

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, playable.getStreamUrl());
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, playable.getEpisodeTitle());
        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "podcast");
        mFirebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle);
    }

    public void reportQueueInEpisode(Playable playable, int secondsInEpisode)
    {
        if (playable == null) return;

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, playable.getStreamUrl());
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, playable.getEpisodeTitle());
        bundle.putInt("seconds_in_episode", secondsInEpisode);
        mFirebaseAnalytics.logEvent("listening_to_podcast_5min", bundle);
    }

    public void reportEndOfPlayback(Playable playable, int secondsInEpisode, boolean didSkip)
    {
        if (playable == null) return;

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, playable.getStreamUrl());
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, playable.getEpisodeTitle());
        bundle.putInt("seconds_in_episode", secondsInEpisode);
        bundle.putBoolean("did_skip", didSkip);
        mFirebaseAnalytics.logEvent("end_of_episode", bundle);
    }

    public void reportFastForward(Playable playable, int secondsInEpisode, int delta)
    {
        if (playable == null) return;

        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, playable.getStreamUrl());
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, playable.getEpisodeTitle());
        bundle.putInt("seconds_in_episode", secondsInEpisode);
        bundle.putInt("seek_delta", delta);
        mFirebaseAnalytics.logEvent("seek_to", bundle);
    }

    public void reportAdClicked(Playable playable, String adAddress, String sentToAddress) {
        if (playable == null) return;
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, playable.getStreamUrl());
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, playable.getEpisodeTitle());
        bundle.putString("ad_address", adAddress);
        bundle.putString("sent_to_address", sentToAddress);
        mFirebaseAnalytics.logEvent("ad_clicked", bundle);
    }
}
