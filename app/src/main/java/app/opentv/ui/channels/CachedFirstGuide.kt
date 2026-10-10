package app.opentv.ui.channels

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.repo.ChannelGrouping
import app.opentv.data.repo.chooseGuideCandidate
import app.opentv.data.repo.distinctByQuality
import app.opentv.ui.ChannelsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.yield

data class GuideProgrammeSnapshot(
    val byId: Map<String, List<Programme>> = emptyMap(),
    val loaded: Boolean = false,
    val failed: Boolean = false,
)

data class GuideRowsState(
    val rows: List<ChannelsViewModel.Row> = emptyList(),
    val catalogueReady: Boolean = false,
    val listingsLoading: Boolean = false,
    val failed: Boolean = false,
)

/** No programme query or HTTP is needed to prepare the selectable saved-channel rows. */
internal class GuideRowPlan(channels: List<Channel>, private val checkCancellation: () -> Unit = {}) {
    private val keys = ChannelGrouping.PreparedKeys(channels, checkCancellation)
    val channelsOnly: List<ChannelsViewModel.Row> = keys.groups(channels).values.mapIndexed { index, unsorted ->
        if (index % 64 == 0) checkCancellation()
        val group = unsorted.sortedByDescending { it.qualityRank }
        val variants = distinctByQuality(group)
        val primary = variants.first()
        ChannelsViewModel.Row(primary, variants, null, null, emptyList(), group, keys.preparedKey(primary))
    }

    fun keepListingsIfUnchanged(previous: List<ChannelsViewModel.Row>): List<ChannelsViewModel.Row> {
        if (previous.size != channelsOnly.size) return channelsOnly
        for (index in channelsOnly.indices) {
            if (index % 64 == 0) checkCancellation()
            val old = previous[index]
            val fresh = channelsOnly[index]
            if (old.key != fresh.key || old.primary != fresh.primary ||
                old.variants != fresh.variants || old.allVariants != fresh.allVariants) return channelsOnly
        }
        return previous
    }

    fun populate(byId: Map<String, List<Programme>>, now: Long,
        check: () -> Unit = checkCancellation): List<ChannelsViewModel.Row> =
        channelsOnly.mapIndexed { index, row ->
            if (index % 64 == 0) check()
            val list = chooseGuideCandidate(row.allVariants, byId, now, keys)
                ?.let { byId[it] }?.sortedBy { it.startUtcMillis }.orEmpty()
            row.copy(now = list.firstOrNull { it.isLiveAt(now) },
                next = list.firstOrNull { it.startUtcMillis > now }, programmes = list)
        }
}

/**
 * Channel identity is prepared once per channel snapshot, independently of programme readiness.
 * Listing updates keep the last visible rows until replacement rows are complete. Channel/filter
 * changes cancel old work and use fresh identities; no previous source/category rows are borrowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun cachedFirstGuideRows(
    channels: Flow<List<Channel>>,
    programmes: Flow<GuideProgrammeSnapshot>,
    now: () -> Long = System::currentTimeMillis,
    previousRows: List<ChannelsViewModel.Row> = emptyList(),
): Flow<GuideRowsState> = flow {
    var latest = GuideRowsState(previousRows)
    emitAll(channels.distinctUntilChanged().flatMapLatest { snapshot ->
        flow {
            val context = currentCoroutineContext()
            val plan = GuideRowPlan(snapshot) { context.ensureActive() }
            var visible = plan.keepListingsIfUnchanged(latest.rows)
            emit(GuideRowsState(visible, catalogueReady = true, listingsLoading = visible.isNotEmpty()))
            yield() // Let the saved channels reach the UI before cached listings are assembled.
            emitAll(programmes.transformLatest { window ->
                if (window.failed) {
                    emit(GuideRowsState(visible, catalogueReady = true, failed = true))
                    return@transformLatest
                }
                emit(GuideRowsState(visible, catalogueReady = true, listingsLoading = visible.isNotEmpty()))
                if (window.loaded) {
                    val updateContext = currentCoroutineContext()
                    visible = plan.populate(window.byId, now()) { updateContext.ensureActive() }
                    emit(GuideRowsState(visible, catalogueReady = true))
                }
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(GuideRowsState(visible, catalogueReady = true, failed = true))
            })
        }
    }.onEach { latest = it }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(latest.copy(listingsLoading = false, failed = true))
    })
}
