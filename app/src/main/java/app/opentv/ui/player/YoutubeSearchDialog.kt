/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.ui.player

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.R
import app.opentv.core.PipState
import app.opentv.youtube.YoutubeRepository
import app.opentv.youtube.YoutubeVideo
import kotlinx.coroutines.CancellationException

/** Voice goes to Android's installed speech recognizer, never to an IPTV provider. */
@Composable
internal fun YoutubeSearchDialog(onDismiss: () -> Unit, onSelect: (YoutubeVideo) -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var searchTicket by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<YoutubeVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var voiceUnavailable by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun search() { submitted = query.trim().take(200); if (submitted.isNotEmpty()) searchTicket++ }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            val heard = it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) { query = heard.take(200); search() }
        }
    }
    fun speak() {
        voiceUnavailable = false
        try {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch (_: Exception) { voiceUnavailable = true }
    }
    // A speech/keyboard activity is not a commercial break. Do not PiP/remove the search UI.
    DisposableEffect(Unit) {
        val previous = PipState.eligible
        PipState.eligible = false
        onDispose { PipState.eligible = previous }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; speak() }
    LaunchedEffect(searchTicket) {
        if (searchTicket == 0) return@LaunchedEffect
        loading = true; searchFailed = false; results = emptyList()
        try { results = YoutubeRepository.search(submitted) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { searchFailed = true }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.88f)
            .background(MaterialTheme.colorScheme.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.youtube_voice_search), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, onValueChange = { query = it.take(200) }, singleLine = true,
                label = { Text(stringResource(R.string.youtube_search_hint)) },
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                        keyboard?.hide(); searchFocus.requestFocus(); true
                    } else false
                })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { keyboard?.hide(); search() }, modifier = Modifier.focusRequester(searchFocus)) {
                    Text(stringResource(R.string.youtube_search))
                }
                Button(onClick = { speak() }) { Text(stringResource(R.string.youtube_speak)) }
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
            if (voiceUnavailable) Text(stringResource(R.string.youtube_voice_unavailable))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searchFailed) Text(stringResource(R.string.youtube_search_failed))
            if (searchTicket != 0 && !loading && !searchFailed && results.isEmpty()) {
                Text(stringResource(R.string.youtube_no_results))
            }
            Text(stringResource(R.string.youtube_signed_out), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results, key = { it.pageUrl }) { video ->
                    Button(onClick = { onSelect(video) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(video.uploader, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
