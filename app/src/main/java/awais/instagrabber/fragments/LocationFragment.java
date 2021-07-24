package awais.instagrabber.fragments;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.collect.ImmutableList;

import java.time.LocalDateTime;
import java.util.Set;

import awais.instagrabber.R;
import awais.instagrabber.activities.MainActivity;
import awais.instagrabber.adapters.FeedAdapterV2;
import awais.instagrabber.asyncs.LocationPostFetchService;
import awais.instagrabber.customviews.PrimaryActionModeCallback;
import awais.instagrabber.databinding.FragmentLocationBinding;
import awais.instagrabber.databinding.LayoutLocationDetailsBinding;
import awais.instagrabber.db.entities.Favorite;
import awais.instagrabber.db.repositories.FavoriteRepository;
import awais.instagrabber.dialogs.PostsLayoutPreferencesDialogFragment;
import awais.instagrabber.models.PostsLayoutPreferences;
import awais.instagrabber.models.enums.FavoriteType;
import awais.instagrabber.repositories.responses.Location;
import awais.instagrabber.repositories.responses.Media;
import awais.instagrabber.repositories.responses.User;
import awais.instagrabber.utils.AppExecutors;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.CookieUtils;
import awais.instagrabber.utils.CoroutineUtilsKt;
import awais.instagrabber.utils.DownloadUtils;
import awais.instagrabber.utils.TextUtils;
import awais.instagrabber.utils.Utils;
import awais.instagrabber.webservices.GraphQLRepository;
import awais.instagrabber.webservices.LocationRepository;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.Dispatchers;

import static awais.instagrabber.utils.Utils.settingsHelper;

public class LocationFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = "LocationFragment";

    private MainActivity fragmentActivity;
    private FragmentLocationBinding binding;
    private CoordinatorLayout root;
    private boolean shouldRefresh = true;
    private boolean opening = false;
    private long locationId;
    private Location locationModel;
    private ActionMode actionMode;
    private GraphQLRepository graphQLRepository;
    private LocationRepository locationRepository;
    private boolean isLoggedIn;
    private Set<Media> selectedFeedModels;
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
                DownloadUtils.download(context, ImmutableList.copyOf(LocationFragment.this.selectedFeedModels));
                binding.posts.endSelection();
                return true;
            }
            return false;
        }
    });
    private final FeedAdapterV2.FeedItemCallback feedItemCallback = new FeedAdapterV2.FeedItemCallback() {
        @Override
        public void onPostClick(final Media feedModel) {
            openPostDialog(feedModel, -1);
        }

        @Override
        public void onSliderClick(final Media feedModel, final int position) {
            openPostDialog(feedModel, position);
        }

        @Override
        public void onCommentsClick(final Media feedModel) {
            final User user = feedModel.getUser();
            if (user == null) return;
            try {
                final NavDirections commentsAction = LocationFragmentDirections.actionToComments(
                        feedModel.getCode(),
                        feedModel.getPk(),
                        user.getPk()
                );
                NavHostFragment.findNavController(LocationFragment.this).navigate(commentsAction);
            } catch (Exception e) {
                Log.e(TAG, "onCommentsClick: ", e);
            }
        }

        @Override
        public void onDownloadClick(final Media feedModel, final int childPosition, final View popupLocation) {
            final Context context = getContext();
            if (context == null) return;
            DownloadUtils.showDownloadDialog(context, feedModel, childPosition, popupLocation);
        }

        @Override
        public void onHashtagClick(final String hashtag) {
            try {
                final NavDirections action = LocationFragmentDirections.actionToHashtag(hashtag);
                NavHostFragment.findNavController(LocationFragment.this).navigate(action);
            } catch (Exception e) {
                Log.e(TAG, "onHashtagClick: ", e);
            }
        }

        @Override
        public void onLocationClick(final Media feedModel) {
            final Location location = feedModel.getLocation();
            if (location == null) return;
            final NavDirections action = LocationFragmentDirections.actionToLocation(location.getPk());
            NavHostFragment.findNavController(LocationFragment.this).navigate(action);
        }

        @Override
        public void onMentionClick(final String mention) {
            navigateToProfile(mention.trim());
        }

        @Override
        public void onNameClick(final Media feedModel) {
            final User user = feedModel.getUser();
            if (user == null) return;
            navigateToProfile("@" + user.getUsername());
        }

        @Override
        public void onProfilePicClick(final Media feedModel) {
            final User user = feedModel.getUser();
            if (user == null) return;
            navigateToProfile("@" + user.getUsername());
        }

        @Override
        public void onURLClick(final String url) {
            Utils.openURL(getContext(), url);
        }

        @Override
        public void onEmailClick(final String emailId) {
            Utils.openEmailAddress(getContext(), emailId);
        }

        private void openPostDialog(@NonNull final Media feedModel, final int position) {
            if (opening) return;
            final User user = feedModel.getUser();
            if (user == null) return;
            if (TextUtils.isEmpty(user.getUsername())) {
                opening = true;
                final String code = feedModel.getCode();
                if (code == null) return;
                graphQLRepository.fetchPost(
                        code,
                        CoroutineUtilsKt.getContinuation((media, throwable) -> AppExecutors.INSTANCE.getMainThread().execute(() -> {
                            opening = false;
                            if (throwable != null) {
                                Log.e(TAG, "Error", throwable);
                                return;
                            }
                            if (media == null) return;
                            openPostDialog(media, position);
                        }))
                );
                return;
            }
            opening = true;
            try {
                final NavDirections action = LocationFragmentDirections.actionToPost(feedModel, position);
                NavHostFragment.findNavController(LocationFragment.this).navigate(action);
            } catch (Exception e) {
                Log.e(TAG, "openPostDialog: ", e);
            }
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
        public void onSelectionChange(final Set<Media> selectedFeedModels) {
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
        final String cookie = settingsHelper.getString(Constants.COOKIE);
        isLoggedIn = !TextUtils.isEmpty(cookie) && CookieUtils.getUserIdFromCookie(cookie) > 0;
        locationRepository = isLoggedIn ? LocationRepository.Companion.getInstance() : null;
        // storiesRepository = StoriesRepository.Companion.getInstance();
        graphQLRepository = isLoggedIn ? null : GraphQLRepository.Companion.getInstance();
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        if (root != null) {
            shouldRefresh = false;
            return root;
        }
        binding = FragmentLocationBinding.inflate(inflater, container, false);
        root = binding.getRoot();
        locationDetailsBinding = binding.header;
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
    }

    @Override
    public void onResume() {
        super.onResume();
        fragmentActivity.setToolbar(binding.toolbar, this);
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
    public void onStop() {
        super.onStop();
        fragmentActivity.resetToolbar(this);
    }

    private void init() {
        if (getArguments() == null) return;
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
        // binding.posts.addOnScrollListener(new RecyclerView.OnScrollListener() {
        //     @Override
        //     public void onScrolled(@NonNull final RecyclerView recyclerView, final int dx, final int dy) {
        //         super.onScrolled(recyclerView, dx, dy);
        //         final boolean canScrollVertically = recyclerView.canScrollVertically(-1);
        //         final MotionScene.Transition transition = root.getTransition(R.id.transition);
        //         if (transition != null) {
        //             transition.setEnable(!canScrollVertically);
        //         }
        //     }
        // });
    }

    private void fetchLocationModel() {
        binding.swipeRefreshLayout.setRefreshing(true);
        final Continuation<Location> cb = CoroutineUtilsKt.getContinuation((result, t) -> {
            locationModel = result;
            AppExecutors.INSTANCE.getMainThread().execute(() -> {
                setupLocationDetails();
                binding.swipeRefreshLayout.setRefreshing(false);
            });
        }, Dispatchers.getIO());
        if (isLoggedIn) locationRepository.fetch(locationId, cb);
        else graphQLRepository.fetchLocation(locationId, cb);
    }

    private void setupLocationDetails() {
        if (locationModel == null) {
            try {
                Toast.makeText(getContext(), R.string.error_loading_location, Toast.LENGTH_SHORT).show();
                binding.swipeRefreshLayout.setEnabled(false);
            } catch (Exception ignored) {}
            return;
        }
        setTitle();
        setupPosts();
        //        fetchStories();
        final long locationId = locationModel.getPk();
        // binding.swipeRefreshLayout.setRefreshing(true);
        locationDetailsBinding.mainLocationImage.setImageURI("res:/" + R.drawable.ic_location);
        // final String postCount = String.valueOf(locationModel.getChildCommentCount());
        // final SpannableStringBuilder span = new SpannableStringBuilder(getResources().getQuantityString(R.plurals.main_posts_count_inline,
        //                                                                                                 locationModel.getPostCount() > 2000000000L
        //                                                                                                 ? 2000000000
        //                                                                                                 : locationModel.getPostCount().intValue(),
        //                                                                                                 postCount));
        // span.setSpan(new RelativeSizeSpan(1.2f), 0, postCount.length(), 0);
        // span.setSpan(new StyleSpan(Typeface.BOLD), 0, postCount.length(), 0);
        // locationDetailsBinding.mainLocPostCount.setText(span);
        // locationDetailsBinding.mainLocPostCount.setVisibility(View.VISIBLE);
        locationDetailsBinding.locationFullName.setText(locationModel.getName());
        CharSequence biography = locationModel.getAddress() + "\n" + locationModel.getCity();
        // binding.locationBiography.setCaptionIsExpandable(true);
        // binding.locationBiography.setCaptionIsExpanded(true);

        final Context context = getContext();
        if (context == null) return;
        if (TextUtils.isEmpty(biography)) {
            locationDetailsBinding.locationBiography.setVisibility(View.GONE);
        } else {
            locationDetailsBinding.locationBiography.setVisibility(View.VISIBLE);
            locationDetailsBinding.locationBiography.setText(biography);
            // locationDetailsBinding.locationBiography.addOnHashtagListener(autoLinkItem -> {
            //     final NavController navController = NavHostFragment.findNavController(this);
            //     final Bundle bundle = new Bundle();
            //     final String originalText = autoLinkItem.getOriginalText().trim();
            //     bundle.putString(ARG_HASHTAG, originalText);
            //     navController.navigate(R.id.action_global_hashTagFragment, bundle);
            // });
            // locationDetailsBinding.locationBiography.addOnMentionClickListener(autoLinkItem -> {
            //     final String originalText = autoLinkItem.getOriginalText().trim();
            //     navigateToProfile(originalText);
            // });
            // locationDetailsBinding.locationBiography.addOnEmailClickListener(autoLinkItem -> Utils.openEmailAddress(context,
            //                                                                                                         autoLinkItem.getOriginalText()
            //                                                                                                                     .trim()));
            // locationDetailsBinding.locationBiography
            //         .addOnURLClickListener(autoLinkItem -> Utils.openURL(context, autoLinkItem.getOriginalText().trim()));
            locationDetailsBinding.locationBiography.setOnLongClickListener(v -> {
                Utils.copyText(context, biography);
                return true;
            });
        }

        if (!locationModel.getGeo().startsWith("geo:0.0,0.0?z=17")) {
            locationDetailsBinding.btnMap.setVisibility(View.VISIBLE);
            locationDetailsBinding.btnMap.setOnClickListener(v -> {
                try {
                    final Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(locationModel.getGeo()));
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(context, R.string.no_external_map_app, Toast.LENGTH_LONG).show();
                    Log.e(TAG, "setupLocationDetails: ", e);
                } catch (Exception e) {
                    Log.e(TAG, "setupLocationDetails: ", e);
                }
            });
        } else {
            locationDetailsBinding.btnMap.setVisibility(View.GONE);
            locationDetailsBinding.btnMap.setOnClickListener(null);
        }

        final FavoriteRepository favoriteRepository = FavoriteRepository.Companion.getInstance(context);
        locationDetailsBinding.favChip.setVisibility(View.VISIBLE);
        favoriteRepository.getFavorite(
                String.valueOf(locationId),
                FavoriteType.LOCATION,
                CoroutineUtilsKt.getContinuation((favorite, throwable) -> AppExecutors.INSTANCE.getMainThread().execute(() -> {
                    if (throwable != null || favorite == null) {
                        locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_outline_star_plus_24);
                        locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_outline_star_plus_24);
                        locationDetailsBinding.favChip.setText(R.string.add_to_favorites);
                        Log.e(TAG, "setupLocationDetails: ", throwable);
                        return;
                    }
                    locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_star_check_24);
                    locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_star_check_24);
                    locationDetailsBinding.favChip.setText(R.string.favorite_short);
                    favoriteRepository.insertOrUpdateFavorite(
                            new Favorite(
                                    favorite.getId(),
                                    String.valueOf(locationId),
                                    FavoriteType.LOCATION,
                                    locationModel.getName(),
                                    "res:/" + R.drawable.ic_location,
                                    favorite.getDateAdded()
                            ),
                            CoroutineUtilsKt.getContinuation((unit, throwable1) -> AppExecutors.INSTANCE.getMainThread().execute(() -> {
                                if (throwable1 != null) {
                                    Log.e(TAG, "onSuccess: ", throwable1);
                                }
                            }), Dispatchers.getIO())
                    );
                }), Dispatchers.getIO())
        );
        locationDetailsBinding.favChip.setOnClickListener(v -> favoriteRepository.getFavorite(
                String.valueOf(locationId),
                FavoriteType.LOCATION,
                CoroutineUtilsKt.getContinuation((favorite, throwable) -> AppExecutors.INSTANCE.getMainThread().execute(() -> {
                    if (throwable != null) {
                        Log.e(TAG, "setupLocationDetails: ", throwable);
                        return;
                    }
                    if (favorite == null) {
                        favoriteRepository.insertOrUpdateFavorite(
                                new Favorite(
                                        0,
                                        String.valueOf(locationId),
                                        FavoriteType.LOCATION,
                                        locationModel.getName(),
                                        "res:/" + R.drawable.ic_location,
                                        LocalDateTime.now()
                                ),
                                CoroutineUtilsKt.getContinuation((unit, throwable1) -> AppExecutors.INSTANCE.getMainThread().execute(() -> {
                                    if (throwable1 != null) {
                                        Log.e(TAG, "onDataNotAvailable: ", throwable1);
                                        return;
                                    }
                                    locationDetailsBinding.favChip.setText(R.string.favorite_short);
                                    locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_star_check_24);
                                    showSnackbar(getString(R.string.added_to_favs));
                                }), Dispatchers.getIO())
                        );
                        return;
                    }
                    favoriteRepository.deleteFavorite(
                            String.valueOf(locationId),
                            FavoriteType.LOCATION,
                            CoroutineUtilsKt.getContinuation((unit, throwable1) -> AppExecutors.INSTANCE.getMainThread().execute(() -> {
                                if (throwable1 != null) {
                                    Log.e(TAG, "onSuccess: ", throwable1);
                                    return;
                                }
                                locationDetailsBinding.favChip.setText(R.string.add_to_favorites);
                                locationDetailsBinding.favChip.setChipIconResource(R.drawable.ic_outline_star_plus_24);
                                showSnackbar(getString(R.string.removed_from_favs));
                            }), Dispatchers.getIO())
                    );
                }), Dispatchers.getIO())
        ));
    }

    private void showSnackbar(final String message) {
        @SuppressLint("ShowToast") final Snackbar snackbar = Snackbar.make(root, message, BaseTransientBottomBar.LENGTH_LONG);
        snackbar.setAction(R.string.ok, v1 -> snackbar.dismiss())
                .setAnimationMode(BaseTransientBottomBar.ANIMATION_MODE_SLIDE)
                .setAnchorView(fragmentActivity.getBottomNavView())
                .show();
    }

    private void setTitle() {
        final ActionBar actionBar = fragmentActivity.getSupportActionBar();
        if (actionBar != null && locationModel != null) {
            actionBar.setTitle(locationModel.getName());
        }
    }

    private void updateSwipeRefreshState() {
        AppExecutors.INSTANCE.getMainThread().execute(() -> binding.swipeRefreshLayout.setRefreshing(binding.posts.isFetching()));
    }

    private void navigateToProfile(final String username) {
        try {
            final NavDirections action = LocationFragmentDirections.actionToProfile().setUsername(username);
            NavHostFragment.findNavController(this).navigate(action);
        } catch (Exception e) {
            Log.e(TAG, "navigateToProfile: ", e);
        }
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
