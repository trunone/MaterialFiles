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
    fun getFileCounts(authority: Authority): Map<Int, Int> {
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

    @Throws(IOException::class)
    fun listFile(path: Path): Mai70FileInfo {
        val remotePath = path.remotePath
        val segments = remotePath.split('/').filter { it.isNotEmpty() }

        if (segments.isEmpty() || (segments.size == 1 && CATEGORY_TYPES.containsKey(segments[0].lowercase(Locale.ROOT)))) {
            return Mai70FileInfo(isDirectory = true, size = 0L, lastModifiedTime = Instant.EPOCH)
        }

        val fileUrl = "http://${path.authority.host}:${path.authority.port}/${remotePath.removePrefix("/")}"

        // Try HEAD first, with GET Range fallback if HEAD is unsupported by dashcam server
        try {
            val request = Request.Builder().url(fileUrl).head().build()
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
            // Ignored, proceed to GET fallback
        }

        // Fallback: GET with Range bytes=0-0
        val request = Request.Builder().url(fileUrl).header("Range", "bytes=0-0").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 416) {
                throw java8.nio.file.NoSuchFileException(path.toString())
            }
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

    @Throws(IOException::class)
    fun deleteFile(path: Path) {
        val authority = path.authority
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
        val fileUrl = "http://${path.authority.host}:${path.authority.port}/${path.remotePath.removePrefix("/")}"
        val request = Request.Builder().url(fileUrl).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        return response.body.byteStream()
    }

    @Throws(IOException::class)
    fun openByteChannel(path: Path, isAppend: Boolean): SeekableByteChannel {
        val fileUrl = "http://${path.authority.host}:${path.authority.port}/${path.remotePath.removePrefix("/")}"
        return FileByteChannel(httpClient, fileUrl, isAppend)
    }

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }
}
