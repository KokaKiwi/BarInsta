package awais.instagrabber.fragments

import android.annotation.SuppressLint
import android.content.DialogInterface.OnClickListener
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import awais.instagrabber.BuildConfig
import awais.instagrabber.R
import awais.instagrabber.adapters.StoriesAdapter
import awais.instagrabber.customviews.helpers.SwipeGestureListener
import awais.instagrabber.databinding.FragmentStoryViewerBinding
import awais.instagrabber.fragments.settings.PreferenceKeys
import awais.instagrabber.interfaces.SwipeEvent
import awais.instagrabber.models.Resource
import awais.instagrabber.models.enums.FavoriteType
import awais.instagrabber.models.enums.MediaItemType
import awais.instagrabber.models.enums.StoryPaginationType
import awais.instagrabber.repositories.requests.StoryViewerOptions
import awais.instagrabber.repositories.responses.directmessages.RankedRecipient
import awais.instagrabber.repositories.responses.stories.*
import awais.instagrabber.utils.DownloadUtils.download
import awais.instagrabber.utils.ResponseBodyUtils
import awais.instagrabber.utils.Utils
import awais.instagrabber.utils.extensions.TAG
import awais.instagrabber.viewmodels.ArchivesViewModel
import awais.instagrabber.viewmodels.FeedStoriesViewModel
import awais.instagrabber.viewmodels.StoryFragmentViewModel
import awais.instagrabber.webservices.MediaRepository
import awais.instagrabber.webservices.StoriesRepository
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.interfaces.DraweeController
import com.facebook.imagepipeline.image.ImageInfo
import com.facebook.imagepipeline.request.ImageRequestBuilder
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.material.textfield.TextInputEditText
import java.io.IOException
import java.text.NumberFormat
import java.util.*


class StoryViewerFragment : Fragment() {
    private val TAG = "StoryViewerFragment"

    private var root: View? = null
    private var currentStoryUsername: String? = null
    private var storiesAdapter: StoriesAdapter? = null
    private var swipeEvent: SwipeEvent? = null
    private var gestureDetector: GestureDetectorCompat? = null
    private val storiesRepository: StoriesRepository? = null
    private val mediaRepository: MediaRepository? = null
    private var menuProfile: MenuItem? = null
    private var profileVisible: Boolean = false
    private var player: SimpleExoPlayer? = null

    private var shouldRefresh = true
    private var currentFeedStoryIndex = 0
    private var sliderValue = 0.0
    private var options: StoryViewerOptions? = null
    private var listViewModel: ViewModel? = null
    private var backStackSavedStateResultLiveData: MutableLiveData<Any?>? = null
    private lateinit var fragmentActivity: AppCompatActivity
    private lateinit var storiesViewModel: StoryFragmentViewModel
    private lateinit var binding: FragmentStoryViewerBinding

    @Suppress("UNCHECKED_CAST")
    private val backStackSavedStateObserver = Observer<Any?> { result ->
        if (result == null) return@Observer
        if ((result is RankedRecipient)) {
            if (context != null) {
                Toast.makeText(context, R.string.sending, Toast.LENGTH_SHORT).show()
            }
            storiesViewModel.shareDm(result)
        } else if ((result is Set<*>)) {
            try {
                if (context != null) {
                    Toast.makeText(context, R.string.sending, Toast.LENGTH_SHORT).show()
                }
                storiesViewModel.shareDm(result as Set<RankedRecipient>)
            } catch (e: Exception) {
                Log.e(TAG, "share: ", e)
            }
        }
        // clear result
        backStackSavedStateResultLiveData?.postValue(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentActivity = requireActivity() as AppCompatActivity
        storiesViewModel = ViewModelProvider(this).get(StoryFragmentViewModel::class.java)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        if (root != null) {
            shouldRefresh = false
            return root
        }
        binding = FragmentStoryViewerBinding.inflate(inflater, container, false)
        root = binding.root
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!shouldRefresh) return
        init()
        shouldRefresh = false
    }

    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.story_menu, menu)
        menuProfile = menu.findItem(R.id.action_profile)
        menuProfile!!.isVisible = profileVisible
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.action_profile) {
            val username = storiesViewModel.getCurrentStory().value?.user?.username
            openProfile(Pair(username, FavoriteType.USER))
            return true
        }
        return false
    }

    override fun onPause() {
        super.onPause()
        player?.pause() ?: return
    }

    override fun onResume() {
        super.onResume()
        setHasOptionsMenu(true)
        try {
            val backStackEntry = NavHostFragment.findNavController(this).currentBackStackEntry
            if (backStackEntry != null) {
                backStackSavedStateResultLiveData = backStackEntry.savedStateHandle.getLiveData("result")
                backStackSavedStateResultLiveData?.observe(viewLifecycleOwner, backStackSavedStateObserver)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onResume: ", e)
        }
        val actionBar = fragmentActivity.supportActionBar ?: return
        actionBar.title = storiesViewModel.getTitle().value
        actionBar.subtitle = storiesViewModel.getDate().value
    }

    override fun onDestroy() {
        releasePlayer()
        val actionBar = fragmentActivity.supportActionBar
        actionBar?.subtitle = null
        super.onDestroy()
    }

    private fun init() {
        val args = arguments ?: return
        val fragmentArgs = StoryViewerFragmentArgs.fromBundle(args)
        options = fragmentArgs.options
        currentFeedStoryIndex = options!!.currentFeedStoryIndex
        val type = options!!.type
        if (currentFeedStoryIndex >= 0) {
            listViewModel = when (type) {
                StoryViewerOptions.Type.STORY_ARCHIVE ->
                    ViewModelProvider(fragmentActivity).get(ArchivesViewModel::class.java)
                StoryViewerOptions.Type.FEED_STORY_POSITION ->
                    ViewModelProvider(fragmentActivity).get(FeedStoriesViewModel::class.java)
                else -> null
            }
        }
        setupButtons()
        setupStories()
    }

    private fun setupStories() {
        setupListeners()
        val context = context ?: return
        binding.storiesList.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        storiesAdapter = StoriesAdapter { _, position ->
            storiesViewModel.setMedia(position)
        }
        binding.storiesList.adapter = storiesAdapter
        storiesViewModel.getCurrentStory().observe(fragmentActivity, {
            if (it?.items != null && it.items.size > 1) {
                val storyMedias = it.items.toMutableList()
                val newItem = storyMedias.get(0)
                newItem.isCurrentSlide = true
                storyMedias.set(0, newItem)
                storiesAdapter!!.submitList(storyMedias)
                storiesViewModel.setMedia(0)
                binding.listToggle.isEnabled = true
                binding.storiesList.visibility = if (Utils.settingsHelper.getBoolean(PreferenceKeys.PREF_STORY_SHOW_LIST)) View.VISIBLE
                else View.GONE
            }
            else {
                if (it?.items != null) storiesViewModel.setMedia(0)
                binding.listToggle.isEnabled = false
                binding.storiesList.visibility = View.GONE
            }
        })
        storiesViewModel.getDate().observe(fragmentActivity, {
            val actionBar = fragmentActivity.supportActionBar
            if (actionBar != null && it != null) actionBar.subtitle = it
        })
        storiesViewModel.getTitle().observe(fragmentActivity, {
            val actionBar = fragmentActivity.supportActionBar
            if (actionBar != null && it != null) actionBar.title = it
        })
        storiesViewModel.getCurrentMedia().observe(fragmentActivity, { refreshStory(it) })
        storiesViewModel.getCurrentIndex().observe(fragmentActivity, {
            storiesAdapter!!.paginate(it)
        })
        storiesViewModel.getOptions().observe(fragmentActivity, {
            binding.stickers.isEnabled = it.first.size > 0
        })
    }

    private fun setupButtons() {
        binding.btnDownload.setOnClickListener({ _ -> downloadStory() })
        binding.btnForward.setOnClickListener({ _ -> storiesViewModel.skip(false) })
        binding.btnBackward.setOnClickListener({ _ -> storiesViewModel.skip(true) })
        binding.btnShare.setOnClickListener({ _ -> shareStoryViaDm() })
        binding.btnReply.setOnClickListener({ _ -> createReplyDialog(null) })
        binding.stickers.setOnClickListener({ _ -> showStickerMenu() })
        binding.listToggle.setOnClickListener({ _ ->
            binding.storiesList.visibility = if (binding.storiesList.visibility == View.GONE) View.VISIBLE
            else View.GONE
        })

        TooltipCompat.setTooltipText(binding.btnDownload, getString(R.string.action_download))
        TooltipCompat.setTooltipText(binding.btnShare, getString(R.string.share))
        TooltipCompat.setTooltipText(binding.btnReply, getString(R.string.reply_story))
        TooltipCompat.setTooltipText(binding.stickers, getString(R.string.story_stickers))
        TooltipCompat.setTooltipText(binding.listToggle, getString(R.string.story_list))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        if (currentFeedStoryIndex >= 0) {
            val type = options!!.type
            when (type) {
                StoryViewerOptions.Type.HIGHLIGHT -> {
                    storiesViewModel.fetchHighlights(options!!.id)
                    storiesViewModel.highlights.observe(fragmentActivity) {
                        setupMultipage(it)
                    }
                }
                StoryViewerOptions.Type.FEED_STORY_POSITION -> {
                    val feedStoriesViewModel = listViewModel as FeedStoriesViewModel?
                    setupMultipage(feedStoriesViewModel!!.list.value)
                }
                StoryViewerOptions.Type.STORY_ARCHIVE -> {
                    val archivesViewModel = listViewModel as ArchivesViewModel?
                    setupMultipage(archivesViewModel!!.list.value)
                }
                StoryViewerOptions.Type.USER -> {
                    resetView()
                }
            }
        }

        val context = context ?: return
        swipeEvent = SwipeEvent { isRightSwipe: Boolean ->
            storiesViewModel.paginate(isRightSwipe)
        }
        gestureDetector = GestureDetectorCompat(context, SwipeGestureListener(swipeEvent))
        binding.playerView.setOnTouchListener { _, event -> gestureDetector!!.onTouchEvent(event) }
        val simpleOnGestureListener: SimpleOnGestureListener = object : SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val diffX = e2.x - e1.x
                try {
                    if (Math.abs(diffX) > Math.abs(e2.y - e1.y) && Math.abs(diffX) > SwipeGestureListener.SWIPE_THRESHOLD && Math.abs(
                            velocityX
                        ) > SwipeGestureListener.SWIPE_VELOCITY_THRESHOLD
                    ) {
                        storiesViewModel.paginate(diffX > 0)
                        return true
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Error", e)
                }
                return false
            }
        }
        binding.imageViewer.setTapListener(simpleOnGestureListener)
    }

    private fun setupMultipage(models: List<Story>?) {
        if (models == null) return
        storiesViewModel.getPagination().observe(fragmentActivity, {
            when (it) {
                StoryPaginationType.FORWARD -> {
                    if (currentFeedStoryIndex == models.size - 1)
                        Toast.makeText(
                            context,
                            R.string.no_more_stories,
                            Toast.LENGTH_SHORT
                        ).show()
                    else paginateStories(false, currentFeedStoryIndex == models.size - 2)
                }
                StoryPaginationType.BACKWARD -> {
                    if (currentFeedStoryIndex == 0)
                        Toast.makeText(
                            context,
                            R.string.no_more_stories,
                            Toast.LENGTH_SHORT
                        ).show()
                    else paginateStories(true, false)
                }
                StoryPaginationType.ERROR -> {
                    Toast.makeText(
                        context,
                        R.string.downloader_unknown_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        if (!models.isEmpty()) {
            binding.btnBackward.isEnabled = currentFeedStoryIndex != 0
            binding.btnForward.isEnabled = currentFeedStoryIndex != models.size - 1
            resetView()
        }
    }

    private fun resetView() {
        val context = context ?: return
        if (menuProfile != null) menuProfile!!.isVisible = false
        binding.imageViewer.controller = null
        releasePlayer()
        val type = options!!.type
        var fetchOptions: StoryViewerOptions? = null
        when (type) {
            StoryViewerOptions.Type.HIGHLIGHT -> {
                val models = storiesViewModel.highlights.value
                if (models == null || models.isEmpty() || currentFeedStoryIndex >= models.size || currentFeedStoryIndex < 0) {
                    Toast.makeText(context, R.string.downloader_unknown_error, Toast.LENGTH_SHORT).show()
                    return
                }
                fetchOptions = StoryViewerOptions.forHighlight(0L, models[currentFeedStoryIndex].id)
            }
            StoryViewerOptions.Type.FEED_STORY_POSITION -> {
                val feedStoriesViewModel = listViewModel as FeedStoriesViewModel?
                val models = feedStoriesViewModel!!.list.value
                if (models == null || currentFeedStoryIndex >= models.size || currentFeedStoryIndex < 0) return
                val userStory = models[currentFeedStoryIndex]
                currentStoryUsername = userStory.user!!.username
                fetchOptions = StoryViewerOptions.forUser(userStory.user.pk, currentStoryUsername)
                val live = userStory.broadcast
                if (live != null) {
                    storiesViewModel.setStory(userStory)
                    refreshLive(live)
                    return
                }
            }
            StoryViewerOptions.Type.STORY_ARCHIVE -> {
                val archivesViewModel = listViewModel as ArchivesViewModel?
                val models = archivesViewModel!!.list.value
                if (models == null || models.isEmpty() || currentFeedStoryIndex >= models.size || currentFeedStoryIndex < 0) {
                    Toast.makeText(context, R.string.downloader_unknown_error, Toast.LENGTH_SHORT)
                        .show()
                    return
                }
                val (id, _, _, _, _, _, _, _, _, title) = models[currentFeedStoryIndex]
                currentStoryUsername = title
                fetchOptions = StoryViewerOptions.forStoryArchive(id)
            }
            StoryViewerOptions.Type.USER -> {
                currentStoryUsername = options!!.name
                fetchOptions = StoryViewerOptions.forUser(options!!.id, currentStoryUsername)
            }
        }
        if (type == StoryViewerOptions.Type.STORY) {
            storiesViewModel.fetchSingleMedia(options!!.id)
            return
        }
        storiesViewModel.fetchStory(fetchOptions).observe(viewLifecycleOwner, {
            if (it.status == Resource.Status.ERROR) {
                Toast.makeText(context, "Error: " + it.message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    @Synchronized
    private fun refreshLive(live: Broadcast) {
        binding.btnDownload.isEnabled = false
        binding.stickers.isEnabled = false
        binding.listToggle.isEnabled = false
        binding.btnShare.isEnabled = false
        binding.btnReply.isEnabled = false
        releasePlayer()
        setupLive(live.dashPlaybackUrl ?: live.dashAbrPlaybackUrl ?: return)
    }

    @Synchronized
    private fun refreshStory(currentStory: StoryMedia) {
        val itemType = currentStory.type
        val url = if (itemType === MediaItemType.MEDIA_TYPE_IMAGE) ResponseBodyUtils.getImageUrl(currentStory)
                  else ResponseBodyUtils.getVideoUrl(currentStory)

        releasePlayer()

        profileVisible = currentStory.user?.username != null
        if (menuProfile != null) menuProfile!!.isVisible = profileVisible

        binding.btnDownload.isEnabled = false
        binding.btnShare.isEnabled = currentStory.canReshare
        binding.btnReply.isEnabled = currentStory.canReply
        if (itemType === MediaItemType.MEDIA_TYPE_VIDEO) setupVideo(url) else setupImage(url)

        if (options!!.type == StoryViewerOptions.Type.FEED_STORY_POSITION
            && Utils.settingsHelper.getBoolean(PreferenceKeys.MARK_AS_SEEN)) {
                val feedStoriesViewModel = listViewModel as FeedStoriesViewModel?
                storiesViewModel.markAsSeen(currentStory).observe(viewLifecycleOwner) { m ->
                    if (m.status == Resource.Status.SUCCESS && m.data != null) {
                        val liveModels: MutableLiveData<List<Story>> = feedStoriesViewModel!!.list
                        val models = liveModels.value
                        val modelsCopy: MutableList<Story> = models!!.toMutableList()
                        modelsCopy.set(currentFeedStoryIndex, m.data)
                        liveModels.postValue(modelsCopy)
                    }
                }
        }
    }

    private fun downloadStory() {
        val context = context ?: return
        val currentStory = storiesViewModel.getMedia().value
        if (currentStory == null) {
            Toast.makeText(context, R.string.downloader_unknown_error, Toast.LENGTH_SHORT).show()
            return
        }
        download(context, currentStory)
    }

    private fun setupImage(url: String) {
        binding.progressView.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE
        binding.imageViewer.visibility = View.VISIBLE
        val requestBuilder = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url))
            .setLocalThumbnailPreviewsEnabled(true)
            .setProgressiveRenderingEnabled(true)
            .build()
        val controller: DraweeController = Fresco.newDraweeControllerBuilder()
            .setImageRequest(requestBuilder)
            .setOldController(binding.imageViewer.controller)
            .setControllerListener(object : BaseControllerListener<ImageInfo?>() {
                override fun onFailure(id: String, throwable: Throwable) {
                    binding.btnDownload.isEnabled = false
                    binding.progressView.visibility = View.GONE
                }

                override fun onFinalImageSet(
                    id: String,
                    imageInfo: ImageInfo?,
                    animatable: Animatable?
                ) {
                    binding.btnDownload.isEnabled = true
                    binding.progressView.visibility = View.GONE
                }
            })
            .build()
        binding.imageViewer.controller = controller
    }

    private fun setupVideo(url: String) {
        binding.playerView.visibility = View.VISIBLE
        binding.progressView.visibility = View.GONE
        binding.imageViewer.visibility = View.GONE
        binding.imageViewer.controller = null
        val context = context ?: return
        player = SimpleExoPlayer.Builder(context).build()
        binding.playerView.player = player
        player!!.playWhenReady =
            Utils.settingsHelper.getBoolean(PreferenceKeys.AUTOPLAY_VIDEOS_STORIES)
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)
        val mediaSource =
            ProgressiveMediaSource.Factory(DefaultDataSourceFactory(context, "instagram"))
                .createMediaSource(mediaItem)
        mediaSource.addEventListener(Handler(), object : MediaSourceEventListener {
            override fun onLoadCompleted(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                binding.btnDownload.isEnabled = true
                binding.progressView.visibility = View.GONE
            }

            override fun onLoadStarted(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                binding.btnDownload.isEnabled = true
                binding.progressView.visibility = View.VISIBLE
            }

            override fun onLoadCanceled(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                binding.progressView.visibility = View.GONE
            }

            override fun onLoadError(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                binding.btnDownload.isEnabled = false
                binding.progressView.visibility = View.GONE
            }
        })
        player!!.setMediaSource(mediaSource)
        player!!.prepare()
        binding.playerView.setOnClickListener { _ ->
            if (player != null) {
                if (player!!.playbackState == Player.STATE_ENDED) player!!.seekTo(0)
                player!!.playWhenReady =
                    player!!.playbackState == Player.STATE_ENDED || !player!!.isPlaying
            }
        }
    }

    private fun setupLive(url: String) {
        binding.playerView.visibility = View.VISIBLE
        binding.progressView.visibility = View.GONE
        binding.imageViewer.visibility = View.GONE
        binding.imageViewer.controller = null
        val context = context ?: return
        player = SimpleExoPlayer.Builder(context).build()
        binding.playerView.player = player
        player!!.playWhenReady =
            Utils.settingsHelper.getBoolean(PreferenceKeys.AUTOPLAY_VIDEOS_STORIES)
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)
        val mediaSource = DashMediaSource.Factory(DefaultDataSourceFactory(context, "instagram"))
            .createMediaSource(mediaItem)
        mediaSource.addEventListener(Handler(), object : MediaSourceEventListener {
            override fun onLoadCompleted(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                binding.progressView.visibility = View.GONE
            }

            override fun onLoadStarted(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                binding.progressView.visibility = View.VISIBLE
            }

            override fun onLoadCanceled(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                binding.progressView.visibility = View.GONE
            }

            override fun onLoadError(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                binding.progressView.visibility = View.GONE
            }
        })
        player!!.setMediaSource(mediaSource)
        player!!.prepare()
        binding.playerView.setOnClickListener { _ ->
            if (player != null) {
                if (player!!.playbackState == Player.STATE_ENDED) player!!.seekTo(0)
                player!!.playWhenReady =
                    player!!.playbackState == Player.STATE_ENDED || !player!!.isPlaying
            }
        }
    }

    private fun openProfile(data: Pair<String?, FavoriteType>) {
        val navController: NavController = NavHostFragment.findNavController(this)
        val bundle = Bundle()
        if (data.first == null) {
            // toast
            return
        }
        val actionBar = fragmentActivity.supportActionBar
        if (actionBar != null) {
            actionBar.title = null
            actionBar.subtitle = null
        }
        val action = when (data.second) {
            FavoriteType.USER -> {
                StoryViewerFragmentDirections.actionToProfile().apply { this.username = data.first!! }
            }
            FavoriteType.HASHTAG -> {
                StoryViewerFragmentDirections.actionToHashtag(data.first!!)
            }
            FavoriteType.LOCATION -> {
                StoryViewerFragmentDirections.actionToLocation(data.first!!.toLong())
            }
            else -> null
        }
        navController.navigate(action!!)
    }

    private fun releasePlayer() {
        if (player == null) return
        try {
            player!!.stop(true)
        } catch (ignored: Exception) {
        }
        try {
            player!!.release()
        } catch (ignored: Exception) {
        }
        player = null
    }

    private fun paginateStories(
        backward: Boolean,
        last: Boolean
    ) {
        binding.btnBackward.isEnabled = currentFeedStoryIndex != 1 || !backward
        binding.btnForward.isEnabled = !last
        currentFeedStoryIndex = if (backward) currentFeedStoryIndex - 1 else currentFeedStoryIndex + 1
        resetView()
    }

    private fun createChoiceDialog(
        title: String?,
        tallies: List<Tally>,
        onClickListener: OnClickListener,
        viewerVote: Int?,
        correctAnswer: Int?
    ) {
        val context = context ?: return
        val choices = tallies.map {
            (if (viewerVote == tallies.indexOf(it)) "√ " else "") +
            (if (correctAnswer == tallies.indexOf(it)) "*** " else "") +
            it.text + " (" + it.count + ")" }
        val builder = AlertDialog.Builder(context)
        if (viewerVote != null) builder.setTitle(R.string.story_quizzed)
        else if (title != null) builder.setTitle(title)
        builder.setPositiveButton(if (viewerVote == null) R.string.cancel else R.string.ok, null)
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, choices.toTypedArray())
        builder.setAdapter(adapter, onClickListener)
        builder.show()
    }

    private fun createMentionDialog() {
        val context = context ?: return
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, storiesViewModel.getMentionTexts())
        val builder = AlertDialog.Builder(context)
            .setPositiveButton(R.string.ok, null)
            .setAdapter(adapter, { _, w ->
                val data = storiesViewModel.getMention(w)
                if (data != null) openProfile(Pair(data.second, data.third))
            })
        builder.show()
    }

    private fun createSliderDialog() {
        val slider = storiesViewModel.getSlider().value ?: return
        val context = context ?: return
        val percentage: NumberFormat = NumberFormat.getPercentInstance()
        percentage.maximumFractionDigits = 2
        val sliderView = LinearLayout(context)
        sliderView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        sliderView.orientation = LinearLayout.VERTICAL
        val tv = TextView(context)
        tv.gravity = Gravity.CENTER_HORIZONTAL
        val input = SeekBar(context)
        val avg: Double = slider.sliderVoteAverage ?: 0.5
        input.progress = (avg * 100).toInt()
        var onClickListener: OnClickListener? = null

        if (slider.viewerVote == null && slider.viewerCanVote == true) {
            input.isEnabled = true
            input.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    sliderValue = progress / 100.0
                    tv.text = percentage.format(sliderValue)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            onClickListener = OnClickListener { _, _ -> storiesViewModel.answerSlider(sliderValue) }
        }
        else {
            input.isEnabled = false
            tv.text = getString(R.string.slider_answer, percentage.format(slider.viewerVote))
        }
        sliderView.addView(input)
        sliderView.addView(tv)
        val builder = AlertDialog.Builder(context)
            .setTitle(if (slider.question.isNullOrEmpty()) slider.emoji else slider.question)
            .setMessage(
                resources.getQuantityString(R.plurals.slider_info,
                slider.sliderVoteCount ?: 0,
                slider.sliderVoteCount ?: 0,
                percentage.format(avg)))
            .setView(sliderView)
            .setPositiveButton(R.string.ok, onClickListener)

        builder.show()
    }

    private fun createReplyDialog(question: String?) {
        val context = context ?: return
        val input = TextInputEditText(context)
        input.setHint(R.string.reply_hint)
        val builder = AlertDialog.Builder(context)
            .setTitle(question ?: context.getString(R.string.reply_story))
            .setView(input)
        val onClickListener = OnClickListener{ _, _ ->
            val result =
                if (question != null) storiesViewModel.answerQuestion(input.text.toString())
                else storiesViewModel.reply(input.text.toString())
            if (result == null) {
                Toast.makeText(context, R.string.downloader_unknown_error, Toast.LENGTH_SHORT)
                    .show()
            }
            else result.observe(viewLifecycleOwner, {
                when (it.status) {
                    Resource.Status.SUCCESS -> {
                        Toast.makeText(context, R.string.answered_story, Toast.LENGTH_SHORT)
                            .show()
                    }
                    Resource.Status.ERROR -> {
                        Toast.makeText(context, "Error: " + it.message, Toast.LENGTH_SHORT)
                            .show()
                    }
                    Resource.Status.LOADING -> {
                        Toast.makeText(context, R.string.sending, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        builder.setPositiveButton(R.string.confirm, onClickListener)
        builder.show()
    }

    private fun shareStoryViaDm() {
        val story = storiesViewModel.getCurrentStory().value ?: return
        val context = context
        if (story.user?.isPrivate == true && context != null) {
            Toast.makeText(context, R.string.share_private_post, Toast.LENGTH_SHORT).show()
        }
        val actionBar = fragmentActivity.supportActionBar
        if (actionBar != null) actionBar.subtitle = null
        val actionGlobalUserSearch = StoryViewerFragmentDirections.actionToUserSearch().apply {
            title = getString(R.string.share)
            actionLabel = getString(R.string.send)
            showGroups = true
            multiple = true
            searchMode = UserSearchMode.RAVEN
        }
        try {
            val navController = NavHostFragment.findNavController(this@StoryViewerFragment)
            navController.navigate(actionGlobalUserSearch)
        } catch (e: Exception) {
            Log.e(TAG, "shareStoryViaDm: ", e)
        }
    }

    private fun showStickerMenu() {
        val data = storiesViewModel.getOptions().value
        if (data == null) return
        val themeWrapper = ContextThemeWrapper(context, R.style.popupMenuStyle)
        val popupMenu = PopupMenu(themeWrapper, binding.stickers)
        val menu = popupMenu.menu
        data.first.map {
            if (it.second != 0) menu.add(0, it.first, 0, it.second)
            if (it.first == R.id.swipeUp) menu.add(0, R.id.swipeUp, 0, data.second)
            if (it.first == R.id.spotify) menu.add(0, R.id.spotify, 0, data.third)
        }
        popupMenu.setOnMenuItemClickListener { item: MenuItem ->
            val itemId = item.itemId
            if (itemId == R.id.spotify) openExternalLink(storiesViewModel.getAppAttribution())
            else if (itemId == R.id.swipeUp) openExternalLink(storiesViewModel.getSwipeUp())
            else if (itemId == R.id.mentions) createMentionDialog()
            else if (itemId == R.id.slider) createSliderDialog()
            else if (itemId == R.id.question) {
                val question = storiesViewModel.getQuestion().value
                if (question != null) createReplyDialog(question.question)
            }
            else if (itemId == R.id.quiz) {
                val quiz = storiesViewModel.getQuiz().value
                if (quiz != null) createChoiceDialog(
                    quiz.question,
                    quiz.tallies,
                    { _, w -> storiesViewModel.answerQuiz(w) },
                    quiz.viewerAnswer,
                    quiz.correctAnswer
                )
            }
            else if (itemId == R.id.poll) {
                val poll = storiesViewModel.getPoll().value
                if (poll != null) createChoiceDialog(
                    poll.question,
                    poll.tallies,
                    { _, w -> storiesViewModel.answerPoll(w) },
                    poll.viewerVote,
                    null
                )
            }
            else if (itemId == R.id.viewStoryPost) {
                storiesViewModel.getLinkedPost().observe(viewLifecycleOwner, {
                    if (it == null) Toast.makeText(context, "Error: LiveData is null", Toast.LENGTH_SHORT).show()
                    else when (it.status) {
                        Resource.Status.SUCCESS -> {
                            if (it.data != null) {
                                val actionBar = fragmentActivity.supportActionBar
                                if (actionBar != null) {
                                    actionBar.title = null
                                    actionBar.subtitle = null
                                }
                                val navController =
                                    NavHostFragment.findNavController(this@StoryViewerFragment)
                                val bundle = Bundle()
                                bundle.putSerializable(PostViewV2Fragment.ARG_MEDIA, it.data)
                                try {
                                    navController.navigate(StoryViewerFragmentDirections.actionToPost(it.data, 0))
                                } catch (e: Exception) {
                                    Log.e(TAG, "openPostDialog: ", e)
                                }
                            }
                        }
                        Resource.Status.ERROR -> {
                            Toast.makeText(context, "Error: " + it.message, Toast.LENGTH_SHORT)
                                .show()
                        }
                        Resource.Status.LOADING -> {
                            Toast.makeText(context, R.string.opening_post, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                })
            }
            false
        }
        popupMenu.show()
    }

    private fun openExternalLink(url: String?) {
        val context = context ?: return
        if (url == null) return
        AlertDialog.Builder(context)
            .setTitle(R.string.swipe_up_confirmation)
            .setMessage(url).setPositiveButton(R.string.yes, { _, _ -> Utils.openURL(context, url) })
            .setNegativeButton(R.string.no, null)
            .show()
    }
}