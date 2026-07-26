package org.sisam.langtutor.packs

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    // INSECURE — testing only. When true, TLS certificate + hostname checks are
    // disabled (works around an intercepting network / untrusted CA). Content is
    // STILL protected by the pack's SHA-256, which is never bypassed. Gated to
    // debug builds by the UI; must never be enabled in a release build.
    private val insecureTls: Boolean = false,
) : PackFetcher {

    override suspend fun open(url: String, offset: Long): FetchResult {
        var current = url
        repeat(MAX_REDIRECTS) {
            val host = URL(current).host
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false // handled manually so Range survives redirects
                requestMethod = "GET"
                // Some CDNs/WAFs reject the default Java/Dalvik UA or a missing Accept.
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                if (insecureTls && this is HttpsURLConnection) {
                    sslSocketFactory = trustAllSocketFactory()
                    setHostnameVerifier { _, _ -> true }
                }
            }
            // The TLS handshake + connect happen here; name the host so a trust
            // failure points at the exact server (huggingface.co vs the CDN).
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw java.io.IOException("${e.javaClass.simpleName} connecting to $host: ${e.message}", e)
            }
            when (code) {
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
                    // Host only — the full URL may carry signed query params
                    // (CDN signatures) that must not surface in user-visible text.
                    error("HTTP $code from $host")
                }
            }
        }
        error("Too many redirects for $url")
    }

    private companion object {
        const val MAX_REDIRECTS = 5

        /** INSECURE trust-all factory — testing only (see [insecureTls]). */
        fun trustAllSocketFactory(): SSLSocketFactory {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            return SSLContext.getInstance("TLS").apply {
                init(null, trustAll, java.security.SecureRandom())
            }.socketFactory
        }
    }
}
