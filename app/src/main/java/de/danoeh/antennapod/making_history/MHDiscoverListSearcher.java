package de.danoeh.antennapod.making_history;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.discovery.PodcastSearchResult;
import de.danoeh.antennapod.discovery.PodcastSearcher;
import io.reactivex.Single;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class MHDiscoverListSearcher implements PodcastSearcher {
    private MHDiscoverListLoader loader;
    private static List<PodcastSearchResult> s_cachedResultsFromDiscover = null;

    public MHDiscoverListSearcher() {
        this.loader = new MHDiscoverListLoader();
    }

    public Single<List<PodcastSearchResult>> search(String query) {
        return Single.create((SingleOnSubscribe<List<PodcastSearchResult>>) subscriber -> {
            if (s_cachedResultsFromDiscover == null) {
                loader.loadToplist(false).subscribe((podcastSearchResults) ->
                {
                    s_cachedResultsFromDiscover = podcastSearchResults;
                    subscriber.onSuccess(filterResults(s_cachedResultsFromDiscover, query));
                });
            }
            else
            {
                subscriber.onSuccess(filterResults(s_cachedResultsFromDiscover, query));
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }
    @Override
    public Single<String> lookupUrl(String resultUrl) {
        return null;
    }

    @Override
    public boolean urlNeedsLookup(String resultUrl) {
        return false;
    }

    @Override
    public String getName() {
        return "Podcast Israel";
    }

    private List<PodcastSearchResult> filterResults(List<PodcastSearchResult> podcastSearchResults, String query)
    {
        List<PodcastSearchResult> searchResults = new ArrayList<>();
        for (PodcastSearchResult result : podcastSearchResults) {
            if  (result.title.toLowerCase().contains(query.toLowerCase()) ||
                (result.category != null && result.category.toLowerCase().contains(query.toLowerCase())))
            {
                searchResults.add(result);
            }
        }

        return searchResults;
    }
}
