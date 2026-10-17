/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70.client

import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.EMPTY
import me.zhanghai.android.files.provider.common.readFully
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer

class FileByteChannel(
    private val client: OkHttpClient,
    private val authority: Authority,
    private val candidateUrls: List<String>,
    private val fileSize: Long?,
    isAppend: Boolean
) : AbstractFileByteChannel(isAppend) {

    private var activeUrl: String? = candidateUrls.firstOrNull()

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val urlsToTry = mutableListOf<String>()
        activeUrl?.let { urlsToTry.add(it) }
        for (u in candidateUrls) {
            if (u != activeUrl) urlsToTry.add(u)
        }

        var lastException: IOException? = null
        for (url in urlsToTry) {
            var attemptedReauth = false
            while (true) {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$position-${position + size - 1}")
                    .build()
                val response = try {
                    client.newCall(request).execute()
                } catch (e: IOException) {
                    lastException = e
                    break
                }

                if (response.code == HTTP_FORBIDDEN && !attemptedReauth) {
                    response.close()
                    attemptedReauth = true
                    try {
                        Client.prepareCameraAccess(authority)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    continue
                }

                if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                    response.close()
                    return ByteBuffer::class.EMPTY
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    val message = response.message
                    response.close()
                    lastException = IOException("HTTP $code: $message")
                    break
                }

                activeUrl = url
                val body = response.body
                val destination = ByteBuffer.allocate(size)
                val limit = body.byteStream().use { inputStream ->
                    inputStream.readFully(destination.array(), destination.arrayOffset(), size)
                }
                destination.limit(limit)
                return destination
            }
        }

        throw lastException ?: IOException("Failed to read from candidate URLs: $candidateUrls")
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        throw UnsupportedOperationException("Writing is not supported")
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        throw UnsupportedOperationException("Truncate is not supported")
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        if (fileSize != null && fileSize >= 0) {
            return fileSize
        }

        val urlsToTry = mutableListOf<String>()
        activeUrl?.let { urlsToTry.add(it) }
        for (u in candidateUrls) {
            if (u != activeUrl) urlsToTry.add(u)
        }

        for (url in urlsToTry) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                        val contentRange = response.header("Content-Range")
                        if (contentRange != null && contentRange.contains("/")) {
                            contentRange.substringAfter("/").toLongOrNull()?.let { return it }
                        }
                        response.header("Content-Length")?.toLongOrNull()?.let { return it }
                    }
                }
            } catch (e: Exception) {
                // Try next candidate
            }
        }

        return fileSize ?: 0L
    }

    @Throws(IOException::class)
    override fun onClose() {}

    companion object {
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
