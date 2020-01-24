package de.danoeh.antennapod.making_history;

import android.content.Context;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.core.ClientConfig;
import de.danoeh.antennapod.core.service.download.AntennapodHttpClient;
import de.danoeh.antennapod.discovery.PodcastSearchResult;
import io.reactivex.Single;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MHDiscoverListLoader {
    private final Context context;
    private final String TOP_DISCOVER_JSON_URL = "https://sites.google.com/site/gkpln3/mh_discover_feed.json";
    private final String ALL_ISRAELI_DISCOVER_JSON_URL = "https://sites.google.com/site/gkpln3/mh_discover_feed.json";

    public MHDiscoverListLoader(Context context) {
        this.context = context;
    }

    public Single<List<PodcastSearchResult>> loadToplist(boolean loadTopOnly) {
        return Single.create((SingleOnSubscribe<List<PodcastSearchResult>>) emitter -> {
            OkHttpClient client = AntennapodHttpClient.getHttpClient();
            String feedString = getTopListFeed(loadTopOnly ? TOP_DISCOVER_JSON_URL : ALL_ISRAELI_DISCOVER_JSON_URL, client);
            List<PodcastSearchResult> podcasts = parseFeed(feedString);
            emitter.onSuccess(podcasts);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<String> getFeedUrl(PodcastSearchResult podcast) {
        return Single.just(podcast.feedUrl)
                .observeOn(AndroidSchedulers.mainThread());
    }

    private String getTopListFeed(String url, OkHttpClient client) throws IOException {
        Request.Builder httpReq = new Request.Builder()
                .header("User-Agent", ClientConfig.USER_AGENT)
                .url(url);

        try (Response response = client.newCall(httpReq.build()).execute()) {
            if (response.isSuccessful()) {
                return response.body().string();
            }
            String prefix = context.getString(R.string.error_msg_prefix);
            throw new IOException(prefix + response);
        }
    }

    private List<PodcastSearchResult> parseFeed(String jsonString) throws JSONException {
        JSONObject result = new JSONObject(jsonString);
        JSONArray entries = result.getJSONArray("items");

        List<PodcastSearchResult> results = new ArrayList<>();
        for (int i=0; i < entries.length(); i++) {
            JSONObject json = entries.getJSONObject(i);
            results.add(PodcastSearchResult.fromMakingHistoryDiscover(json));
        }

        return results;
    }

}
