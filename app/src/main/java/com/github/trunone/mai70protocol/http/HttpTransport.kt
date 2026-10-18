package com.github.trunone.mai70protocol.http

import java.io.InputStream

/**
 * Abstraction for HTTP transport used by `70mai-protocol`.
 */
interface HttpTransport {
    /**
     * Executes a GET request and returns the response body as text.
     *
     * @param url Target URL.
     * @return String response body, or null if request failed or was unfulfilled.
     */
    suspend fun get(url: String): String?

    /**
     * Opens an [InputStream] to stream content from the target URL.
     *
     * @param url Target download URL.
     * @return Open [InputStream], or null if request failed.
     */
    suspend fun openStream(url: String): InputStream?

    /**
     * Probes HTTP HEAD or GET Range headers for file metadata (content size and last modified date).
     *
     * @param url Target URL.
     * @return Pair of Content-Length (Long) and Last-Modified header value (String), or null if request failed.
     */
    suspend fun probe(url: String): Pair<Long, String?>? = null
}
