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
    private val fileUrl: String,
    isAppend: Boolean
) : AbstractFileByteChannel(isAppend) {

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val request = Request.Builder()
            .url(fileUrl)
            .header("Range", "bytes=$position-${position + size - 1}")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != HTTP_RANGE_NOT_SATISFIABLE) {
            response.close()
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
            response.close()
            return ByteBuffer::class.EMPTY
        }
        val body = response.body
        val destination = ByteBuffer.allocate(size)
        val limit = body.byteStream().use { inputStream ->
            inputStream.readFully(destination.array(), destination.arrayOffset(), size)
        }
        destination.limit(limit)
        return destination
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
        try {
            val request = Request.Builder()
                .url(fileUrl)
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.let { return it }
                }
            }
        } catch (e: Exception) {
            // Fallback to GET Range
        }

        val request = Request.Builder()
            .url(fileUrl)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != HTTP_RANGE_NOT_SATISFIABLE) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val contentRange = response.header("Content-Range")
            if (contentRange != null && contentRange.contains("/")) {
                contentRange.substringAfter("/").toLongOrNull()?.let { return it }
            }
            return response.header("Content-Length")?.toLongOrNull()
                ?: throw IOException("Missing size info")
        }
    }

    @Throws(IOException::class)
    override fun onClose() {}

    companion object {
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
