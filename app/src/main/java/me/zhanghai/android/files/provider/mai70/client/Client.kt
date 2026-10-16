/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70.client

import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.LocalWatchService
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java8.nio.file.Path as Java8Path

object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    val httpClient: OkHttpClient = OkHttpClient.Builder().build()

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

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun signParams(command: String, rawParams: Map<String, String>, token: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        for ((key, value) in rawParams) {
            val paramKey = if (key.startsWith("-")) key else "-$key"
            params[paramKey] = value
        }
        params["-timestamp"] = (System.currentTimeMillis() / 1000).toString()

        val paramsStr = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val hashStr = "$command?$paramsStr$token"
        params["-signkey"] = md5(hashStr)

        return params
    }

    @Throws(IOException::class)
    fun sendCgiCommand(authority: Authority, command: String, rawParams: Map<String, String> = emptyMap()): Any? {
        val token = authenticator.getToken(authority) ?: authority.token
        val signedParams = signParams(command, rawParams, token)

        val urlBuilder = HttpUrl.Builder()
            .scheme("http")
            .host(authority.host)
            .port(authority.port)
            .addPathSegment("cgi-bin")
            .addPathSegment(command)

        for ((k, v) in signedParams) {
            urlBuilder.addQueryParameter(k, v)
        }

        val request = Request.Builder().url(urlBuilder.build()).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP error ${response.code}: ${response.message}")
        }

        var responseText = response.body.string()
        // Fix potential trailing comma before ]} in 70mai JSON responses
        if (responseText.contains(",\n]}")) {
            responseText = responseText.replace(",\n]}", "]} ")
        } else if (responseText.contains(",]}")) {
            responseText = responseText.replace(",]}", "]} ")
        }

        val json = try {
            JSONObject(responseText)
        } catch (e: Exception) {
            throw IOException("Invalid JSON response from 70mai: $responseText", e)
        }

        val resultCode = json.optString("ResultCode", "-1")
        if (resultCode != "0") {
            throw IOException("70mai command $command failed with ResultCode: $resultCode")
        }

        if (json.isNull("Result")) {
            return null
        }

        val resultObj = json.get("Result")
        return resultObj
    }

    @Throws(IOException::class)
    fun getFileCounts(authority: Authority): Map<Int, Int> {
        val result = sendCgiCommand(authority, "getfilecount.cgi")
        val counts = mutableMapOf<Int, Int>()
        if (result is JSONArray) {
            for (i in 0 until result.length()) {
                val item = result.getJSONObject(i)
                val type = item.getString("type").toIntOrNull()
                val count = item.getString("count").toIntOrNull()
                if (type != null && count != null) {
                    counts[type] = count
                }
            }
        }
        return counts
    }

    @Throws(IOException::class)
    fun getFileEntries(authority: Authority, type: Int, count: Int): List<FileEntry> {
        if (count <= 0) return emptyList()
        val params = mapOf("type" to type.toString(), "start" to "1", "end" to count.toString())
        val result = sendCgiCommand(authority, "getfilelist.cgi", params)
        val entries = mutableListOf<FileEntry>()
        if (result is JSONArray) {
            for (i in 0 until result.length()) {
                val item = result.getJSONObject(i)
                val path = item.getString("path")
                val name = item.getString("name")
                var size = item.getString("size").toLongOrNull() ?: 0L
                if (size <= 0) {
                    size += (4L * 1024 * 1024 * 1024)
                }
                entries.add(FileEntry(path, name, size))
            }
        }
        return entries
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
            val counts = try {
                getFileCounts(authority)
            } catch (e: Exception) {
                emptyMap()
            }
            val count = counts[type] ?: 1000
            val fileEntries = getFileEntries(authority, type, count)
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
        val request = Request.Builder().url(fileUrl).head().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java8.nio.file.NoSuchFileException(path.toString())
            }
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

    @Throws(IOException::class)
    fun deleteFile(path: Path) {
        val authority = path.authority
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) {
            throw IOException("Cannot delete directory $path")
        }
        val category = segments[0]
        val name = segments[1]
        sendCgiCommand(authority, "delete.cgi", mapOf("path" to category, "name" to name))
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
