/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70.client

import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.LocalWatchService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    val CATEGORY_NAMES = mapOf(
        0 to "Normal",
        1 to "Event",
        2 to "Parking",
        3 to "Photo",
        4 to "Unknown",
        5 to "TimeLapse"
    )

    val CATEGORY_TYPES = CATEGORY_NAMES.entries.associate { (k, v) -> v.lowercase(Locale.ROOT) to k }

    private val fileEntriesCache = ConcurrentHashMap<String, FileEntry>()

    data class Mai70FileInfo(
        val isDirectory: Boolean,
        val size: Long,
        val lastModifiedTime: Instant
    )

    data class FileEntry(
        val path: String,
        val name: String,
        val size: Long
    )

    fun calculateMD5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    fun generateSignKey(urlQuery: String, token: String): String =
        calculateMD5(urlQuery + token)

    @Throws(IOException::class)
    fun executeRawRequest(url: String): String? {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            var text = response.body.string()
            if (text.contains(",\n]}")) {
                text = text.replace(",\n]}", "]} ")
            } else if (text.contains(",]}")) {
                text = text.replace(",]}", "]} ")
            }
            return text
        }
    }

    @Throws(IOException::class)
    fun registerClient(authority: Authority): Boolean {
        val token = authenticator.getToken(authority) ?: authority.token
        val timestamp = System.currentTimeMillis() / 1000L
        val queryPath = "client.cgi?"
        val queryParams = "&-operation=register&-ip=192.168.0.2&-timestamp=$timestamp"
        val sign = generateSignKey(queryPath + queryParams, token)
        val url = "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams&-signkey=$sign"

        val responseText = executeRawRequest(url)
        return responseText != null && (
            responseText.contains("success") ||
            responseText.contains("\"ResultCode\":0") ||
            responseText.contains("\"ResultCode\":\"0\"")
        )
    }

    @Throws(IOException::class)
    fun setAlbumMode(authority: Authority, enable: Boolean): Boolean {
        val token = authenticator.getToken(authority) ?: authority.token
        val timestamp = System.currentTimeMillis() / 1000L
        val enableValue = if (enable) 0 else 1

        val queryPath = "setaccessalbum.cgi?"
        val queryParamsCandidates = listOf(
            "&-enable=$enableValue&-timestamp=$timestamp",
            "-enable=$enableValue&-timestamp=$timestamp"
        )

        for (queryParams in queryParamsCandidates) {
            val sign = generateSignKey(queryPath + queryParams, token)
            val url = "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams&-signkey=$sign"

            val responseText = executeRawRequest(url)
            if (responseText != null && (
                responseText.contains("\"ResultCode\":0") ||
                responseText.contains("\"ResultCode\":\"0\"")
            )) {
                return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    fun prepareCameraAccess(authority: Authority) {
        try {
            registerClient(authority)
        } catch (e: Exception) {
            // Best effort
        }
        try {
            setAlbumMode(authority, enable = true)
        } catch (e: Exception) {
            // Best effort
        }
    }

    @Throws(IOException::class)
    fun getFileCounts(authority: Authority): Map<Int, Int> {
        prepareCameraAccess(authority)
        val token = authenticator.getToken(authority) ?: authority.token
        val timestamp = System.currentTimeMillis() / 1000L
        val queryPath = "getfilecount.cgi?"
        val queryParams = "-timestamp=$timestamp"
        val sign = generateSignKey(queryPath + queryParams, token)
        val url = "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams&-signkey=$sign"

        val responseText = executeRawRequest(url) ?: return emptyMap()
        val counts = mutableMapOf<Int, Int>()
        try {
            val json = JSONObject(responseText)
            val resultCode = json.optString("ResultCode", "-1")
            if (resultCode == "0" && !json.isNull("Result")) {
                val result = json.getJSONArray("Result")
                for (i in 0 until result.length()) {
                    val item = result.getJSONObject(i)
                    val type = item.optString("type").toIntOrNull()
                    val count = item.optString("count").toIntOrNull()
                    if (type != null && count != null) {
                        counts[type] = count
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return counts
    }

    @Throws(IOException::class)
    fun getFileEntries(authority: Authority, type: Int): List<FileEntry> {
        prepareCameraAccess(authority)
        val token = authenticator.getToken(authority) ?: authority.token
        val allEntries = mutableListOf<FileEntry>()
        var startIndex = 0
        val pageSize = 100

        while (true) {
            val timestamp = System.currentTimeMillis() / 1000L
            val queryPath = "getfilelist.cgi?"
            val queryParams = "-start=$startIndex&-end=${startIndex + pageSize - 1}&-type=$type&-timestamp=$timestamp"
            val sign = generateSignKey(queryPath + queryParams, token)
            var url = "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams&-signkey=$sign"

            var responseText = executeRawRequest(url)
            var batch = parseFileEntriesResponse(responseText)

            // If Result is empty or failed on first page, try fallback query formats
            if ((batch == null || batch.isEmpty()) && startIndex == 0) {
                val fallbackFormats = listOf(0, 1, 2, 3)
                for (format in fallbackFormats) {
                    val altTimestamp = System.currentTimeMillis() / 1000L
                    val altUrl = when (format) {
                        0 -> {
                            val qp = "-start=0&-end=${pageSize - 1}&-type=$type&-timestamp=$altTimestamp"
                            val s = generateSignKey("getfilelist.cgi?" + qp, token)
                            "http://${authority.host}:${authority.port}/cgi-bin/getfilelist.cgi?$qp&-signkey=$s"
                        }
                        1 -> {
                            val qp = "-start=0&-end=${pageSize - 1}&-timestamp=$altTimestamp"
                            val s = generateSignKey("getfilelist.cgi?" + qp, token)
                            "http://${authority.host}:${authority.port}/cgi-bin/getfilelist.cgi?$qp&-signkey=$s"
                        }
                        2 -> {
                            val qp = "start=0&end=${pageSize - 1}&type=$type&timestamp=$altTimestamp"
                            val s = generateSignKey("getfilelist.cgi?" + qp, token)
                            "http://${authority.host}:${authority.port}/cgi-bin/getfilelist.cgi?$qp&signkey=$s"
                        }
                        3 -> {
                            val qp = "-start=1&-end=$pageSize&-type=$type&-timestamp=$altTimestamp"
                            val s = generateSignKey("getfilelist.cgi?" + qp, token)
                            "http://${authority.host}:${authority.port}/cgi-bin/getfilelist.cgi?$qp&-signkey=$s"
                        }
                        else -> continue
                    }

                    val altText = executeRawRequest(altUrl) ?: continue
                    val altBatch = parseFileEntriesResponse(altText)
                    if (altBatch != null && altBatch.isNotEmpty()) {
                        batch = altBatch
                        responseText = altText
                        break
                    }
                }
            }

            if (batch == null || batch.isEmpty()) {
                break
            }

            for (entry in batch) {
                val categoryName = CATEGORY_NAMES[type] ?: entry.path
                val fullRemotePath = "/$categoryName/${entry.name}"
                val cacheKey = "${authority.host}:${authority.port}/${fullRemotePath.removePrefix("/")}"
                fileEntriesCache[cacheKey] = entry
            }

            allEntries.addAll(batch)
            if (batch.size < pageSize) {
                break
            }

            startIndex += pageSize
        }

        return allEntries
    }

    private fun parseFileEntriesResponse(responseText: String?): List<FileEntry>? {
        if (responseText.isNullOrEmpty()) return null
        return try {
            val json = JSONObject(responseText)
            val resultCode = json.optString("ResultCode", "-1")
            if (resultCode != "0" || json.isNull("Result")) return null
            val result = json.getJSONArray("Result")
            val entries = mutableListOf<FileEntry>()
            for (i in 0 until result.length()) {
                val item = result.getJSONObject(i)
                val path = item.optString("path")
                val name = item.optString("name")
                var size = item.optString("size").toLongOrNull() ?: 0L
                if (size < 0) {
                    size += (4L * 1024 * 1024 * 1024)
                }
                entries.add(FileEntry(path, name, size))
            }
            entries
        } catch (e: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    fun listDirectory(path: Path): List<Path> {
        val authority = path.authority
        val remotePath = path.remotePath
        val segments = remotePath.split('/').filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            // Root directory: return category subdirectories
            val counts = try {
                getFileCounts(authority)
            } catch (e: Exception) {
                emptyMap()
            }
            val result = mutableListOf<Path>()
            for ((type, name) in CATEGORY_NAMES) {
                val count = counts[type] ?: -1
                if (count != 0) {
                    result.add(path.resolve(name))
                }
            }
            return result
        } else if (segments.size == 1) {
            // Category directory (e.g. /Normal)
            val categoryName = segments[0]
            val type = CATEGORY_TYPES[categoryName.lowercase(Locale.ROOT)]
                ?: throw IOException("Unknown category directory: $categoryName")
            val fileEntries = getFileEntries(authority, type)
            return fileEntries.map { entry ->
                path.resolve(entry.name)
            }
        } else {
            return emptyList()
        }
    }

    private fun getFileUrlCandidates(path: Path): List<String> {
        val authority = path.authority
        val cacheKey = "${authority.host}:${authority.port}/${path.remotePath.removePrefix("/")}"
        val cachedEntry = fileEntriesCache[cacheKey]
        val remotePath = path.remotePath.removePrefix("/")
        val fileName = path.remotePath.substringAfterLast("/")

        val candidates = mutableListOf<String>()
        candidates.add("http://${authority.host}:${authority.port}/$remotePath")
        if (cachedEntry != null) {
            val entryPath = cachedEntry.path.removePrefix("/")
            candidates.add("http://${authority.host}:${authority.port}/$entryPath/${cachedEntry.name}")
            candidates.add("http://${authority.host}:${authority.port}/sd/$entryPath/${cachedEntry.name}")
        }
        candidates.add("http://${authority.host}:${authority.port}/sd/$remotePath")
        candidates.add("http://${authority.host}:${authority.port}/$fileName")
        return candidates.distinct()
    }

    @Throws(IOException::class)
    fun listFile(path: Path): Mai70FileInfo {
        val remotePath = path.remotePath
        val segments = remotePath.split('/').filter { it.isNotEmpty() }

        if (segments.isEmpty() || (segments.size == 1 && CATEGORY_TYPES.containsKey(segments[0].lowercase(Locale.ROOT)))) {
            return Mai70FileInfo(isDirectory = true, size = 0L, lastModifiedTime = Instant.EPOCH)
        }

        val cacheKey = "${path.authority.host}:${path.authority.port}/${remotePath.removePrefix("/")}"
        val cachedEntry = fileEntriesCache[cacheKey]
        if (cachedEntry != null) {
            return Mai70FileInfo(isDirectory = false, size = cachedEntry.size, lastModifiedTime = Instant.EPOCH)
        }

        // If not in cache, try candidate URLs with HEAD/GET Range
        val candidateUrls = getFileUrlCandidates(path)
        for (url in candidateUrls) {
            try {
                val request = Request.Builder().url(url).head().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        val lastModifiedHeader = response.header("Last-Modified")
                        val lastModifiedTime = if (lastModifiedHeader != null) {
                            try {
                                DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedHeader, Instant::from)
                            } catch (e: Exception) {
                                Instant.EPOCH
                            }
                        } else {
                            Instant.EPOCH
                        }
                        return Mai70FileInfo(isDirectory = false, size = size, lastModifiedTime = lastModifiedTime)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            try {
                val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 416) {
                        val contentRange = response.header("Content-Range")
                        val size = if (contentRange != null && contentRange.contains("/")) {
                            contentRange.substringAfter("/").toLongOrNull() ?: 0L
                        } else {
                            response.header("Content-Length")?.toLongOrNull() ?: 0L
                        }
                        val lastModifiedHeader = response.header("Last-Modified")
                        val lastModifiedTime = if (lastModifiedHeader != null) {
                            try {
                                DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedHeader, Instant::from)
                            } catch (e: Exception) {
                                Instant.EPOCH
                            }
                        } else {
                            Instant.EPOCH
                        }
                        return Mai70FileInfo(isDirectory = false, size = size, lastModifiedTime = lastModifiedTime)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Default fallback rather than crashing with NoSuchFileException
        return Mai70FileInfo(isDirectory = false, size = 0L, lastModifiedTime = Instant.EPOCH)
    }

    @Throws(IOException::class)
    fun deleteFile(path: Path) {
        val authority = path.authority
        prepareCameraAccess(authority)
        val token = authenticator.getToken(authority) ?: authority.token
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) {
            throw IOException("Cannot delete directory $path")
        }
        val rawCategory = segments[0]
        val name = segments[1]

        val pathCandidates = listOf(
            rawCategory,
            "/$rawCategory",
            rawCategory.removePrefix("/"),
            "/mnt/${rawCategory.removePrefix("/")}",
            "$rawCategory/"
        ).distinct()

        var deleted = false
        for (p in pathCandidates) {
            val timestamp = System.currentTimeMillis() / 1000L
            val queryPath = "delete.cgi?"
            val queryParams = "-path=$p&-name=$name&-timestamp=$timestamp"
            val sign = generateSignKey(queryPath + queryParams, token)
            val url = "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams&-signkey=$sign"

            val responseText = executeRawRequest(url)
            if (responseText != null && (
                responseText.contains("\"ResultCode\":\"0\"") ||
                responseText.contains("\"ResultCode\":0") ||
                responseText.contains("success")
            )) {
                deleted = true
                break
            }
        }

        if (!deleted) {
            throw IOException("Failed to delete $path from 70mai server")
        }

        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(IOException::class)
    fun retrieveFile(path: Path): InputStream {
        val candidateUrls = getFileUrlCandidates(path)
        for (url in candidateUrls) {
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return response.body.byteStream()
                }
                response.close()
            } catch (e: Exception) {
                // Try next
            }
        }
        throw IOException("Failed to retrieve file $path from candidate URLs: $candidateUrls")
    }

    @Throws(IOException::class)
    fun openByteChannel(path: Path, isAppend: Boolean): SeekableByteChannel {
        val candidateUrls = getFileUrlCandidates(path)
        val workingUrl = candidateUrls.first()
        return FileByteChannel(httpClient, workingUrl, isAppend)
    }

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }
}
