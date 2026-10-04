/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
@file:kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.opentv.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.PipState
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.player.PlayerController
import app.opentv.ui.channels.GuideDirectionRepeatGate
import app.opentv.ui.channels.guideProgrammeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos

private class SplitPickerState {
    var query by mutableStateOf("")
    var categoryKey by mutableStateOf<Pair<Long, String>?>(null)
    var favouritesOnly by mutableStateOf(false)
    var focusedChannelId: Long? = null
    var selectedContext: List<Channel> = emptyList()
    val channelScroll = LazyListState()
    val categoryScroll = LazyListState()
}

/** Two in-app pictures. The existing live session stays the primary; only one extra player exists. */
@OptIn(UnstableApi::class)
@Composable
internal fun SplitScreenPlayer(
    primary: PlayerController,
    primaryChannelId: Long?,
    onExit: () -> Unit,
    onGuide: () -> Unit,
    onKeepSecondary: (Channel, PlayerController, List<Channel>) -> Unit,
    onChangePrimary: (Channel, List<Channel>) -> Unit,
) {
    val graph = ServiceLocator.get(LocalContext.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var audioPane by remember { mutableIntStateOf(0) }
    var selectedPane by remember { mutableIntStateOf(0) }
    var pendingPaneFocus by remember { mutableStateOf<Int?>(0) }
    var focusedPane by remember { mutableStateOf<Int?>(null) }
    var expandedPane by remember { mutableStateOf<Int?>(null) }
    var secondaryChannel by remember { mutableStateOf<Channel?>(null) }
    var primaryChannel by remember { mutableStateOf<Channel?>(null) }
    var picker by remember { mutableStateOf(false) }
    var pictureMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var pickerPane by remember { mutableIntStateOf(0) }
    val initialFocus = remember { FocusRequester() }
    val expandFocus = remember { FocusRequester() }
    val paneFocus = remember { listOf(FocusRequester(), FocusRequester()) }
    var fillPictures by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var requestedFocus by remember { mutableStateOf("pane") }
    val wakeGuard = remember { SplitControlsWakeGuard() }
    val pickerStates = remember { listOf(SplitPickerState(), SplitPickerState()) }
    val recordings by graph.recordingRepository.observeActive().collectAsState(initial = null)
    val mayOpenSecondary = SplitScreenPolicy.mayOpenSecondary(recordings?.size)
    val firstControlFocus = if (selectedPane == 0 || secondaryChannel != null) expandFocus else initialFocus
    var recordingBlocked by remember { mutableStateOf(false) }
    var secondaryController by remember { mutableStateOf<PlayerController?>(null) }
    val idleSecondary = remember { kotlinx.coroutines.flow.MutableStateFlow<PlayerController.State>(PlayerController.State.Idle) }
    val secondaryState by (secondaryController?.state ?: idleSecondary).collectAsState()
    var secondaryTransferred by remember { mutableStateOf(false) }
    var primaryRetry by remember { mutableIntStateOf(0) }
    var primaryResolveError by remember { mutableStateOf(false) }
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
    val volumes = SplitScreenPolicy.volumes(audioPane, null, secondaryChannel != null, foreground && !inPip)
    LaunchedEffect(volumes) { audioRouter.route(volumes) }
    DisposableEffect(primary, lifecycle) {
        primary.setMultiviewActive(true)
        onDispose {
            audioRouter.close()
            if (graph.livePlaybackSession.controller === primary) {
                primary.setMultiviewActive(false)
                primary.player.volume = if (PipState.inPip.value ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) 0f else 1f
            }
        }
    }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(picker, pictureMenu, audioMenu, placed, controlsVisible, requestedFocus, expandedPane, selectedPane, pendingPaneFocus) {
        if (!picker && !pictureMenu && !audioMenu) {
            withFrameNanos { }
            val target = if (!controlsVisible || requestedFocus == "pane") paneFocus[pendingPaneFocus ?: expandedPane ?: selectedPane]
                else if (requestedFocus == "choose") firstControlFocus else initialFocus
            if (!controlsVisible || placed) {
                runCatching { target.requestFocus() }
                // An already-focused pane emits no new focus-change callback.
                if (pendingPaneFocus != null && focusedPane == pendingPaneFocus) pendingPaneFocus = null
            }
        }
    }
    LaunchedEffect(controlsVisible, interaction, picker, pictureMenu, audioMenu, secondaryChannel) {
        if (controlsVisible && !picker && !pictureMenu && !audioMenu && secondaryChannel != null) {
            delay(SplitScreenPolicy.CONTROLS_TIMEOUT_MS)
            // Move focus before removing the focused toolbar; never leave focus on a removed node.
            runCatching { paneFocus[expandedPane ?: selectedPane].requestFocus() }
            controlsVisible = false
        }
    }
    LaunchedEffect(mayOpenSecondary) {
        if (recordings?.isNotEmpty() == true) {
            secondaryChannel = null
            audioPane = 0
            selectedPane = 0
            pendingPaneFocus = null
            expandedPane = null
            recordingBlocked = true
            picker = false
            pictureMenu = false
            audioMenu = false
            controlsVisible = true
            requestedFocus = "exit"
        }
    }
    LaunchedEffect(primaryRetry) {
        if (primaryRetry == 0) return@LaunchedEffect
        primaryResolveError = false
        primary.stop()
        try {
            val channel = primaryChannelId?.let { graph.catalogRepository.channel(it) }
                ?: error("Missing channel")
            val source = graph.sourceRepository.byId(channel.sourceId) ?: error("Missing source")
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            ensureActive()
            primary.play(PlayerController.Request(url, channel.shownName, source.userAgent ?: "OpenTV/0.1 (Android)", channelId = channel.id), debounce = false)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { primaryResolveError = true }
    }

    fun back() {
        if (pictureMenu || audioMenu) { pendingPaneFocus = selectedPane; pictureMenu = false; audioMenu = false; requestedFocus = "pane" }
        else if (picker) { pendingPaneFocus = selectedPane; picker = false; controlsVisible = true; requestedFocus = "pane" }
        else if (expandedPane != null) { expandedPane = null; controlsVisible = false }
        else onGuide()
    }
    fun openPicker() {
        pickerPane = selectedPane
        pictureMenu = false
        picker = true; recordingBlocked = false; requestedFocus = "pane"
    }
    fun selectPane(pane: Int) {
        // Dialog dismissal may briefly restore the OLD focus owner. Do not let it steal
        // picture selection/audio before the requested pane has accepted focus.
        if (picker || pictureMenu || audioMenu || !SplitScreenPolicy.acceptPaneFocus(pendingPaneFocus, pane)) return
        pendingPaneFocus = null
        audioPane = SplitScreenPolicy.audioAfterSelection(selectedPane, pane, audioPane)
        selectedPane = pane
    }
    fun openPictureMenu() { pictureMenu = true; controlsVisible = true; requestedFocus = "pane" }
    fun expand(pane: Int) {
        selectedPane = pane
        audioPane = pane
        expandedPane = if (expandedPane == pane) null else pane
        pendingPaneFocus = pane
        requestedFocus = "pane"
        controlsVisible = false
    }
    BackHandler { back() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.key == Key.Back || event.key == Key.Escape) {
                if (event.type == KeyEventType.KeyUp) back()
                true
            } else if (wakeGuard.consumeTail(event.nativeKeyEvent.downTime, event.type == KeyEventType.KeyUp)) {
                true
            } else if (!controlsVisible && event.type == KeyEventType.KeyDown && event.key in listOf(
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                )) {
                // Waking the overlay must not also click/expand on the release of that press.
                wakeGuard.start(event.nativeKeyEvent.downTime)
                requestedFocus = if (event.key == Key.DirectionDown) "choose" else "pane"
                if (event.key == Key.DirectionLeft && expandedPane == null) {
                    audioPane = SplitScreenPolicy.audioAfterSelection(selectedPane, 0, audioPane)
                    selectedPane = 0; pendingPaneFocus = 0
                }
                if (event.key == Key.DirectionRight && expandedPane == null && mayOpenSecondary) {
                    audioPane = SplitScreenPolicy.audioAfterSelection(selectedPane, 1, audioPane)
                    selectedPane = 1; pendingPaneFocus = 1
                }
                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) openPictureMenu()
                controlsVisible = true
                interaction++
                true
            } else {
                if (event.type == KeyEventType.KeyDown) interaction++
                false
            }
        },
    ) {
        // Keep both PlayerViews in composition when expanded so their surfaces/buffers survive.
        // The hidden pane uses zero width. It keeps streaming while split-screen is active.
        Row(Modifier.fillMaxSize()) {
            Box(
                if (expandedPane == 1) Modifier.width(0.dp).fillMaxHeight()
                else Modifier.weight(1f).fillMaxHeight(),
            ) {
                VideoPane(
                    controller = primary,
                    title = primaryTitle,
                    audible = volumes.first > 0f,
                    selected = selectedPane == 0,
                    sideLabel = stringResource(R.string.split_left_picture),
                    showOverlay = controlsVisible,
                    focusRequester = paneFocus[0],
                    controlsFocus = firstControlFocus,
                    leftFocus = FocusRequester.Cancel,
                    rightFocus = if (expandedPane != null || !mayOpenSecondary) FocusRequester.Cancel else paneFocus[1],
                    resizeMode = SplitScreenPolicy.resizeMode(fillPictures, expandedPane != null),
                    onFocus = { focusedPane = 0; selectPane(0) },
                    onFocusLost = { if (focusedPane == 0) focusedPane = null },
                    onExpand = { openPictureMenu() },
                    onRetry = { primaryRetry++ },
                    resolveError = primaryResolveError,
                    onViewReleased = { graph.livePlaybackSession.releaseRetiredController(primary) },
                    visible = expandedPane != 1,
                )
            }
            Box(
                if (expandedPane == 0) Modifier.width(0.dp).fillMaxHeight()
                else Modifier.weight(1f).fillMaxHeight(),
            ) {
                val channel = secondaryChannel
                if (channel == null) {
                    Box(Modifier.fillMaxSize().focusRequester(paneFocus[1]).focusProperties {
                        canFocus = mayOpenSecondary && expandedPane != 0
                        left = paneFocus[0]; right = FocusRequester.Cancel; up = FocusRequester.Cancel
                        down = initialFocus
                    }.onFocusChanged {
                        if (it.isFocused) { focusedPane = 1; selectPane(1) }
                        else if (focusedPane == 1) focusedPane = null
                    }
                        .clickable(enabled = mayOpenSecondary && expandedPane != 0, onClick = { openPictureMenu() })
                        .then(if (controlsVisible) Modifier.border(3.dp,
                            if (selectedPane == 1) MaterialTheme.colorScheme.primary else Color.DarkGray) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.split_empty_picture), color = Color.LightGray)
                    }
                } else if (foreground && !inPip && mayOpenSecondary) {
                    SecondaryPane(
                        channel = channel,
                        audioRouter = audioRouter,
                        audible = volumes.second > 0f,
                        selected = selectedPane == 1,
                        showOverlay = controlsVisible,
                        focusRequester = paneFocus[1],
                        controlsFocus = firstControlFocus,
                        leftFocus = if (expandedPane != null) FocusRequester.Cancel else paneFocus[0],
                        rightFocus = FocusRequester.Cancel,
                        resizeMode = SplitScreenPolicy.resizeMode(fillPictures, expandedPane != null),
                        visible = expandedPane != 0,
                        onFocus = { focusedPane = 1; selectPane(1) },
                        onFocusLost = { if (focusedPane == 1) focusedPane = null },
                        onExpand = { openPictureMenu() },
                        onController = { secondaryController = it },
                        transferred = { secondaryTransferred },
                    )
                }
            }
        }
        if (controlsVisible) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.92f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth().focusProperties {
                up = paneFocus[expandedPane ?: selectedPane]
            }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SplitAction(
                    modifier = Modifier.weight(1f).focusRequester(expandFocus),
                    label = stringResource(if (expandedPane == null) R.string.split_expand else R.string.split_two_pictures),
                    onClick = { expand(selectedPane) }, enabled = selectedPane == 0 || secondaryChannel != null,
                )
                SplitAction(
                    modifier = Modifier.weight(1f),
                    label = stringResource(if (fillPictures) R.string.split_fit_pictures else R.string.split_fill_pictures),
                    onClick = { fillPictures = !fillPictures },
                )
                SplitAction(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.split_keep_picture),
                    enabled = selectedPane == 0 || (secondaryState is PlayerController.State.Playing &&
                        secondaryChannel?.let { secondaryController?.isReadyChannel(it.id) } == true),
                    onClick = {
                        if (selectedPane == 0) onExit()
                        else {
                            val channel = secondaryChannel
                            val extra = secondaryController
                            if (channel != null && extra != null && extra.isReadyChannel(channel.id)) {
                                audioRouter.close()
                                secondaryTransferred = true
                                onKeepSecondary(channel, extra, pickerStates[1].selectedContext)
                            }
                        }
                    },
                )
                SplitAction(label = stringResource(R.string.split_exit), onClick = onExit, modifier = Modifier.weight(1f).focusRequester(initialFocus).onGloballyPositioned { placed = true })
            }
            Text(
                stringResource(when {
                    !mayOpenSecondary -> R.string.split_recording_blocked
                    recordingBlocked -> R.string.split_recording_stopped
                    else -> R.string.split_connection_note
                }),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (picker) {
        SplitChannelPicker(
            primaryChannel = if (pickerPane == 0) secondaryChannel else primaryChannel,
            primaryChannelId = if (pickerPane == 0) secondaryChannel?.id else primaryChannelId,
            saved = pickerStates[pickerPane],
            onDismiss = { pendingPaneFocus = selectedPane; picker = false; controlsVisible = true; interaction++; requestedFocus = "pane" },
            onSelect = {
                if (pickerPane == 0) {
                    onChangePrimary(it, pickerStates[0].selectedContext)
                    audioPane = 0
                } else if (mayOpenSecondary) {
                    secondaryChannel = it
                    audioPane = 1
                }
                selectedPane = pickerPane
                pendingPaneFocus = pickerPane
                picker = false
                controlsVisible = true
                interaction++
                requestedFocus = "pane"
            },
        )
    }
    if (pictureMenu) SmallPictureMenu(
        title = stringResource(if (selectedPane == 0) R.string.split_left_picture else R.string.split_right_picture),
        wakeGuard = wakeGuard,
        actions = listOf(
            PictureMenuAction(stringResource(R.string.split_change_channel), selectedPane == 0 || mayOpenSecondary) { openPicker() },
            PictureMenuAction(stringResource(R.string.split_audio_select), true) { pictureMenu = false; audioMenu = true },
            PictureMenuAction(stringResource(if (expandedPane == null) R.string.split_expand else R.string.split_two_pictures),
                selectedPane == 0 || secondaryChannel != null) { pictureMenu = false; expand(selectedPane) },
        ),
        onDismiss = { pendingPaneFocus = selectedPane; pictureMenu = false; requestedFocus = "pane"; interaction++ },
    )
    if (audioMenu) SmallPictureMenu(
        title = stringResource(R.string.split_audio_select), wakeGuard = wakeGuard,
        actions = listOf(
            PictureMenuAction(stringResource(R.string.split_left_picture), true) { pendingPaneFocus = selectedPane; audioPane = 0; audioMenu = false; interaction++ },
            PictureMenuAction(stringResource(R.string.split_right_picture), secondaryChannel != null) { pendingPaneFocus = selectedPane; audioPane = 1; audioMenu = false; interaction++ },
        ),
        onDismiss = { pendingPaneFocus = selectedPane; audioMenu = false; requestedFocus = "pane"; interaction++ },
    )
}

private data class PictureMenuAction(val label: String, val enabled: Boolean, val run: () -> Unit)

/** A fresh paired Select is required: the press that opens the dialog cannot pick its first row. */
@Composable
private fun SmallPictureMenu(title: String, wakeGuard: SplitControlsWakeGuard,
    actions: List<PictureMenuAction>, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val activation = remember { MenuSelectActivation() }
    var focusedAction by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(placed) { if (placed) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).background(Color(0xFF171A20), RoundedCornerShape(12.dp)).padding(14.dp)
            .onPreviewKeyEvent { event ->
                val up = event.type == KeyEventType.KeyUp
                if (wakeGuard.consumeTail(event.nativeKeyEvent.downTime, up)) true
                else if (event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (activation.activate(event.nativeKeyEvent.downTime, up, event.nativeKeyEvent.repeatCount)) {
                        actions.getOrNull(focusedAction)?.takeIf { it.enabled }?.run?.invoke()
                    }
                    true
                } else false
            }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
            val firstEnabled = actions.indexOfFirst { it.enabled }
            actions.forEachIndexed { index, action ->
                SplitAction(action.label, enabled = action.enabled,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == firstEnabled) Modifier.focusRequester(firstFocus).onGloballyPositioned { placed = true } else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedAction = index },
                    onClick = action.run)
            }
        }
    }
}

/** Neutral actions; only actual remote focus is brightly filled and outlined. */
@Composable
private fun SplitAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.border(
            if (focused) 3.dp else 1.dp,
            if (focused) Color.White else if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) MaterialTheme.colorScheme.primary else Color(0xFF252932),
            contentColor = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White,
            disabledContainerColor = Color(0xFF171A20),
            disabledContentColor = Color.Gray,
        ),
    ) { Text(if (active) "✓ $label" else label, style = MaterialTheme.typography.labelSmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@OptIn(UnstableApi::class)
@Composable
private fun SecondaryPane(
    channel: Channel,
    audioRouter: SplitScreenAudioRouter,
    audible: Boolean,
    selected: Boolean,
    showOverlay: Boolean,
    focusRequester: FocusRequester,
    controlsFocus: FocusRequester,
    leftFocus: FocusRequester,
    rightFocus: FocusRequester,
    resizeMode: Int,
    visible: Boolean,
    onFocus: () -> Unit,
    onFocusLost: () -> Unit,
    onExpand: () -> Unit,
    onController: (PlayerController?) -> Unit,
    transferred: () -> Boolean,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    // Reused on channel changes, disposed immediately on background/PiP/exit/navigation.
    val controller = remember {
        graph.livePlaybackSession.createSecondaryController().apply { player.volume = 0f }
    }
    var resolveError by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    DisposableEffect(controller, audioRouter) {
        audioRouter.attachSecondary { controller.player.volume = it }
        onController(controller)
        onDispose {
            audioRouter.detachSecondary()
            onController(null)
            if (!transferred()) controller.release()
        }
    }
    LaunchedEffect(channel.id, retry) {
        resolveError = false
        // Stop the replaced stream before minting a Stalker URL; keep just two connections.
        controller.stop()
        try {
            if (graph.recordingRepository.observeActive().first().isNotEmpty()) return@LaunchedEffect
            val source = graph.sourceRepository.byId(channel.sourceId) ?: error("Missing source")
            check(source.enabled)
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            ensureActive()
            if (graph.recordingRepository.observeActive().first().isNotEmpty()) return@LaunchedEffect
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
        selected = selected,
        sideLabel = stringResource(R.string.split_right_picture),
        showOverlay = showOverlay,
        focusRequester = focusRequester,
        controlsFocus = controlsFocus,
        leftFocus = leftFocus,
        rightFocus = rightFocus,
        resizeMode = resizeMode,
        onFocus = onFocus,
        onFocusLost = onFocusLost,
        onExpand = onExpand,
        resolveError = resolveError,
        visible = visible,
        onRetry = { retry++ },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPane(
    controller: PlayerController,
    title: String,
    audible: Boolean,
    selected: Boolean,
    sideLabel: String,
    showOverlay: Boolean,
    focusRequester: FocusRequester,
    controlsFocus: FocusRequester,
    leftFocus: FocusRequester,
    rightFocus: FocusRequester,
    resizeMode: Int,
    onFocus: () -> Unit,
    onFocusLost: () -> Unit = {},
    onExpand: () -> Unit,
    resolveError: Boolean = false,
    visible: Boolean = true,
    onRetry: () -> Unit = { controller.retry() },
    onViewReleased: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = visible
                up = FocusRequester.Cancel
                left = leftFocus
                right = rightFocus
                if (showOverlay) down = controlsFocus
            }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() else onFocusLost() }
            .clickable(enabled = visible, onClick = onExpand),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // SurfaceView can draw its zoomed surface outside Compose's half-screen clip
                // on this Shield. TextureView and native parent clipping contain BOTH pictures.
                (android.view.LayoutInflater.from(context)
                    .inflate(R.layout.split_player_view, null, false) as PlayerView).apply {
                    player = controller.player
                    useController = false
                    clipChildren = true
                    clipToPadding = true
                    this.resizeMode = resizeMode
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { it.resizeMode = resizeMode },
            onRelease = { it.player = null; onViewReleased() },
        )
        if (showOverlay) {
            // Draw ABOVE the Android video surface so focus/selection is not hidden by it.
            Box(Modifier.fillMaxSize().border(
                if (focused) 4.dp else 3.dp,
                if (focused) Color.White else if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray,
            ))
            Text(
                listOfNotNull(sideLabel, if (selected) stringResource(R.string.split_selected) else null,
                    if (audible) stringResource(R.string.player_audio) else null).joinToString(" · ") + " — " + title,
                Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.85f)).padding(10.dp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state is PlayerController.State.Buffering || state == PlayerController.State.Idle && !resolveError) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (state is PlayerController.State.Error || resolveError) {
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.9f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.split_playback_error), color = Color.White)
                SplitAction(enabled = visible, label = stringResource(R.string.common_try_again), onClick = onRetry)
            }
        }
    }
}

@Composable
private fun SplitChannelPicker(
    primaryChannel: Channel?,
    primaryChannelId: Long?,
    saved: SplitPickerState,
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
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var nowProgrammes by remember { mutableStateOf<Map<Pair<Long, String>, Programme>>(emptyMap()) }
    var query by saved::query
    var categoryKey by saved::categoryKey
    var favouritesOnly by saved::favouritesOnly
    val initialFocus = remember { FocusRequester() }
    var initialPlaced by remember { mutableStateOf(false) }
    var focusArea by remember { mutableStateOf("rail") }
    var focusChannel by remember { mutableStateOf<Long?>(null) }
    var focusTicket by remember { mutableIntStateOf(0) }
    var initialRestore by remember { mutableStateOf(true) }
    val repeatGate = remember { GuideDirectionRepeatGate() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(channels, nowMillis) {
        if (channels.isEmpty()) return@LaunchedEffect
        // One read-only current-programme query/index, not one query per channel or a new download.
        graph.epgRepository.observeNow(nowMillis)
            .map { SplitScreenPolicy.currentProgrammes(channels, it, nowMillis) }
            .flowOn(Dispatchers.Default)
            .collect { nowProgrammes = it }
    }
    LaunchedEffect(Unit) {
        try {
            val enabledSources = graph.sourceRepository.observeAll().first().filter { it.enabled }.map { it.id }.toSet()
            categories = graph.catalogRepository.liveCategories().filter { it.sourceId in enabledSources }
            graph.catalogRepository.observeChannels().collect { updated ->
                channels = updated.filter { it.sourceId in enabledSources }
                loaded = true
            }
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
    LaunchedEffect(channels, loaded, visibleCategoryKeys, categoryKey, favouritesOnly, query, primaryChannel, primaryChannelId) {
        if (!loaded) return@LaunchedEffect
        if (categoryKey != null && categoryKey !in visibleCategoryKeys) categoryKey = null
        val updated = withContext(Dispatchers.Default) {
            SplitScreenPolicy.choices(channels.filter {
                (it.categoryId == null || (it.sourceId to it.categoryId) in visibleCategoryKeys) &&
                    (categoryKey == null || (it.sourceId to it.categoryId) == categoryKey) &&
                    (query.isBlank() || it.shownName.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true))
            }, primaryChannel ?: channels.firstOrNull { it.id == primaryChannelId })
                .filter { !favouritesOnly || it.favourite }
        }
        choices = updated
        if ((initialRestore && saved.focusedChannelId != null) || focusArea == "channels") {
            if (updated.isNotEmpty()) {
                val index = SplitScreenPolicy.restoredChannelIndex(updated.map { it.id }, saved.focusedChannelId)
                saved.channelScroll.scrollToItem(index)
                focusChannel = updated[index].id
                focusTicket++
            } else {
                focusArea = "rail"
                focusChannel = null
                withFrameNanos { }
                runCatching { initialFocus.requestFocus() }
            }
        }
        initialRestore = false
    }
    // Start in the stable category rail so a TV remote can browse without opening the keyboard.
    LaunchedEffect(initialPlaced) {
        if (initialPlaced) {
            withFrameNanos { }; runCatching { initialFocus.requestFocus() }
        }
    }
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
            Text(stringResource(R.string.split_change_channel), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.split_search_channels)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusArea = "search" },
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(220.dp).fillMaxHeight()) {
                    PickerRow(
                        stringResource(R.string.guide_all_channels),
                        categoryKey == null && !favouritesOnly,
                        focusRequester = initialFocus,
                        onPlaced = { initialPlaced = true },
                        onFocused = { focusArea = "rail" },
                    ) {
                        categoryKey = null; favouritesOnly = false
                    }
                    PickerRow(stringResource(R.string.guide_favourites), favouritesOnly, onFocused = { focusArea = "rail" }) {
                        categoryKey = null; favouritesOnly = true
                    }
                    LazyColumn(state = saved.categoryScroll, modifier = Modifier.weight(1f)) {
                        itemsIndexed(visibleCategories, key = { _, it -> "${it.sourceId}:${it.id}" }) { _, category ->
                            PickerRow(category.name, categoryKey == (category.sourceId to category.id), onFocused = { focusArea = "rail" }) {
                                categoryKey = category.sourceId to category.id; favouritesOnly = false
                            }
                        }
                    }
                }
                if (!loaded && !loadError) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (choices.isEmpty()) {
                    Text(stringResource(if (loadError) R.string.split_catalogue_error else R.string.split_no_channels))
                } else {
                    LazyColumn(state = saved.channelScroll, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        itemsIndexed(choices, key = { _, channel -> channel.id }) { _, channel ->
                            val programme = nowProgrammes[SplitScreenPolicy.logicalKey(channel)]
                                ?.takeIf { it.isLiveAt(nowMillis) }
                            val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
                            val programmeText = programme?.let {
                                "${timeFormat.format(java.util.Date(it.startUtcMillis))}–${timeFormat.format(java.util.Date(it.endUtcMillis))}  " +
                                    guideProgrammeLabel(it.title, it.description).singleLineText
                            } ?: stringResource(R.string.guide_no_info)
                            PickerRow(
                                channel.shownName, false,
                                detail = programmeText,
                                focusTicket = if (focusChannel == channel.id) focusTicket else 0,
                                onFocused = { saved.focusedChannelId = channel.id; focusArea = "channels"; focusChannel = null },
                            ) {
                                saved.focusedChannelId = channel.id
                                saved.selectedContext = choices
                                onSelect(channel)
                            }
                        }
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.onFocusChanged { if (it.isFocused) focusArea = "cancel" }) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    detail: String? = null,
    focusRequester: FocusRequester? = null,
    focusTicket: Int = 0,
    onPlaced: () -> Unit = {},
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ownRequester = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(focusTicket, placed) {
        if (focusTicket > 0 && placed) { withFrameNanos { }; runCatching { ownRequester.requestFocus() } }
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .focusRequester(focusRequester ?: ownRequester)
            .onGloballyPositioned { placed = true; onPlaced() }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick).padding(10.dp),
    ) {
        Text(label,
            color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) Text(detail,
            color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
