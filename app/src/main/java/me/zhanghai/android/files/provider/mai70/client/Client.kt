/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70.client

import com.github.trunone.mai70protocol.Mai70Client
import com.github.trunone.mai70protocol.TokenStore
import com.github.trunone.mai70protocol.http.HttpTransport
import com.github.trunone.mai70protocol.models.Category
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.LocalWatchService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java8.nio.file.Path as Java8Path

object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val clients = ConcurrentHashMap<Authority, Mai70Client>()

    private val tokenStore = object : TokenStore {
        override fun getToken(authority: com.github.trunone.mai70protocol.models.Authority): String? {
            val appAuth = Authority(
                Protocol.MAI70,
                authority.host,
                authority.port,
                ""
            )
            return authenticator.getToken(appAuth)
        }

        override fun saveToken(authority: com.github.trunone.mai70protocol.models.Authority, token: String) {}
    }

    private fun getMai70Client(authority: Authority): Mai70Client {
        return clients.getOrPut(authority) {
            val transport: HttpTransport = try {
                com.github.trunone.mai70protocol.android.AndroidNetworkTransport(
                    context = application,
                    defaultCameraIp = authority.host
                )
            } catch (e: Throwable) {
                object : HttpTransport {
                    override suspend fun get(url: String): String? {
                        val request = Request.Builder().url(url).build()
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return null
                            return response.body.string()
                        }
                    }

                    override suspend fun openStream(url: String): InputStream? {
                        val request = Request.Builder().url(url).build()
                        val response = httpClient.newCall(request).execute()
                        if (!response.isSuccessful) {
                            response.close()
                            return null
                        }
                        return response.body.byteStream()
                    }

                    override suspend fun probe(url: String): Pair<Long, String?>? {
                        val request = Request.Builder().url(url).head().build()
                        try {
                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                                    val lastModified = response.header("Last-Modified")
                                    return Pair(size, lastModified)
                                }
                            }
                        } catch (_: Exception) {}
                        return null
                    }
                }
            }
            Mai70Client(
                transport = transport,
                tokenStore = tokenStore,
                host = authority.host,
                port = authority.port
            )
        }
    }

    fun prepareCameraAccess(authority: Authority) {
        val mai70Client = getMai70Client(authority)
        kotlinx.coroutines.runBlocking {
            try {
                mai70Client.prepareCameraAccess()
            } catch (e: Exception) {
                // Best effort
            }
        }
    }

    data class Mai70FileInfo(
        val isDirectory: Boolean,
        val size: Long,
        val lastModifiedTime: Instant
    )

    @Throws(IOException::class)
    fun listDirectory(path: Path): List<Path> {
        val authority = path.authority
        val remotePath = path.remotePath
        val segments = remotePath.split('/').filter { it.isNotEmpty() }

        val mai70Client = getMai70Client(authority)

        return kotlinx.coroutines.runBlocking {
            if (segments.isEmpty()) {
                val counts = try {
                    mai70Client.getFileCounts()
                } catch (e: Exception) {
                    emptyMap()
                }
                val result = mutableListOf<Path>()
                for (category in Category.entries) {
                    if (category == Category.UNKNOWN) continue
                    val count = counts[category] ?: -1
                    if (count != 0) {
                        result.add(path.resolve(category.categoryName))
                    }
                }
                result
            } else if (segments.size == 1) {
                val categoryName = segments[0]
                val category = Category.fromName(categoryName)
                val fileEntries = mai70Client.listFiles(category)
                fileEntries.map { entry ->
                    path.resolve(entry.name)
                }
            } else {
                emptyList()
            }
        }
    }

    @Throws(IOException::class)
    fun listFile(path: Path): Mai70FileInfo {
        val remotePath = path.remotePath
        val segments = remotePath.split('/').filter { it.isNotEmpty() }

        if (segments.isEmpty() || (segments.size == 1 && Category.fromName(segments[0]) != Category.UNKNOWN)) {
            return Mai70FileInfo(isDirectory = true, size = 0L, lastModifiedTime = Instant.EPOCH)
        }

        val categoryName = segments[0]
        val fileName = segments.getOrNull(1) ?: ""

        val mai70Client = getMai70Client(path.authority)

        return kotlinx.coroutines.runBlocking {
            try {
                val info = mai70Client.getFileInfo(categoryName, fileName)
                Mai70FileInfo(
                    isDirectory = false,
                    size = info.size,
                    lastModifiedTime = info.lastModifiedTime
                )
            } catch (e: Exception) {
                Mai70FileInfo(isDirectory = false, size = 0L, lastModifiedTime = Instant.EPOCH)
            }
        }
    }

    @Throws(IOException::class)
    fun deleteFile(path: Path) {
        val authority = path.authority
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) {
            throw IOException("Cannot delete directory $path")
        }
        val categoryName = segments[0]
        val fileName = segments[1]
        val category = Category.fromName(categoryName)
        val fileEntry = com.github.trunone.mai70protocol.models.FileEntry(
            category = category,
            path = categoryName,
            name = fileName,
            size = 0L
        )

        val mai70Client = getMai70Client(authority)

        kotlinx.coroutines.runBlocking {
            try {
                mai70Client.delete(fileEntry)
            } catch (e: Exception) {
                throw IOException("Failed to delete $path: ${e.message}", e)
            }
        }
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(IOException::class)
    fun retrieveFile(path: Path): InputStream {
        val authority = path.authority
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        val categoryName = segments.getOrNull(0) ?: ""
        val fileName = segments.getOrNull(1) ?: ""
        val category = Category.fromName(categoryName)
        val fileEntry = com.github.trunone.mai70protocol.models.FileEntry(
            category = category,
            path = categoryName,
            name = fileName,
            size = 0L
        )

        val mai70Client = getMai70Client(authority)

        return kotlinx.coroutines.runBlocking {
            try {
                mai70Client.openFile(fileEntry)
            } catch (e: Exception) {
                throw IOException("Failed to retrieve file $path: ${e.message}", e)
            }
        }
    }

    @Throws(IOException::class)
    fun openByteChannel(path: Path, isAppend: Boolean): SeekableByteChannel {
        val authority = path.authority
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        val categoryName = segments.getOrNull(0) ?: ""
        val fileName = segments.getOrNull(1) ?: ""

        val mai70Client = getMai70Client(authority)
        val candidateUrls = mai70Client.getFileUrlCandidates(categoryName, fileName)
        val fileSize = try { listFile(path).size } catch (e: Exception) { null }

        return FileByteChannel(httpClient, authority, candidateUrls, fileSize, isAppend)
    }

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }
}
