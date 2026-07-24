package org.sisam.langtutor.packs

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens a byte stream for a pack URL, supporting resume via an HTTP Range
 * request. Injected into [RealPackRepository] so the download logic is testable
 * without a network (a fake fetcher serves bytes from memory).
 */
fun interface PackFetcher {
    /** Open [url] starting at byte [offset]. Implementations follow redirects. */
    suspend fun open(url: String, offset: Long): FetchResult
}

/**
 * A pack byte stream.
 * @param stream bytes starting at [startedAt].
 * @param totalBytes full file size, or -1 if the server didn't report it.
 * @param startedAt the offset the stream actually begins at — equals the
 *   requested offset on a 206 Partial response, or 0 when the server ignored the
 *   Range header and sent the whole file (so the caller must not append).
 */
class FetchResult(
    val stream: InputStream,
    val totalBytes: Long,
    val startedAt: Long,
)

/**
 * Real HTTP fetcher. The app's INTERNET permission is used ONLY here, for
 * user-initiated pack downloads (see [PackRepository]'s contract). Inbound only;
 * nothing is ever uploaded.
 */
class HttpPackFetcher(
    private val connectTimeoutMs: Int = 30_000,
    // Generous per-read timeout: a multi-GB mobile download can stall briefly
    // without being dead. Too-short a value was a likely cause of spurious fails.
    private val readTimeoutMs: Int = 120_000,
    private val userAgent: String = "lang-tutor/0.1 (Android; on-device tutor)",
) : PackFetcher {

    override suspend fun open(url: String, offset: Long): FetchResult {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false // handled manually so Range survives redirects
                requestMethod = "GET"
                // Some CDNs/WAFs reject the default Java/Dalvik UA or a missing Accept.
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            when (val code = conn.responseCode) {
                in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                        ?: error("Redirect ($code) without Location for $current")
                    conn.disconnect()
                    current = URL(URL(current), location).toString()
                }
                HttpURLConnection.HTTP_OK -> {
                    val len = conn.contentLengthLong
                    return FetchResult(conn.inputStream, totalBytes = if (len >= 0) len else -1L, startedAt = 0L)
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    // Content-Range: bytes START-END/TOTAL
                    val total = conn.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    return FetchResult(conn.inputStream, totalBytes = total, startedAt = offset)
                }
                else -> {
                    conn.disconnect()
                    error("HTTP $code for $current")
                }
            }
        }
        error("Too many redirects for $url")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
