package repeater

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.fileSize

/**
 * Optional re-encode of downloaded episodes to save disk space. Lossless
 * recompression of already-lossy MP3/AAC only saves a few percent, so this
 * is a lossy step - but at podcast-speech bitrates it is hard to hear:
 *
 *  - opus: ~32 kbps sounds like a typical 128 kbps MP3 for speech, at
 *    roughly a quarter of the size. Not playable on iOS/Apple Podcasts.
 *  - aac:  AAC-LC in an m4a container, playable everywhere including iOS.
 *    Needs ~64 kbps for comparable speech quality, so saves less.
 *
 * Settings come from the global `transcode:` config section, overridable
 * per podcast. Requires ffmpeg on the PATH. When ffmpeg is missing, or a
 * transcode fails, or the result is not smaller than the original, the
 * episode is cached in its original form instead - enabling this can never
 * lose an episode.
 */
class Transcoder(private val configStore: ConfigStore) {
    private val log = LoggerFactory.getLogger(Transcoder::class.java)

    private val ffmpegAvailable: Boolean by lazy {
        try {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            val ok = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
            if (!ok) log.warn("ffmpeg did not run cleanly; episodes will be cached in their original format")
            ok
        } catch (e: Exception) {
            log.warn(
                "Transcoding is enabled in config but ffmpeg was not found on the PATH ({}). " +
                    "Install ffmpeg to activate it; until then episodes are cached in their original format.",
                e.message
            )
            false
        }
    }

    /** Whether ffmpeg is usable at all; logs a warning once when it is not. */
    fun available(): Boolean = ffmpegAvailable

    fun settingsFor(podcast: PodcastConfig): TranscodeConfig =
        podcast.transcode ?: configStore.config.transcode

    /** True when transcoding applies to this podcast and ffmpeg is usable. */
    fun active(podcast: PodcastConfig): Boolean =
        settingsFor(podcast).enabled && ffmpegAvailable

    /** What a transcoded episode of this podcast is served as. */
    fun contentTypeFor(podcast: PodcastConfig): String =
        if (settingsFor(podcast).codec == "aac") "audio/mp4" else "audio/ogg"

    fun extensionFor(podcast: PodcastConfig): String =
        if (settingsFor(podcast).codec == "aac") ".m4a" else ".opus"

    /**
     * Whether an enclosure/download with this declared content type should be
     * transcoded. Blank and octet-stream types are assumed to be audio (many
     * CDNs mislabel MP3s that way); a failed transcode falls back to the
     * original file, so guessing wrong here is harmless. Sources already in
     * the target codec's container are left alone - re-encoding them would
     * only lose quality.
     */
    fun wantsTranscode(podcast: PodcastConfig, contentType: String?): Boolean {
        if (!active(podcast)) return false
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: ""
        if (mime.isEmpty() || mime == "application/octet-stream" || mime == "binary/octet-stream") return true
        val alreadyTargetCodec = when (settingsFor(podcast).codec) {
            "aac" -> setOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac")
            else -> setOf("audio/ogg", "audio/opus")
        }
        return mime.startsWith("audio/") && mime !in alreadyTargetCodec
    }

    /**
     * Re-encodes [input] with the podcast's configured codec and bitrate,
     * writing to [output]. Returns true only when ffmpeg succeeded AND the
     * result is smaller than the original; otherwise [output] is cleaned up
     * and the caller should keep [input] as-is.
     */
    fun transcode(input: Path, output: Path, podcast: PodcastConfig, logTag: String): Boolean {
        val settings = settingsFor(podcast)
        val codecArgs = when (settings.codec) {
            // -movflags +faststart puts the index at the front of the m4a so
            // podcatchers can stream/seek before the download completes.
            "aac" -> listOf("-c:a", "aac", "-movflags", "+faststart", "-f", "ipod")
            else -> listOf("-c:a", "libopus", "-f", "ogg")
        }
        val startedAt = System.currentTimeMillis()
        try {
            val process = ProcessBuilder(
                listOf(
                    "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(),
                    "-vn", // drop embedded artwork/video streams
                    "-b:a", "${settings.bitrateKbps}k",
                ) + codecArgs + output.toString()
            ).redirectErrorStream(true).start()
            val ffmpegOutput = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                log.warn("[{}] ffmpeg timed out, keeping original file", logTag)
                return cleanupAndFail(output)
            }
            if (process.exitValue() != 0) {
                log.warn("[{}] ffmpeg failed (exit {}), keeping original file: {}",
                    logTag, process.exitValue(), ffmpegOutput.trim().take(500))
                return cleanupAndFail(output)
            }

            val originalSize = input.fileSize()
            val transcodedSize = output.fileSize()
            if (transcodedSize >= originalSize) {
                log.info("[{}] transcode not smaller ({} -> {} bytes), keeping original file",
                    logTag, originalSize, transcodedSize)
                return cleanupAndFail(output)
            }

            log.info(
                "[{}] transcoded to {} {}k: {} -> {} bytes ({}% saved) in {} ms",
                logTag, settings.codec, settings.bitrateKbps, originalSize, transcodedSize,
                100 - transcodedSize * 100 / originalSize,
                System.currentTimeMillis() - startedAt
            )
            return true
        } catch (e: Exception) {
            log.warn("[{}] transcode failed ({}), keeping original file", logTag, e.message)
            return cleanupAndFail(output)
        }
    }

    private fun cleanupAndFail(output: Path): Boolean {
        Files.deleteIfExists(output)
        return false
    }
}
