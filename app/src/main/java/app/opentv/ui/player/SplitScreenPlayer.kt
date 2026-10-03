/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.PipState
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.player.PlayerController
import app.opentv.ui.channels.GuideDirectionRepeatGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Two in-app pictures. The existing live session stays the primary; only one extra player exists. */
@OptIn(UnstableApi::class)
@Composable
internal fun SplitScreenPlayer(
    primary: PlayerController,
    primaryChannelId: Long?,
    onExit: () -> Unit,
    onGuide: () -> Unit,
) {
    val graph = ServiceLocator.get(LocalContext.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var audioPane by remember { mutableIntStateOf(0) }
    var expandedPane by remember { mutableStateOf<Int?>(null) }
    var secondaryChannel by remember { mutableStateOf<Channel?>(null) }
    var primaryChannel by remember { mutableStateOf<Channel?>(null) }
    var picker by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    val primaryState by primary.state.collectAsState()
    val audioRouter = remember(primary) { SplitScreenAudioRouter { primary.player.volume = it } }
    val primaryTitle = when (val value = primaryState) {
        is PlayerController.State.Playing -> value.title
        is PlayerController.State.Buffering -> value.title
        is PlayerController.State.Error -> value.title
        else -> primaryChannel?.shownName.orEmpty()
    }

    LaunchedEffect(primaryChannelId) {
        primaryChannel = primaryChannelId?.let { graph.catalogRepository.channel(it) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val inPip by PipState.inPip.collectAsState()
    val volumes = SplitScreenPolicy.volumes(audioPane, expandedPane, secondaryChannel != null, foreground && !inPip)
    LaunchedEffect(volumes) { audioRouter.route(volumes) }
    DisposableEffect(primary, lifecycle) {
        onDispose {
            primary.player.volume = if (PipState.inPip.value ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) 0f else 1f
        }
    }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }

    fun back() {
        if (picker) picker = false
        else if (expandedPane != null) expandedPane = null
        else onGuide()
    }
    BackHandler { back() }
    Column(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.key == Key.Back || event.key == Key.Escape) {
                if (event.type == KeyEventType.KeyUp) back()
                true
            } else false
        },
    ) {
        // Keep both PlayerViews in composition when expanded so their surfaces/buffers survive.
        // The hidden pane uses zero width. It keeps streaming while split-screen is active.
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                if (expandedPane == 1) Modifier.width(0.dp).fillMaxHeight()
                else Modifier.weight(1f).fillMaxHeight(),
            ) {
                VideoPane(
                    controller = primary,
                    title = primaryTitle,
                    audible = volumes.first > 0f,
                    onFocus = { audioPane = 0 },
                    onExpand = { expandedPane = if (expandedPane == 0) null else 0 },
                    visible = expandedPane != 1,
                )
            }
            Box(
                if (expandedPane == 0) Modifier.width(0.dp).fillMaxHeight()
                else Modifier.weight(1f).fillMaxHeight(),
            ) {
                val channel = secondaryChannel
                if (channel == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { picker = true }) { Text(stringResource(R.string.split_choose_second)) }
                    }
                } else if (foreground) {
                    SecondaryPane(
                        channel = channel,
                        audioRouter = audioRouter,
                        audible = volumes.second > 0f,
                        visible = expandedPane != 0,
                        onFocus = { audioPane = 1 },
                        onExpand = { expandedPane = if (expandedPane == 1) null else 1 },
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { picker = true }, modifier = Modifier.focusRequester(initialFocus)) {
                    Text(stringResource(if (secondaryChannel == null) R.string.split_choose_second else R.string.split_change_second))
                }
                Button(onClick = { audioPane = 0; expandedPane = null }) {
                    Text(stringResource(R.string.split_left_audio))
                }
                Button(
                    onClick = { audioPane = 1; expandedPane = null },
                    enabled = secondaryChannel != null,
                ) { Text(stringResource(R.string.split_right_audio)) }
                Button(onClick = { expandedPane = if (expandedPane == null) audioPane else null }) {
                    Text(stringResource(if (expandedPane == null) R.string.split_expand else R.string.split_two_pictures))
                }
                Button(onClick = onExit) { Text(stringResource(R.string.split_exit)) }
            }
            Text(
                stringResource(R.string.split_connection_note),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (picker) {
        SplitChannelPicker(
            primaryChannel = primaryChannel,
            primaryChannelId = primaryChannelId,
            onDismiss = { picker = false },
            onSelect = {
                secondaryChannel = it
                expandedPane = null
                picker = false
            },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun SecondaryPane(
    channel: Channel,
    audioRouter: SplitScreenAudioRouter,
    audible: Boolean,
    visible: Boolean,
    onFocus: () -> Unit,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    // Reused on channel changes, disposed immediately on background/PiP/exit/navigation.
    val controller = remember {
        PlayerController(
            context = context,
            scope = scope,
            httpClient = graph.streamingHttpClient,
            subtitlesEnabled = false,
            dvr = false,
            multiview = true,
        ).apply { player.volume = 0f }
    }
    var resolveError by remember { mutableStateOf(false) }
    DisposableEffect(controller, audioRouter) {
        audioRouter.attachSecondary { controller.player.volume = it }
        onDispose {
            audioRouter.detachSecondary()
            controller.release()
        }
    }
    LaunchedEffect(channel.id) {
        resolveError = false
        // Stop the replaced stream before minting a Stalker URL; keep just two connections.
        controller.stop()
        try {
            val source = graph.sourceRepository.byId(channel.sourceId)
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            ensureActive()
            controller.play(
                PlayerController.Request(
                    url = url,
                    title = channel.shownName,
                    userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                    channelId = channel.id,
                ),
                debounce = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Avoid displaying exceptions that may include a signed provider URL.
            resolveError = true
        }
    }
    VideoPane(
        controller = controller,
        title = channel.shownName,
        audible = audible,
        onFocus = onFocus,
        onExpand = onExpand,
        resolveError = resolveError,
        visible = visible,
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPane(
    controller: PlayerController,
    title: String,
    audible: Boolean,
    onFocus: () -> Unit,
    onExpand: () -> Unit,
    resolveError: Boolean = false,
    visible: Boolean = true,
) {
    val state by controller.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .focusProperties { canFocus = visible }
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.DarkGray)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(enabled = visible, onClick = onExpand),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    player = controller.player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            onRelease = { it.player = null },
        )
        Text(
            if (audible) stringResource(R.string.split_audible_title, title) else title,
            Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.7f)).padding(10.dp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state is PlayerController.State.Buffering || state == PlayerController.State.Idle && !resolveError) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (state is PlayerController.State.Error || resolveError) {
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.9f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.split_playback_error), color = Color.White)
                if (!resolveError) {
                    Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                }
            }
        }
    }
}

@Composable
private fun SplitChannelPicker(
    primaryChannel: Channel?,
    primaryChannelId: Long?,
    onSelect: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    val graph = ServiceLocator.get(LocalContext.current)
    val hiddenCategories by graph.settings.hiddenCategories.collectAsState()
    val hiddenUnlocked by graph.settings.hiddenUnlocked.collectAsState()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var categoryKey by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var favouritesOnly by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    val repeatGate = remember { GuideDirectionRepeatGate() }
    LaunchedEffect(Unit) {
        try {
            val enabledSources = graph.sourceRepository.observeAll().first().filter { it.enabled }.map { it.id }.toSet()
            channels = graph.catalogRepository.observeChannels().first().filter { it.sourceId in enabledSources }
            categories = graph.catalogRepository.liveCategories().filter { it.sourceId in enabledSources }
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    val visibleCategories = remember(categories, hiddenCategories, hiddenUnlocked) {
        categories.filter {
            hiddenUnlocked || ChannelNameNormalizer.normalize(it.name).groupKey !in hiddenCategories
        }
    }
    val visibleCategoryKeys = remember(visibleCategories) { visibleCategories.map { it.sourceId to it.id }.toSet() }
    var choices by remember { mutableStateOf<List<Channel>>(emptyList()) }
    LaunchedEffect(channels, visibleCategoryKeys, categoryKey, favouritesOnly, query, primaryChannel, primaryChannelId) {
        choices = withContext(Dispatchers.Default) {
            SplitScreenPolicy.choices(channels.filter {
                (it.categoryId == null || (it.sourceId to it.categoryId) in visibleCategoryKeys) &&
                    (categoryKey == null || (it.sourceId to it.categoryId) == categoryKey) &&
                    (query.isBlank() || it.shownName.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true))
            }, primaryChannel ?: channels.firstOrNull { it.id == primaryChannelId })
                .filter { !favouritesOnly || it.favourite }
        }
    }
    // Start in the stable category rail so a TV remote can browse without opening the keyboard.
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.90f).background(MaterialTheme.colorScheme.surface)
                .padding(18.dp).onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionUp -> GuideDirectionRepeatGate.Direction.UP
                        Key.DirectionDown -> GuideDirectionRepeatGate.Direction.DOWN
                        else -> null
                    }
                    direction != null && event.type == KeyEventType.KeyDown &&
                        repeatGate.shouldConsume(direction, event.nativeKeyEvent.eventTime)
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.split_choose_second), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.split_search_channels)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.width(220.dp).fillMaxHeight()) {
                    item {
                        PickerRow(
                            stringResource(R.string.guide_all_channels),
                            categoryKey == null && !favouritesOnly,
                            focusRequester = initialFocus,
                        ) {
                            categoryKey = null; favouritesOnly = false
                        }
                    }
                    item {
                        PickerRow(stringResource(R.string.guide_favourites), favouritesOnly) {
                            categoryKey = null; favouritesOnly = true
                        }
                    }
                    itemsIndexed(visibleCategories, key = { _, it -> "${it.sourceId}:${it.id}" }) { _, category ->
                        PickerRow(category.name, categoryKey == (category.sourceId to category.id)) {
                            categoryKey = category.sourceId to category.id; favouritesOnly = false
                        }
                    }
                }
                if (!loaded && !loadError) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (choices.isEmpty()) {
                    Text(stringResource(if (loadError) R.string.split_catalogue_error else R.string.split_no_channels))
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                        itemsIndexed(choices, key = { _, channel -> channel.id }) { _, channel ->
                            PickerRow(channel.shownName, false) { onSelect(channel) }
                        }
                    }
                }
            }
            Button(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
            .onFocusChanged { focused = it.isFocused }
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick).padding(10.dp),
        color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
