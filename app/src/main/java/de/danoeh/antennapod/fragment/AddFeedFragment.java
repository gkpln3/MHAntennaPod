package de.danoeh.antennapod.fragment;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import com.google.android.material.snackbar.Snackbar;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.activity.OnlineFeedViewActivity;
import de.danoeh.antennapod.activity.OpmlImportActivity;
import de.danoeh.antennapod.core.feed.Feed;
import de.danoeh.antennapod.core.storage.DBTasks;
import de.danoeh.antennapod.core.storage.DownloadRequestException;
import de.danoeh.antennapod.core.util.SortOrder;
import de.danoeh.antennapod.discovery.CombinedSearcher;
import de.danoeh.antennapod.discovery.FyydPodcastSearcher;
import de.danoeh.antennapod.discovery.ItunesPodcastSearcher;
import de.danoeh.antennapod.discovery.PodcastIndexPodcastSearcher;
import de.danoeh.antennapod.fragment.gpodnet.GpodnetMainFragment;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import java.util.Collections;

/**
 * Provides actions for adding new podcast subscriptions.
 */
public class AddFeedFragment extends Fragment {

    public static final String TAG = "AddFeedFragment";
    private static final int REQUEST_CODE_CHOOSE_OPML_IMPORT_PATH = 1;
    private static final int REQUEST_CODE_ADD_LOCAL_FOLDER = 2;

    private EditText combinedFeedSearchBox;
    private MainActivity activity;

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View root = inflater.inflate(R.layout.addfeed, container, false);
        activity = (MainActivity) getActivity();
        Toolbar toolbar = root.findViewById(R.id.toolbar);
        ((MainActivity) getActivity()).setupToolbarToggle(toolbar);

        root.findViewById(R.id.btn_search_itunes).setOnClickListener(v
                -> activity.loadChildFragment(OnlineSearchFragment.newInstance(ItunesPodcastSearcher.class)));
        root.findViewById(R.id.btn_search_fyyd).setOnClickListener(v
                -> activity.loadChildFragment(OnlineSearchFragment.newInstance(FyydPodcastSearcher.class)));
        root.findViewById(R.id.btn_search_gpodder).setOnClickListener(v
                -> activity.loadChildFragment(new GpodnetMainFragment()));
        root.findViewById(R.id.btn_search_podcastindex).setOnClickListener(v
                -> activity.loadChildFragment(OnlineSearchFragment.newInstance(PodcastIndexPodcastSearcher.class)));

        combinedFeedSearchBox = root.findViewById(R.id.combinedFeedSearchBox);
        combinedFeedSearchBox.setOnEditorActionListener((v, actionId, event) -> {
            performSearch();
            return true;
        });
        root.findViewById(R.id.btn_add_via_url).setOnClickListener(v
                -> showAddViaUrlDialog());

        root.findViewById(R.id.btn_opml_import).setOnClickListener(v -> {
            try {
                Intent intentGetContentAction = new Intent(Intent.ACTION_GET_CONTENT);
                intentGetContentAction.addCategory(Intent.CATEGORY_OPENABLE);
                intentGetContentAction.setType("*/*");
                startActivityForResult(intentGetContentAction, REQUEST_CODE_CHOOSE_OPML_IMPORT_PATH);
            } catch (ActivityNotFoundException e) {
                e.printStackTrace();
                ((MainActivity) getActivity())
                        .showSnackbarAbovePlayer(R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG);
            }
        });
        root.findViewById(R.id.btn_add_local_folder).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < 21) {
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_CODE_ADD_LOCAL_FOLDER);
            } catch (ActivityNotFoundException e) {
                e.printStackTrace();
                ((MainActivity) getActivity())
                        .showSnackbarAbovePlayer(R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG);
            }
        });
        if (Build.VERSION.SDK_INT < 21) {
            root.findViewById(R.id.btn_add_local_folder).setVisibility(View.GONE);
        }
        root.findViewById(R.id.search_icon).setOnClickListener(view -> performSearch());
        return root;
    }

    private void showAddViaUrlDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.add_podcast_by_url);
        View content = View.inflate(getContext(), R.layout.edit_text_dialog, null);
        EditText editText = content.findViewById(R.id.text);
        editText.setHint(R.string.add_podcast_by_url_hint);
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        String clipboardContent = clipboard.getText() != null ? clipboard.getText().toString() : "";
        if (clipboardContent.trim().startsWith("http")) {
            editText.setText(clipboardContent.trim());
        }
        builder.setView(content);
        builder.setPositiveButton(R.string.confirm_label, (dialog, which) -> addUrl(editText.getText().toString()));
        builder.setNegativeButton(R.string.cancel_label, null);
        builder.show();
    }

    private void addUrl(String url) {
        Intent intent = new Intent(getActivity(), OnlineFeedViewActivity.class);
        intent.putExtra(OnlineFeedViewActivity.ARG_FEEDURL, url);
        startActivity(intent);
    }

    private void performSearch() {
        String query = combinedFeedSearchBox.getText().toString();

        if (query.matches("http[s]?://.*")) {
            addUrl(query);
            return;
        }
        activity.loadChildFragment(OnlineSearchFragment.newInstance(CombinedSearcher.class, query));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();

        if (requestCode == REQUEST_CODE_CHOOSE_OPML_IMPORT_PATH) {
            Intent intent = new Intent(getContext(), OpmlImportActivity.class);
            intent.setData(uri);
            startActivity(intent);
        } else if (requestCode == REQUEST_CODE_ADD_LOCAL_FOLDER) {
            Observable.fromCallable(() -> addLocalFolder(uri))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        feed -> {
                            Fragment fragment = FeedItemlistFragment.newInstance(feed.getId());
                            ((MainActivity) getActivity()).loadChildFragment(fragment);
                        }, error -> {
                            Log.e(TAG, Log.getStackTraceString(error));
                            ((MainActivity) getActivity())
                                    .showSnackbarAbovePlayer(error.getLocalizedMessage(), Snackbar.LENGTH_LONG);
                        });
        }
    }

    private Feed addLocalFolder(Uri uri) throws DownloadRequestException {
        if (Build.VERSION.SDK_INT < 21) {
            return null;
        }
        getActivity().getContentResolver()
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        DocumentFile documentFile = DocumentFile.fromTreeUri(getContext(), uri);
        if (documentFile == null) {
            throw new IllegalArgumentException("Unable to retrieve document tree");
        }
        Feed dirFeed = new Feed(Feed.PREFIX_LOCAL_FOLDER + uri.toString(), null, documentFile.getName());
        dirFeed.setDescription(getString(R.string.local_feed_description));
        dirFeed.setItems(Collections.emptyList());
        dirFeed.setSortOrder(SortOrder.EPISODE_TITLE_A_Z);
        Feed fromDatabase = DBTasks.updateFeed(getContext(), dirFeed, false);
        DBTasks.forceRefreshFeed(getContext(), fromDatabase, true);
        return fromDatabase;
    }
}
