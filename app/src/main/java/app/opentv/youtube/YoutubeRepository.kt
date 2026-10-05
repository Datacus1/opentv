/* SPDX-License-Identifier: GPL-3.0-or-later */
package app.opentv.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Public, signed-out searches only. No IPTV client, credentials, disk cache, or request logging. */
internal object YoutubeRepository {
    private val extraction = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val ready by lazy { NewPipe.init(YoutubeDownloader(client)); true }

    suspend fun search(query: String): List<YoutubeVideo> = withContext(Dispatchers.IO) {
        extraction.withLock {
            check(ready)
            val text = query.trim().take(200)
            require(text.isNotEmpty())
            val service = ServiceList.YouTube
            SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(text, listOf("videos"), ""))
                .relatedItems.filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamType.VIDEO_STREAM }.take(24).map {
                    YoutubeVideo(it.url, it.name.take(300), it.uploaderName.orEmpty().take(150))
                }
        }
    }

    suspend fun resolve(video: YoutubeVideo): YoutubePlayback = withContext(Dispatchers.IO) {
        extraction.withLock {
            check(ready)
            val info = StreamInfo.getInfo(ServiceList.YouTube, video.pageUrl)
            if (info.streamType != StreamType.VIDEO_STREAM) throw IOException("Public recorded videos only")
            // Avoid AV1/software decoding and manifest formats not supported by this first version.
            fun supported(stream: VideoStream) = stream.isUrl &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                YoutubePlaybackPolicy.supports(stream.codec, stream.resolution, stream.fps)
            val muxed = info.videoStreams.filter { supported(it) && !it.isVideoOnly }
                .maxByOrNull { height(it.resolution) }
            val picture = info.videoOnlyStreams.filter { supported(it) }
                .sortedWith(compareByDescending<VideoStream> {
                    it.codec.orEmpty().startsWith("avc", ignoreCase = true)
                }.thenByDescending { height(it.resolution) }).firstOrNull()
            val sound = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                    it.format?.mimeType == "audio/mp4"
                }.thenByDescending { it.averageBitrate }).firstOrNull()
            if (muxed != null && (picture == null || sound == null || height(muxed.resolution) >= height(picture.resolution)))
                return@withLock YoutubePlayback(muxed.content, muxed.format?.mimeType, null, null)
            if (picture != null && sound != null) return@withLock YoutubePlayback(
                picture.content, picture.format?.mimeType, sound.content, sound.format?.mimeType)
            throw IOException("No supported public video stream")
        }
    }

    private fun height(resolution: String): Int = resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}

internal object YoutubePlaybackPolicy {
    fun supports(codec: String?, resolution: String, fps: Int): Boolean {
        val name = codec.orEmpty().lowercase(java.util.Locale.ROOT)
        val height = resolution.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        return height in 1..1080 && fps in 0..60 &&
            (name.startsWith("avc") || name.startsWith("vp9") || name.startsWith("vp09"))
    }
}

internal data class YoutubeVideo(val pageUrl: String, val title: String, val uploader: String) {
    override fun toString() = "YoutubeVideo(title=$title)"
}
internal data class YoutubePlayback(val videoUrl: String, val videoMime: String?, val audioUrl: String?, val audioMime: String?) {
    override fun toString() = "YoutubePlayback(videoMime=$videoMime, separateAudio=${audioUrl != null})"
}

internal class YoutubeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
            .header("User-Agent", "Mozilla/5.0")
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val body = request.dataToSend()?.toRequestBody() ?: if (request.httpMethod() in listOf("POST", "PUT", "PATCH"))
            ByteArray(0).toRequestBody() else null
        client.newCall(builder.method(request.httpMethod(), body).build()).execute().use {
            val responseBody = it.body?.let { response ->
                response.byteStream().use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 16 * 1024 * 1024) throw IOException("YouTube response too large")
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
            }.orEmpty()
            return Response(it.code, it.message, it.headers.toMultimap(), responseBody, it.request.url.toString())
        }
    }
}
