package awais.instagrabber.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.PermissionChecker;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.collect.ImmutableList;

import java.util.Date;
import java.util.List;
import java.util.Set;

import awais.instagrabber.R;
import awais.instagrabber.activities.MainActivity;
import awais.instagrabber.adapters.FeedAdapterV2;
import awais.instagrabber.asyncs.LocationFetcher;
import awais.instagrabber.asyncs.LocationPostFetchService;
import awais.instagrabber.asyncs.PostFetcher;
import awais.instagrabber.customviews.PrimaryActionModeCallback;
import awais.instagrabber.databinding.FragmentLocationBinding;
import awais.instagrabber.databinding.LayoutLocationDetailsBinding;
import awais.instagrabber.db.datasources.FavoriteDataSource;
import awais.instagrabber.db.entities.Favorite;
import awais.instagrabber.db.repositories.FavoriteRepository;
import awais.instagrabber.db.repositories.RepositoryCallback;
import awais.instagrabber.dialogs.PostsLayoutPreferencesDialogFragment;
import awais.instagrabber.models.FeedModel;
import awais.instagrabber.models.LocationModel;
import awais.instagrabber.models.PostsLayoutPreferences;
import awais.instagrabber.models.StoryModel;
import awais.instagrabber.models.enums.FavoriteType;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.CookieUtils;
import awais.instagrabber.utils.DownloadUtils;
import awais.instagrabber.utils.TextUtils;
import awais.instagrabber.utils.Utils;
import awais.instagrabber.webservices.ServiceCallback;
import awais.instagrabber.webservices.StoriesService;
import awaisomereport.LogCollector;

import static androidx.core.content.PermissionChecker.checkSelfPermission;
import static awais.instagrabber.fragments.HashTagFragment.ARG_HASHTAG;
import static awais.instagrabber.utils.DownloadUtils.WRITE_PERMISSION;
import static awais.instagrabber.utils.Utils.logCollector;
import static awais.instagrabber.utils.Utils.settingsHelper;

public class LocationFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = "LocationFragment";
    private static final int STORAGE_PERM_REQUEST_CODE = 8020;
    private static final int STORAGE_PERM_REQUEST_CODE_FOR_SELECTION = 8030;

    private MainActivity fragmentActivity;
    private FragmentLocationBinding binding;
    private CoordinatorLayout root;
    private boolean shouldRefresh = true;
    private boolean hasStories = false;
    private boolean opening = false;
    private String locationId;
    private LocationModel locationModel;
    private ActionMode actionMode;
    private StoriesService storiesService;
    private AsyncTask<?, ?, ?> currentlyExecuting;
    private boolean isLoggedIn;
    private boolean storiesFetching;
    private Set<FeedModel> selectedFeedModels;
    private FeedModel downloadFeedModel;
    private int downloadChildPosition = -1;
    private PostsLayoutPreferences layoutPreferences = Utils.getPostsLayoutPreferences(Constants.PREF_LOCATION_POSTS_LAYOUT);
    private LayoutLocationDetailsBinding locationDetailsBinding;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            binding.posts.endSelection();
        }
    };
    private final PrimaryActionModeCallback multiSelectAction = new PrimaryActionModeCallback(
            R.menu.multi_select_download_menu, new PrimaryActionModeCallback.CallbacksHelper() {
        @Override
        public void onDestroy(final ActionMode mode) {
            binding.posts.endSelection();
        }

        @Override
        public boolean onActionItemClicked(final ActionMode mode,
                                           final MenuItem item) {
            if (item.getItemId() == R.id.action_download) {
                if (LocationFragment.this.selectedFeedModels == null) return false;
                final Context context = getContext();
                if (context == null) return false;
                if (checkSelfPermission(context, WRITE_PERMISSION) == PermissionChecker.PERMISSION_GRANTED) {
                    DownloadUtils.download(context, ImmutableList.copyOf(LocationFragment.this.selectedFeedModels));
                    binding.posts.endSelection();
                    return true;
                }
                requestPermissions(DownloadUtils.PERMS, STORAGE_PERM_REQUEST_CODE_FOR_SELECTION);
            }
            return false;
        }
    });
    private final FeedAdapterV2.FeedItemCallback feedItemCallback = new FeedAdapterV2.FeedItemCallback() {
        @Override
        public void onPostClick(final FeedModel feedModel, final View profilePicView, final View mainPostImage) {
            openPostDialog(feedModel, profilePicView, mainPostImage, -1);
        }

        @Override
        public void onSliderClick(final FeedModel feedModel, final int position) {
            openPostDialog(feedModel, null, null, position);
        }

        @Override
        public void onCommentsClick(final FeedModel feedModel) {
            final NavDirections commentsAction = LocationFragmentDirections.actionGlobalCommentsViewerFragment(
                    feedModel.getShortCode(),
                    feedModel.getPostId(),
                    feedModel.getProfileModel().getId()
            );
            NavHostFragment.findNavController(LocationFragment.this).navigate(commentsAction);
        }

        @Override
        public void onDownloadClick(final FeedModel feedModel, final int childPosition) {
            final Context context = getContext();
            if (context == null) return;
            if (checkSelfPermission(context, WRITE_PERMISSION) == PermissionChecker.PERMISSION_GRANTED) {
                DownloadUtils.showDownloadDialog(context, feedModel, childPosition);
                return;
            }
            downloadFeedModel = feedModel;
            downloadChildPosition = childPosition;
            requestPermissions(DownloadUtils.PERMS, STORAGE_PERM_REQUEST_CODE);
        }

        @Override
        public void onHashtagClick(final String hashtag) {
            final NavDirections action = LocationFragmentDirections.actionGlobalHashTagFragment(hashtag);
            NavHostFragment.findNavController(LocationFragment.this).navigate(action);
        }

        @Override
        public void onLocationClick(final FeedModel feedModel) {
            final NavDirections action = LocationFragmentDirections.actionGlobalLocationFragment(feedModel.getLocationId());
            NavHostFragment.findNavController(LocationFragment.this).navigate(action);
        }

        @Override
        public void onMentionClick(final String mention) {
            navigateToProfile(mention.trim());
        }

        @Override
        public void onNameClick(final FeedModel feedModel, final View profilePicView) {
            navigateToProfile("@" + feedModel.getProfileModel().getUsername());
        }

        @Override
        public void onProfilePicClick(final FeedModel feedModel, final View profilePicView) {
            navigateToProfile("@" + feedModel.getProfileModel().getUsername());
        }

        @Override
        public void onURLClick(final String url) {
            Utils.openURL(getContext(), url);
        }

        @Override
        public void onEmailClick(final String emailId) {
            Utils.openEmailAddress(getContext(), emailId);
        }

        private void openPostDialog(@NonNull final FeedModel feedModel,
                                    final View profilePicView,
                                    final View mainPostImage,
                                    final int position) {
            if (opening) return;
            if (TextUtils.isEmpty(feedModel.getProfileModel().getUsername())) {
                opening = true;
                new PostFetcher(feedModel.getShortCode(), newFeedModel -> {
                    opening = false;
                    if (newFeedModel == null) return;
                    openPostDialog(newFeedModel, profilePicView, mainPostImage, position);
                }).execute();
                return;
            }
            opening = true;
            final PostViewV2Fragment.Builder builder = PostViewV2Fragment
                    .builder(feedModel);
            if (position >= 0) {
                builder.setPosition(position);
            }
            if (!layoutPreferences.isAnimationDisabled()) {
                builder.setSharedProfilePicElement(profilePicView)
                       .setSharedMainPostElement(mainPostImage);
            }
            builder.build().show(getChildFragmentManager(), "post_view");
            opening = false;
        }
    };
    private final FeedAdapterV2.SelectionModeCallback selectionModeCallback = new FeedAdapterV2.SelectionModeCallback() {

        @Override
        public void onSelectionStart() {
            if (!onBackPressedCallback.isEnabled()) {
                final OnBackPressedDispatcher onBackPressedDispatcher = fragmentActivity.getOnBackPressedDispatcher();
                onBackPressedCallback.setEnabled(true);
                onBackPressedDispatcher.addCallback(getViewLifecycleOwner(), onBackPressedCallback);
            }
            if (actionMode == null) {
                actionMode = fragmentActivity.startActionMode(multiSelectAction);
            }
        }

        @Override
        public void onSelectionChange(final Set<FeedModel> selectedFeedModels) {
            final String title = getString(R.string.number_selected, selectedFeedModels.size());
            if (actionMode != null) {
                actionMode.setTitle(title);
            }
            LocationFragment.this.selectedFeedModels = selectedFeedModels;
        }

        @Override
        public void onSelectionEnd() {
            if (onBackPressedCallback.isEnabled()) {
                onBackPressedCallback.setEnabled(false);
                onBackPressedCallback.remove();
            }
            if (actionMode != null) {
                actionMode.finish();
                actionMode = null;
            }
        }
    };

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fragmentActivity = (MainActivity) requireActivity();
        storiesService = StoriesService.getInstance();
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        if (root != null) {
            shouldRefresh = false;
            fragmentActivity.setCollapsingView(locationDetailsBinding.getRoot());
            return root;
        }
        binding = FragmentLocationBinding.inflate(inflater, container, false);
        root = binding.getRoot();
        locationDetailsBinding = LayoutLocationDetailsBinding.inflate(inflater, fragmentActivity.getCollapsingToolbarView(), false);
        fragmentActivity.setCollapsingView(locationDetailsBinding.getRoot());
        return root;
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        if (!shouldRefresh) return;
        binding.swipeRefreshLayout.setOnRefreshListener(this);
        init();
        shouldRefresh = false;
    }

    @Override
    public void onRefresh() {
        binding.posts.refresh();
        fetchStories();
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        inflater.inflate(R.menu.topic_posts_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.layout) {
            showPostsLayoutPreferences();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final boolean granted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
        final Context context = getContext();
        if (context == null) return;
        if (requestCode == STORAGE_PERM_REQUEST_CODE && granted) {
            if (downloadFeedModel == null) return;
            DownloadUtils.showDownloadDialog(context, downloadFeedModel, downloadChildPosition);
            downloadFeedModel = null;
            downloadChildPosition = -1;
            return;
        }
        if (requestCode == STORAGE_PERM_REQUEST_CODE_FOR_SELECTION && granted) {
            DownloadUtils.download(context, ImmutableList.copyOf(selectedFeedModels));
            binding.posts.endSelection();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (locationDetailsBinding != null) {
            fragmentActivity.removeCollapsingView(locationDetailsBinding.getRoot());
        }
    }

    private void init() {
        if (getArguments() == null) return;
        final String cookie = settingsHelper.getString(Constants.COOKIE);
        isLoggedIn = !TextUtils.isEmpty(cookie) && CookieUtils.getUserIdFromCookie(cookie) != null;
        final LocationFragmentArgs fragmentArgs = LocationFragmentArgs.fromBundle(getArguments());
        locationId = fragmentArgs.getLocationId();
        locationDetailsBinding.favChip.setVisibility(View.GONE);
        locationDetailsBinding.btnMap.setVisibility(View.GONE);
        setTitle();
        fetchLocationModel();
    }

    private void setupPosts() {
        binding.posts.setViewModelStoreOwner(this)
                     .setLifeCycleOwner(this)
                     .setPostFetchService(new LocationPostFetchService(locationModel, isLoggedIn))
                     .setLayoutPreferences(layoutPreferences)
                     .addFetchStatusChangeListener(fetching -> updateSwipeRefreshState())
                     .setFeedItemCallback(feedItemCallback)
                     .setSelectionModeCallback(selectionModeCallback)
                     .init();
        binding.swipeRefreshLayout.setRefreshing(true);
    }

    private void fetchLocationModel() {
        stopCurrentExecutor();
        binding.swipeRefreshLayout.setRefreshing(true);
        currentlyExecuting = new LocationFetcher(locationId, result -> {
            locationModel = result;
            binding.swipeRefreshLayout.setRefreshing(false);
            if (locationModel == null) {
                final Context context = getContext();
                if (context == null) return;
                Toast.makeText(context, R.string.error_loading_profile, Toast.LENGTH_SHORT).show();
                return;
            }
            setTitle();
            setupLocationDetails();
            setupPosts();
            fetchStories();
            // fetchPosts();
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void setupLocationDetails() {
        final String locationId = locationModel.getId();
        // binding.swipeRefreshLayout.setRefreshing(true);
        locationDetailsBinding.mainLocationImage.setImageURI(locationModel.getSdProfilePic());
        final String postCount = String.valueOf(locationModel.getPostCount());
        final SpannableStringBuilder span = new SpannableStringBuilder(getResources().getQuantityString(R.plurals.main_posts_count_inline,
                locationModel.getPostCount() > 2000000000L ? 2000000000 : locationModel.getPostCount().intValue(),
                postCount));
        span.setSpan(new RelativeSizeSpan(1.2f), 0, postCount.length(), 0);
        span.setSpan(new StyleSpan(Typeface.BOLD), 0, postCount.length(), 0);
        locationDetailsBinding.mainLocPostCount.setText(span);
        locationDetailsBinding.mainLocPostCount.setVisibility(View.VISIBLE);
        locationDetailsBinding.locationFullName.setText(locationModel.getName());
        CharSequence biography = locationModel.getBio();
        // binding.locationBiography.setCaptionIsExpandable(true);
        // binding.locationBiography.setCaptionIsExpanded(true);

        if (TextUtils.isEmpty(biography)) {
            locationDetailsBinding.locationBiography.setVisibility(View.GONE);
        } else {
            locationDetailsBinding.locationBiography.setVisibility(View.VISIBLE);
            locationDetailsBinding.locationBiography.setText(biography);
            locationDetailsBinding.locationBiography.addOnHashtagListener(autoLinkItem -> {
                final NavController navController = NavHostFragment.findNavController(this);
                final Bundle bundle = new Bundle();
                final String originalText = autoLinkItem.getOriginalText().trim();
                bundle.putString(ARG_HASHTAG, originalText);
                navController.navigate(R.id.action_global_hashTagFragment, bundle);
            });
            locationDetailsBinding.locationBiography.addOnMentionClickListener(autoLinkItem -> {
                final String originalText = autoLinkItem.getOriginalText().trim();
                navigateToProfile(originalText);
            });
            locationDetailsBinding.locationBiography.addOnEmailClickListener(autoLinkItem -> Utils.openEmailAddress(getContext(),
                    autoLinkItem.getOriginalText()
                            .trim()));
            locationDetailsBinding.locationBiography
                    .addOnURLClickListener(autoLinkItem -> Utils.openURL(getContext(), autoLinkItem.getOriginalText().trim()));
            locationDetailsBinding.locationBiography.setOnLongClickListener(v -> {
                Utils.copyText(getContext(), biography);
                return true;
            });
        }

        if (!locationModel.getGeo().startsWith("geo:0.0,0.0?z=17")) {
            locationDetailsBinding.btnMap.setVisibility(View.VISIBLE);
            locationDetailsBinding.btnMap.setOnClickListener(v -> {
                final Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(locationModel.getGeo()));
                startActivity(intent);
            });
        } else {
            locationDetailsBinding.btnMap.setVisibility(View.GONE);
            locationDetailsBinding.btnMap.setOnClickListener(null);
        }

        final String url = locationModel.getUrl();
        if (TextUtils.isEmpty(url)) {
            locationDetailsBinding.locationUrl.setVisibility(View.GONE);
        } else if (!url.startsWith("http")) {
            locationDetailsBinding.locationUrl.setVisibility(View.VISIBLE);
            locationDetailsBinding.locationUrl.setText(TextUtils.getSpannableUrl("http://" + url));
        } else {
            locationDetailsBinding.locationUrl.setVisibility(View.VISIBLE);
            locationDetailsBinding.locationUrl.setText(TextUtils.getSpannableUrl(url));
        }
        final FavoriteDataSource dataSource = FavoriteDataSource.getInstance(getContext());
        final FavoriteRepository favoriteRepository = FavoriteRepository.getInstance(dataSource);
        locationDetailsBinding.favChip.setVisibility(View.VISIBLE);
        favoriteRepository.getFavorite(locationId, FavoriteType.LOCATION, new RepositoryCallback<Favorite>() {
            @Override
            public void onSuccess(final Favorite result) {
                locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_star_check_24);
                locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_star_check_24);
                locationDetailsBinding.favChip.setText(R.string.favorite_short);
                favoriteRepository.insertOrUpdateFavorite(new Favorite(
                        result.getId(),
                        locationId,
                        FavoriteType.LOCATION,
                        locationModel.getName(),
                        locationModel.getSdProfilePic(),
                        result.getDateAdded()
                ), new RepositoryCallback<Void>() {
                    @Override
                    public void onSuccess(final Void result) {}

                    @Override
                    public void onDataNotAvailable() {}
                });
            }

            @Override
            public void onDataNotAvailable() {
                locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_outline_star_plus_24);
                locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_outline_star_plus_24);
                locationDetailsBinding.favChip.setText(R.string.add_to_favorites);
            }
        });
        locationDetailsBinding.favChip.setOnClickListener(v -> {
            favoriteRepository.getFavorite(locationId, FavoriteType.LOCATION, new RepositoryCallback<Favorite>() {
                @Override
                public void onSuccess(final Favorite result) {
                    favoriteRepository.deleteFavorite(locationId, FavoriteType.LOCATION, new RepositoryCallback<Void>() {
                        @Override
                        public void onSuccess(final Void result) {
                            locationDetailsBinding.favChip.setText(R.string.add_to_favorites);
                            locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_outline_star_plus_24);
                            showSnackbar(getString(R.string.removed_from_favs));
                        }

                        @Override
                        public void onDataNotAvailable() {}
                    });
                }

                @Override
                public void onDataNotAvailable() {
                    favoriteRepository.insertOrUpdateFavorite(new Favorite(
                            0,
                            locationId,
                            FavoriteType.LOCATION,
                            locationModel.getName(),
                            locationModel.getSdProfilePic(),
                            new Date()
                    ), new RepositoryCallback<Void>() {
                        @Override
                        public void onSuccess(final Void result) {
                            locationDetailsBinding.favChip.setText(R.string.favorite_short);
                            locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_star_check_24);
                            showSnackbar(getString(R.string.added_to_favs_short));
                        }

                        @Override
                        public void onDataNotAvailable() {}
                    });
                }
            });
        });
        locationDetailsBinding.mainLocationImage.setOnClickListener(v -> {
            if (hasStories) {
                // show stories
                final NavDirections action = LocationFragmentDirections
                        .actionLocationFragmentToStoryViewerFragment(-1, null, false, true, locationId, locationModel.getName(), false, false);
                NavHostFragment.findNavController(this).navigate(action);
            }
        });
    }

    private void showSnackbar(final String message) {
        final Snackbar snackbar = Snackbar.make(root, message, BaseTransientBottomBar.LENGTH_LONG);
        snackbar.setAction(R.string.ok, v1 -> snackbar.dismiss())
                .setAnimationMode(BaseTransientBottomBar.ANIMATION_MODE_SLIDE)
                .setAnchorView(fragmentActivity.getBottomNavView())
                .show();
    }

    private void fetchStories() {
        if (isLoggedIn) {
            storiesFetching = true;
            storiesService.getUserStory(locationId,
                                        null,
                                        true,
                                        false,
                                        false,
                                        new ServiceCallback<List<StoryModel>>() {
                                            @Override
                                            public void onSuccess(final List<StoryModel> storyModels) {
                                                if (storyModels != null && !storyModels.isEmpty()) {
                                                    locationDetailsBinding.mainLocationImage.setStoriesBorder(1);
                                                    hasStories = true;
                                                }
                                                storiesFetching = false;
                                            }

                                            @Override
                                            public void onFailure(final Throwable t) {
                                                Log.e(TAG, "Error", t);
                                                storiesFetching = false;
                                            }
                                        });
        }
    }

    private void stopCurrentExecutor() {
        if (currentlyExecuting != null) {
            try {
                currentlyExecuting.cancel(true);
            } catch (final Exception e) {
                if (logCollector != null) logCollector.appendException(
                        e, LogCollector.LogFile.MAIN_HELPER, "stopCurrentExecutor");
                Log.e(TAG, "", e);
            }
        }
    }

    private void setTitle() {
        final ActionBar actionBar = fragmentActivity.getSupportActionBar();
        if (actionBar != null && locationModel != null) {
            actionBar.setTitle(locationModel.getName());
        }
    }

    private void updateSwipeRefreshState() {
        binding.swipeRefreshLayout.setRefreshing(binding.posts.isFetching() || storiesFetching);
    }

    private void navigateToProfile(final String username) {
        final NavController navController = NavHostFragment.findNavController(this);
        final Bundle bundle = new Bundle();
        bundle.putString("username", username);
        navController.navigate(R.id.action_global_profileFragment, bundle);
    }

    private void showPostsLayoutPreferences() {
        final PostsLayoutPreferencesDialogFragment fragment = new PostsLayoutPreferencesDialogFragment(
                Constants.PREF_LOCATION_POSTS_LAYOUT,
                preferences -> {
                    layoutPreferences = preferences;
                    new Handler().postDelayed(() -> binding.posts.setLayoutPreferences(preferences), 200);
                });
        fragment.show(getChildFragmentManager(), "posts_layout_preferences");
    }
}
