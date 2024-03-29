package de.danoeh.antennapod.making_history;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import android.net.Uri;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.core.export.opml.OpmlElement;
import de.danoeh.antennapod.core.export.opml.OpmlReader;
import de.danoeh.antennapod.core.service.download.DownloadRequestCreator;
import de.danoeh.antennapod.core.service.download.DownloadService;
import de.danoeh.antennapod.core.util.download.AutoUpdateManager;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;

public class MHDefaultFeedLoader {
    private final static String TAG = "MHDefaultFeedLoader";
    private final static String OPML_FEED_URL = "https://firebasestorage.googleapis.com/v0/b/makinghistory-1579519443087.appspot.com/o/default_opml.xml?alt=media";
    private final static String SHARED_PREFERENCES_NAME = "MH_SHARED_PREFERENCES";
    private final static String OPML_HASH_PREF_NAME = "OPML_HASH";
    private final static String UNWANTED_FEEDS_LIST_PREF_NAME = "UNWANTED_FEEDS_LIST";

    public static void loadDefaultOPMLIfNeeded(final Activity activity)
    {
        Request request = new Request.Builder().url(OPML_FEED_URL).build();
        OkHttpClient client = new OkHttpClient.Builder().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, Log.getStackTraceString(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try
                {
                    // Calculate the hash value of the current list to see if it has changed.
                    String opmlData = response.body().string();
                    int stringHash = opmlData.hashCode();
                    SharedPreferences prefs = activity.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_MULTI_PROCESS);
                    if (prefs.getInt(OPML_HASH_PREF_NAME, 0) == stringHash)
                    {
                        // List has not changed, exit.
                        return;
                    }

                    // List changed, Save the new hash and reload the opml file.
                    prefs.edit().putInt(OPML_HASH_PREF_NAME, stringHash).apply();

                    // Fetch the list of the unwanted podcasts.
                    Set<String> unwantedPodcasts = prefs.getStringSet(UNWANTED_FEEDS_LIST_PREF_NAME, Collections.emptySet());

                    final InputStream opmlStream = new ByteArrayInputStream(opmlData.getBytes("UTF-8"));
                    activity.runOnUiThread(() ->
                    {
                        try {
                            Reader mReader = new InputStreamReader(opmlStream, StandardCharsets.UTF_8);
                            OpmlReader reader = new OpmlReader();
                            ArrayList<OpmlElement> elements = reader.readDocument(mReader);
                            for (OpmlElement element : elements) {
                                Feed feed = new Feed(element.getXmlUrl(), null, element.getText());
                                if (!unwantedPodcasts.contains(element.getXmlUrl())) {
                                    DownloadServiceInterface.get().download(activity, false, DownloadRequestCreator.create(feed).build());
                                    // Once we've added this podcast, theres no need to add it again ever...
                                    MHDefaultFeedLoader.addUnwantedFeedToList(activity, element.getXmlUrl());
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
                catch (Exception e)
                {
                    Log.e(TAG, Log.getStackTraceString(e));
                }
            }
        });
    }

    /***
     * Adds a podcast to the list of unwanted podcasts, this happends when the user decides to remove
     * a podcast from the list, and will make the automatic podcast downloader ignore this podcast
     * in later loadings.
     */
    public static void addUnwantedFeedToList(Context context, String feedURL)
    {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_MULTI_PROCESS);
        Set<String> unwantedPodcasts = prefs.getStringSet(UNWANTED_FEEDS_LIST_PREF_NAME, new HashSet<String>());
        Set<String> newUnwantedPodcasts = new HashSet<String>(unwantedPodcasts);
        newUnwantedPodcasts.add(feedURL);
        prefs.edit().putStringSet(UNWANTED_FEEDS_LIST_PREF_NAME, newUnwantedPodcasts).apply();
    }

    private static int[] toIntArray(List<Integer> list)  {
        int[] ret = new int[list.size()];
        int i = 0;
        for (Integer e : list)
            ret[i++] = e;
        return ret;
    }
}
