package de.danoeh.antennapod.making_history;


import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.core.view.MenuItemCompat;
import androidx.appcompat.widget.SearchView;
import androidx.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.OnlineFeedViewActivity;
import de.danoeh.antennapod.adapter.itunes.ItunesAdapter;
import de.danoeh.antennapod.net.discovery.PodcastSearchResult;
import dev.dworks.libs.astickyheader.SimpleSectionedGridAdapter;
import io.reactivex.disposables.Disposable;

//Searches iTunes store for given string and displays results in a list
public class MHDiscoverSearchFragment extends Fragment {

    private static final String TAG = "MHDiscoverSearchFrag";


    /**
     * Adapter responsible with the search results
     */
    private ItunesAdapter adapter;
    private SimpleSectionedGridAdapter sectionedGridAdapter;
    private GridView gridView;
    private ProgressBar progressBar;
    private TextView txtvError;
    private Button butRetry;
    private TextView txtvEmpty;

    /**
     * List of podcasts retreived from the search
     */
    private List<PodcastSearchResult> searchResults;
    private List<PodcastSearchResult> topList;
    private Disposable disposable;

    /**
     * Replace adapter data with provided search results from SearchTask.
     * @param result List of Podcast objects containing search results
     */
    private void updateData(List<PodcastSearchResult> result) {
        this.searchResults = result;

        // Set sections needs to be called here so it will sort the search results because they
        // are being used.
        setSections(sectionedGridAdapter);

        adapter.clear();
        if (result != null && result.size() > 0) {
            gridView.setVisibility(View.VISIBLE);
            txtvEmpty.setVisibility(View.GONE);
            for (PodcastSearchResult p : result) {
                adapter.add(p);
            }
            sectionedGridAdapter.notifyDataSetInvalidated();
        } else {
            gridView.setVisibility(View.GONE);
            txtvEmpty.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Constructor
     */
    public MHDiscoverSearchFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_making_history_search, container, false);
        gridView = root.findViewById(R.id.gridView);
        adapter = new ItunesAdapter(getActivity(), new ArrayList<>());
        sectionedGridAdapter = setupSectionedAdapter(gridView, adapter);
        gridView.setNumColumns(1);
        gridView.setAdapter(sectionedGridAdapter);

        //Show information about the podcast when the list item is clicked
        gridView.setOnItemClickListener((parent, view1, position, id) -> {
            Object item = sectionedGridAdapter.getItem(position);

            // Ignore presses on the section headers.
            if (!(item instanceof PodcastSearchResult)) { return; }

            PodcastSearchResult podcast = (PodcastSearchResult)item;
            if (podcast.feedUrl == null) {
                return;
            }
            gridView.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            MHDiscoverListLoader loader = new MHDiscoverListLoader();
            disposable = loader.getFeedUrl(podcast)
                    .subscribe(feedUrl -> {
                        progressBar.setVisibility(View.GONE);
                        gridView.setVisibility(View.VISIBLE);
                        Intent intent = new Intent(getActivity(), OnlineFeedViewActivity.class);
                        intent.putExtra(OnlineFeedViewActivity.ARG_FEEDURL, feedUrl);
                        startActivity(intent);
                    }, error -> {
                        Log.e(TAG, Log.getStackTraceString(error));
                        progressBar.setVisibility(View.GONE);
                        gridView.setVisibility(View.VISIBLE);
                        String prefix = getString(R.string.error_msg_prefix);
                        new AlertDialog.Builder(getActivity())
                                .setMessage(prefix + " " + error.getMessage())
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    });
        });
        progressBar = root.findViewById(R.id.progressBar);
        txtvError = root.findViewById(R.id.txtvError);
        butRetry = root.findViewById(R.id.butRetry);
        txtvEmpty = root.findViewById(android.R.id.empty);

        loadToplist();

        return root;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (disposable != null) {
            disposable.dispose();
        }
        adapter = null;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.search, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        final SearchView sv = (SearchView) MenuItemCompat.getActionView(searchItem);
        sv.setQueryHint(getString(R.string.search_making_history_label));
        sv.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String s) {
                sv.clearFocus();
                search(s);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                return false;
            }
        });
        MenuItemCompat.setOnActionExpandListener(searchItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if(searchResults != null) {
                    searchResults = null;
                    updateData(topList);
                }
                return true;
            }
        });
    }

    private void loadToplist() {
        if (disposable != null) {
            disposable.dispose();
        }
        gridView.setVisibility(View.GONE);
        txtvError.setVisibility(View.GONE);
        butRetry.setVisibility(View.GONE);
        txtvEmpty.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        MHDiscoverListLoader loader = new MHDiscoverListLoader();
        disposable = loader.loadToplist(false)
                .subscribe(podcasts -> {
                    progressBar.setVisibility(View.GONE);
                    topList = podcasts;
                    updateData(topList);
                }, error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    progressBar.setVisibility(View.GONE);
                    txtvError.setText(error.toString());
                    txtvError.setVisibility(View.VISIBLE);
                    butRetry.setOnClickListener(v -> loadToplist());
                    butRetry.setVisibility(View.VISIBLE);
                });
    }

    private void search(String query) {
        if (disposable != null) {
            disposable.dispose();
        }
        gridView.setVisibility(View.GONE);
        txtvError.setVisibility(View.GONE);
        butRetry.setVisibility(View.GONE);
        txtvEmpty.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        MHDiscoverListSearcher searcher = new MHDiscoverListSearcher();
        disposable = searcher.search(query).subscribe(podcasts -> {
            progressBar.setVisibility(View.GONE);
//            updateData(podcasts);
        }, error -> {
            Log.e(TAG, Log.getStackTraceString(error));
            progressBar.setVisibility(View.GONE);
            txtvError.setText(error.toString());
            txtvError.setVisibility(View.VISIBLE);
            butRetry.setOnClickListener(v -> search(query));
            butRetry.setVisibility(View.VISIBLE);
        });
    }


    private SimpleSectionedGridAdapter setupSectionedAdapter(GridView gridView, BaseAdapter baseAdapter)
    {
        SimpleSectionedGridAdapter adapter = new SimpleSectionedGridAdapter(getContext(), baseAdapter, R.layout.grid_item_header, R.id.header_layout, R.id.header);
        adapter.setGridView(gridView);
        setSections(adapter);
        return adapter;
    }

    private void setSections(SimpleSectionedGridAdapter adapter)
    {
        if (searchResults == null)
            return;

        List<SimpleSectionedGridAdapter.Section> sections = new ArrayList<>();
        Collections.sort(searchResults, new Comparator<PodcastSearchResult>() {
            @Override
            public int compare(PodcastSearchResult podcastSearchResult, PodcastSearchResult t1) {
                // No categorized to the end of the list.
                if (podcastSearchResult.category == null)
                    return 1;
                if (t1.category == null)
                    return -1;
                if (podcastSearchResult.category.equalsIgnoreCase("No Category"))
                    return 1;
                if (t1.category.equalsIgnoreCase("No Category"))
                    return -1;

                // Put top podcasts on top.
                if (podcastSearchResult.category.equalsIgnoreCase("Top") || podcastSearchResult.category.equalsIgnoreCase("Recommended") || podcastSearchResult.category.contains("מומלצים"))
                    return -1;
                if (t1.category.equalsIgnoreCase("Top") || t1.category.equalsIgnoreCase("Recommended") || t1.category.contains("מומלצים"))
                    return 1;


                return podcastSearchResult.category.compareTo(t1.category);
            }
        });


        if (searchResults.get(0).category != null) {
            // Add the first category.
            sections.add(new SimpleSectionedGridAdapter.Section(0, searchResults.get(0).category));

            for (int i = 1; i < searchResults.size(); i++) {
                if (searchResults.get(i).category == null)
                    continue;

                if (!searchResults.get(i).category.equals(searchResults.get(i - 1).category))
                    sections.add(new SimpleSectionedGridAdapter.Section(i, searchResults.get(i).category));
            }
            adapter.setSections(sections.toArray(new SimpleSectionedGridAdapter.Section[0]));
        }
    }
}

