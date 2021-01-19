package awais.instagrabber.fragments.directmessages;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import awais.instagrabber.ProfileNavGraphDirections;
import awais.instagrabber.R;
import awais.instagrabber.UserSearchNavGraphDirections;
import awais.instagrabber.adapters.DirectPendingUsersAdapter;
import awais.instagrabber.adapters.DirectPendingUsersAdapter.PendingUser;
import awais.instagrabber.adapters.DirectPendingUsersAdapter.PendingUserCallback;
import awais.instagrabber.adapters.DirectUsersAdapter;
import awais.instagrabber.customviews.helpers.TextWatcherAdapter;
import awais.instagrabber.databinding.FragmentDirectMessagesSettingsBinding;
import awais.instagrabber.dialogs.ConfirmDialogFragment;
import awais.instagrabber.dialogs.ConfirmDialogFragment.ConfirmDialogFragmentCallback;
import awais.instagrabber.dialogs.MultiOptionDialogFragment;
import awais.instagrabber.dialogs.MultiOptionDialogFragment.Option;
import awais.instagrabber.fragments.UserSearchFragment;
import awais.instagrabber.fragments.UserSearchFragmentDirections;
import awais.instagrabber.models.Resource;
import awais.instagrabber.repositories.responses.User;
import awais.instagrabber.repositories.responses.directmessages.DirectThread;
import awais.instagrabber.repositories.responses.directmessages.DirectThreadParticipantRequestsResponse;
import awais.instagrabber.repositories.responses.directmessages.RankedRecipient;
import awais.instagrabber.viewmodels.DirectInboxViewModel;
import awais.instagrabber.viewmodels.DirectSettingsViewModel;

public class DirectMessageSettingsFragment extends Fragment implements ConfirmDialogFragmentCallback {
    private static final String TAG = DirectMessageSettingsFragment.class.getSimpleName();
    private static final int APPROVAL_REQUIRED_REQUEST_CODE = 200;
    private static final int LEAVE_THREAD_REQUEST_CODE = 201;

    private FragmentDirectMessagesSettingsBinding binding;
    private DirectSettingsViewModel viewModel;
    private DirectUsersAdapter usersAdapter;
    private boolean isPendingRequestsSetupDone = false;
    private DirectPendingUsersAdapter pendingUsersAdapter;
    private Set<User> approvalRequiredUsers;
    // private List<Option<String>> options;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final NavController navController = NavHostFragment.findNavController(this);
        final ViewModelStoreOwner viewModelStoreOwner = navController.getViewModelStoreOwner(R.id.direct_messages_nav_graph);
        final DirectInboxViewModel inboxViewModel = new ViewModelProvider(viewModelStoreOwner).get(DirectInboxViewModel.class);
        final List<DirectThread> threads = inboxViewModel.getThreads().getValue();
        final Bundle arguments = getArguments();
        if (arguments == null) {
            navController.navigateUp();
            return;
        }
        final DirectMessageSettingsFragmentArgs fragmentArgs = DirectMessageSettingsFragmentArgs.fromBundle(arguments);
        final String threadId = fragmentArgs.getThreadId();
        final Optional<DirectThread> first = threads != null ? threads.stream()
                                                                      .filter(thread -> thread.getThreadId().equals(threadId))
                                                                      .findFirst()
                                                             : Optional.empty();
        if (!first.isPresent()) {
            navController.navigateUp();
            return;
        }
        viewModel = new ViewModelProvider(this).get(DirectSettingsViewModel.class);
        viewModel.setViewer(inboxViewModel.getViewer());
        viewModel.setThread(first.get());
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container,
                             final Bundle savedInstanceState) {
        binding = FragmentDirectMessagesSettingsBinding.inflate(inflater, container, false);
        // final String threadId = DirectMessageSettingsFragmentArgs.fromBundle(getArguments()).getThreadId();
        // threadTitle = DirectMessageSettingsFragmentArgs.fromBundle(getArguments()).getTitle();
        // binding.swipeRefreshLayout.setEnabled(false);

        // final ActionBar actionBar = fragmentActivity.getSupportActionBar();
        // if (actionBar != null) {
        //     actionBar.setTitle(threadTitle);
        // }

        // titleSend.setOnClickListener(v -> new ChangeSettings(titleText.getText().toString()).execute("update_title"));

        // binding.titleText.addTextChangedListener(new TextWatcherAdapter() {
        //     @Override
        //     public void onTextChanged(CharSequence s, int start, int before, int count) {
        //         binding.titleSend.setVisibility(s.toString().equals(threadTitle) ? View.GONE : View.VISIBLE);
        //     }
        // });

        // final AppCompatButton btnLeave = binding.btnLeave;
        // btnLeave.setOnClickListener(v -> new AlertDialog.Builder(context)
        //         .setTitle(R.string.dms_action_leave_question)
        //         .setPositiveButton(R.string.yes, (x, y) -> new ChangeSettings(titleText.getText().toString()).execute("leave"))
        //         .setNegativeButton(R.string.no, null)
        //         .show());

        // currentlyRunning = new DirectMessageInboxThreadFetcher(threadId, null, null, fetchListener).execute();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        init();
        setupObservers();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        isPendingRequestsSetupDone = false;
    }

    private void setupObservers() {
        viewModel.getUsers().observe(getViewLifecycleOwner(), users -> {
            if (usersAdapter == null) return;
            usersAdapter.submitUsers(users.first, users.second);
        });
        viewModel.getTitle().observe(getViewLifecycleOwner(), title -> binding.titleEdit.setText(title));
        viewModel.getAdminUserIds().observe(getViewLifecycleOwner(), adminUserIds -> {
            if (usersAdapter == null) return;
            usersAdapter.setAdminUserIds(adminUserIds);
        });
        viewModel.getMuted().observe(getViewLifecycleOwner(), muted -> binding.muteMessages.setChecked(muted));
        if (viewModel.isViewerAdmin()) {
            viewModel.getApprovalRequiredToJoin().observe(getViewLifecycleOwner(), required -> binding.approvalRequired.setChecked(required));
            viewModel.getPendingRequests().observe(getViewLifecycleOwner(), this::setPendingRequests);
        }
        final NavController navController = NavHostFragment.findNavController(this);
        final NavBackStackEntry backStackEntry = navController.getCurrentBackStackEntry();
        if (backStackEntry != null) {
            final MutableLiveData<Object> resultLiveData = backStackEntry.getSavedStateHandle().getLiveData("result");
            resultLiveData.observe(getViewLifecycleOwner(), result -> {
                if ((result instanceof RankedRecipient)) {
                    final RankedRecipient recipient = (RankedRecipient) result;
                    final User user = getUser(recipient);
                    // Log.d(TAG, "result: " + user);
                    if (user != null) {
                        addMembers(Collections.singleton(recipient.getUser()));
                    }
                } else if ((result instanceof Set)) {
                    try {
                        //noinspection unchecked
                        final Set<RankedRecipient> recipients = (Set<RankedRecipient>) result;
                        final Set<User> users = recipients.stream()
                                                          .filter(Objects::nonNull)
                                                          .map(this::getUser)
                                                          .filter(Objects::nonNull)
                                                          .collect(Collectors.toSet());
                        // Log.d(TAG, "result: " + users);
                        addMembers(users);
                    } catch (Exception e) {
                        Log.e(TAG, "search users result: ", e);
                        Snackbar.make(binding.getRoot(), e.getMessage() != null ? e.getMessage() : "", Snackbar.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    private void addMembers(final Set<User> users) {
        final Boolean approvalRequired = viewModel.getApprovalRequiredToJoin().getValue();
        if (!viewModel.isViewerAdmin() && approvalRequired != null && approvalRequired) {
            approvalRequiredUsers = users;
            final ConfirmDialogFragment confirmDialogFragment = ConfirmDialogFragment.newInstance(
                    APPROVAL_REQUIRED_REQUEST_CODE,
                    R.string.admin_approval_required,
                    R.string.admin_approval_required_description,
                    R.string.ok,
                    R.string.cancel,
                    -1
            );
            confirmDialogFragment.show(getChildFragmentManager(), "approval_required_dialog");
            return;
        }
        final LiveData<Resource<Object>> detailsChangeResourceLiveData = viewModel.addMembers(users);
        observeDetailsChange(detailsChangeResourceLiveData);
    }

    @Nullable
    private User getUser(@NonNull final RankedRecipient recipient) {
        User user = null;
        if (recipient.getUser() != null) {
            user = recipient.getUser();
        } else if (recipient.getThread() != null && !recipient.getThread().isGroup()) {
            user = recipient.getThread().getUsers().get(0);
        }
        return user;
    }

    private void init() {
        setupSettings();
        setupMembers();
    }

    private void setupSettings() {
        binding.groupSettings.setVisibility(viewModel.isGroup() ? View.VISIBLE : View.GONE);
        if (!viewModel.isGroup()) return;
        binding.titleEdit.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
                if (s.toString().trim().equals(viewModel.getTitle().getValue())) {
                    binding.titleEditInputLayout.setSuffixText(null);
                    return;
                }
                binding.titleEditInputLayout.setSuffixText(getString(R.string.save));
            }
        });
        binding.titleEditInputLayout.getSuffixTextView().setOnClickListener(v -> {
            final Editable text = binding.titleEdit.getText();
            if (text == null) return;
            final String newTitle = text.toString().trim();
            if (newTitle.equals(viewModel.getTitle().getValue())) return;
            observeDetailsChange(viewModel.updateTitle(newTitle));
        });
        binding.addMembers.setOnClickListener(v -> {
            if (!isAdded()) return;
            final NavController navController = NavHostFragment.findNavController(this);
            final NavDestination currentDestination = navController.getCurrentDestination();
            if (currentDestination == null) return;
            if (currentDestination.getId() != R.id.directMessagesSettingsFragment) return;
            final Pair<List<User>, List<User>> users = viewModel.getUsers().getValue();
            final long[] currentUserIds;
            if (users != null && users.first != null) {
                final List<User> currentMembers = users.first;
                currentUserIds = currentMembers.stream()
                                               .mapToLong(User::getPk)
                                               .sorted()
                                               .toArray();
            } else {
                currentUserIds = new long[0];
            }
            final UserSearchNavGraphDirections.ActionGlobalUserSearch actionGlobalUserSearch = UserSearchFragmentDirections
                    .actionGlobalUserSearch()
                    .setTitle(getString(R.string.add_members))
                    .setActionLabel(getString(R.string.add))
                    .setHideUserIds(currentUserIds)
                    .setSearchMode(UserSearchFragment.SearchMode.RAVEN)
                    .setMultiple(true);
            navController.navigate(actionGlobalUserSearch);
        });
        binding.muteMessagesLabel.setOnClickListener(v -> binding.muteMessages.toggle());
        binding.muteMessages.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final LiveData<Resource<Object>> resourceLiveData = isChecked ? viewModel.mute() : viewModel.unmute();
            handleSwitchChangeResource(resourceLiveData, buttonView);
        });
        binding.muteMentionsLabel.setOnClickListener(v -> binding.muteMentions.toggle());
        binding.muteMentions.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final LiveData<Resource<Object>> resourceLiveData = isChecked ? viewModel.muteMentions() : viewModel.unmuteMentions();
            handleSwitchChangeResource(resourceLiveData, buttonView);
        });
        setApprovalRelatedUI();
        binding.leave.setOnClickListener(v -> {
            final ConfirmDialogFragment confirmDialogFragment = ConfirmDialogFragment.newInstance(
                    LEAVE_THREAD_REQUEST_CODE,
                    R.string.dms_action_leave_question,
                    -1,
                    R.string.yes,
                    R.string.no,
                    -1
            );
            confirmDialogFragment.show(getChildFragmentManager(), "leave_thread_confirmation_dialog");
        });
    }

    private void setApprovalRelatedUI() {
        if (!viewModel.isViewerAdmin()) {
            binding.pendingMembersGroup.setVisibility(View.GONE);
            binding.approvalRequired.setVisibility(View.GONE);
            binding.approvalRequiredLabel.setVisibility(View.GONE);
            return;
        }
        binding.approvalRequired.setVisibility(View.VISIBLE);
        binding.approvalRequiredLabel.setVisibility(View.VISIBLE);
        binding.approvalRequiredLabel.setOnClickListener(v -> binding.approvalRequired.toggle());
        binding.approvalRequired.setOnCheckedChangeListener((buttonView, isChecked) -> {
            final LiveData<Resource<Object>> resourceLiveData = isChecked ? viewModel.approvalRequired() : viewModel.approvalNotRequired();
            handleSwitchChangeResource(resourceLiveData, buttonView);
        });
    }

    private void handleSwitchChangeResource(final LiveData<Resource<Object>> resourceLiveData, final CompoundButton buttonView) {
        resourceLiveData.observe(getViewLifecycleOwner(), resource -> {
            if (resource == null) return;
            switch (resource.status) {
                case SUCCESS:
                    buttonView.setEnabled(true);
                    break;
                case ERROR:
                    buttonView.setEnabled(true);
                    buttonView.setChecked(!buttonView.isChecked());
                    if (resource.message != null) {
                        Snackbar.make(binding.getRoot(), resource.message, Snackbar.LENGTH_LONG).show();
                    }
                    break;
                case LOADING:
                    buttonView.setEnabled(false);
                    break;
            }
        });
    }

    private void setupMembers() {
        final Context context = getContext();
        if (context == null) return;
        binding.users.setLayoutManager(new LinearLayoutManager(context));
        final User inviter = viewModel.getThread().getInviter();
        usersAdapter = new DirectUsersAdapter(
                inviter != null ? inviter.getPk() : -1,
                (position, user, selected) -> {
                    final ProfileNavGraphDirections.ActionGlobalProfileFragment directions = ProfileNavGraphDirections
                            .actionGlobalProfileFragment()
                            .setUsername("@" + user.getUsername());
                    NavHostFragment.findNavController(this).navigate(directions);
                },
                (position, user) -> {
                    final ArrayList<Option<String>> options = viewModel.createUserOptions(user);
                    if (options == null || options.isEmpty()) return true;
                    final MultiOptionDialogFragment<String> fragment = MultiOptionDialogFragment.newInstance(-1, options);
                    fragment.setSingleCallback(new MultiOptionDialogFragment.MultiOptionDialogSingleCallback<String>() {
                        @Override
                        public void onSelect(final String action) {
                            if (action == null) return;
                            observeDetailsChange(viewModel.doAction(user, action));
                        }

                        @Override
                        public void onCancel() {}
                    });
                    final FragmentManager fragmentManager = getChildFragmentManager();
                    fragment.show(fragmentManager, "actions");
                    return true;
                }
        );
        binding.users.setAdapter(usersAdapter);
    }

    private void setPendingRequests(final DirectThreadParticipantRequestsResponse requests) {
        if (requests == null || requests.getUsers() == null || requests.getUsers().isEmpty()) {
            binding.pendingMembersGroup.setVisibility(View.GONE);
            return;
        }
        if (!isPendingRequestsSetupDone) {
            final Context context = getContext();
            if (context == null) return;
            binding.pendingMembers.setLayoutManager(new LinearLayoutManager(context));
            pendingUsersAdapter = new DirectPendingUsersAdapter(new PendingUserCallback() {
                @Override
                public void onClick(final int position, final PendingUser pendingUser) {
                    final ProfileNavGraphDirections.ActionGlobalProfileFragment directions = ProfileNavGraphDirections
                            .actionGlobalProfileFragment()
                            .setUsername("@" + pendingUser.getUser().getUsername());
                    NavHostFragment.findNavController(DirectMessageSettingsFragment.this).navigate(directions);
                }

                @Override
                public void onApprove(final int position, final PendingUser pendingUser) {
                    final LiveData<Resource<Object>> resourceLiveData = viewModel.approveUsers(Collections.singletonList(pendingUser.getUser()));
                    observeApprovalChange(resourceLiveData, position, pendingUser);
                }

                @Override
                public void onDeny(final int position, final PendingUser pendingUser) {
                    final LiveData<Resource<Object>> resourceLiveData = viewModel.denyUsers(Collections.singletonList(pendingUser.getUser()));
                    observeApprovalChange(resourceLiveData, position, pendingUser);
                }
            });
            binding.pendingMembers.setAdapter(pendingUsersAdapter);
            binding.pendingMembersGroup.setVisibility(View.VISIBLE);
            isPendingRequestsSetupDone = true;
        }
        if (pendingUsersAdapter != null) {
            pendingUsersAdapter.submitPendingRequests(requests);
        }
    }

    private void observeDetailsChange(@NonNull final LiveData<Resource<Object>> resourceLiveData) {
        resourceLiveData.observe(getViewLifecycleOwner(), resource -> {
            if (resource == null) return;
            switch (resource.status) {
                case SUCCESS:
                case LOADING:
                    break;
                case ERROR:
                    if (resource.message != null) {
                        Snackbar.make(binding.getRoot(), resource.message, Snackbar.LENGTH_LONG).show();
                    }
                    break;
            }
        });
    }

    private void observeApprovalChange(@NonNull final LiveData<Resource<Object>> detailsChangeResourceLiveData,
                                       final int position,
                                       @NonNull final PendingUser pendingUser) {
        detailsChangeResourceLiveData.observe(getViewLifecycleOwner(), resource -> {
            if (resource == null) return;
            switch (resource.status) {
                case SUCCESS:
                    // pending user will be removed from the list, so no need to set the progress to false
                    // pendingUser.setInProgress(false);
                    break;
                case LOADING:
                    pendingUser.setInProgress(true);
                    break;
                case ERROR:
                    pendingUser.setInProgress(false);
                    if (resource.message != null) {
                        Snackbar.make(binding.getRoot(), resource.message, Snackbar.LENGTH_LONG).show();
                    }
                    break;
            }
            pendingUsersAdapter.notifyItemChanged(position);
        });
    }

    @Override
    public void onPositiveButtonClicked(final int requestCode) {
        if (requestCode == APPROVAL_REQUIRED_REQUEST_CODE && approvalRequiredUsers != null) {
            final LiveData<Resource<Object>> detailsChangeResourceLiveData = viewModel.addMembers(approvalRequiredUsers);
            observeDetailsChange(detailsChangeResourceLiveData);
            return;
        }
        if (requestCode == LEAVE_THREAD_REQUEST_CODE) {
            final LiveData<Resource<Object>> resourceLiveData = viewModel.leave();
            resourceLiveData.observe(getViewLifecycleOwner(), resource -> {
                if (resource == null) return;
                switch (resource.status) {
                    case SUCCESS:
                        final NavDirections directions = DirectMessageSettingsFragmentDirections.actionSettingsToInbox();
                        NavHostFragment.findNavController(this).navigate(directions);
                        break;
                    case ERROR:
                        binding.leave.setEnabled(true);
                        if (resource.message != null) {
                            Snackbar.make(binding.getRoot(), resource.message, Snackbar.LENGTH_LONG).show();
                        }
                        break;
                    case LOADING:
                        binding.leave.setEnabled(false);
                        break;
                }
            });
        }
    }

    @Override
    public void onNegativeButtonClicked(final int requestCode) {
        if (requestCode == APPROVAL_REQUIRED_REQUEST_CODE) {
            approvalRequiredUsers = null;
        }
    }

    @Override
    public void onNeutralButtonClicked(final int requestCode) {}
}
