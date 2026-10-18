package com.github.trunone.mai70protocol

import com.github.trunone.mai70protocol.http.HttpTransport
import com.github.trunone.mai70protocol.models.Authority
import com.github.trunone.mai70protocol.models.Category
import com.github.trunone.mai70protocol.models.FileEntry
import com.github.trunone.mai70protocol.models.FileInfo
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Main façade client for interacting with 70mai dashcam.
 *
 * Provides pairing, client registration, album mode configuration, category file count,
 * pagination listing, download stream retrieval, file deletion, and metadata probing.
 *
 * @param transport HTTP transport implementation.
 * @param tokenStore Token persistence store.
 * @param host Camera IP address (default: "192.168.0.1").
 * @param port Camera HTTP port (default: 80).
 */
class Mai70Client(
    private val transport: HttpTransport,
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    private val host: String = "192.168.0.1",
    private val port: Int = 80
) {
    private val auth = Mai70Auth(transport, host, port)
    private val fileEntriesCache = ConcurrentHashMap<String, FileEntry>()

    /**
     * Gets or resolves authority instance for current host and port.
     */
    fun getAuthority(): Authority {
        val storedToken = tokenStore.getToken(Authority(host = host, port = port)) ?: ""
        return Authority(host = host, port = port, token = storedToken)
    }

    /**
     * Executes full pairing flow:
     * `BindByBanya` -> poll `UserconfirmByBanya` (waiting for camera button press) -> `client.cgi` register.
     *
     * Saves and returns active session token.
     */
    suspend fun pair(
        onWaitingForButton: suspend () -> Unit = {},
        maxAttempts: Int = 20,
        delayMs: Long = 5000
    ): String {
        val token = auth.pair(onWaitingForButton, maxAttempts, delayMs)
        val authority = Authority(host = host, port = port, token = token)
        tokenStore.saveToken(authority, token)
        return token
    }

    /**
     * Registers client IP with camera (`client.cgi`).
     */
    suspend fun register(clientIp: String = "192.168.0.2"): Boolean {
        val authority = getAuthority()
        return auth.register(authority, clientIp)
    }

    /**
     * Sets access album mode (`setaccessalbum.cgi`).
     *
     * Mode must be enabled on many 70mai camera models prior to listing or downloading.
     *
     * @param enable `true` to enable album access (pauses recording), `false` to disable.
     * @return `true` if camera confirmed album mode change.
     */
    suspend fun setAlbumMode(enable: Boolean): Boolean {
        val authority = getAuthority()
        val timestamp = System.currentTimeMillis() / 1000L
        val enableValue = if (enable) 0 else 1

        val queryPath = "setaccessalbum.cgi?"
        val queryParamsCandidates = listOf(
            "&-enable=$enableValue&-timestamp=$timestamp",
            "-enable=$enableValue&-timestamp=$timestamp"
        )

        for (queryParams in queryParamsCandidates) {
            val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)
            val responseText = transport.get(url)
            if (Mai70Protocol.isSuccessResponse(responseText)) {
                return true
            }
        }
        return false
    }

    /**
     * Prepares camera access by registering client and enabling album access mode.
     */
    suspend fun prepareCameraAccess() {
        try {
            register()
        } catch (_: Exception) {
            // Best effort
        }
        try {
            setAlbumMode(enable = true)
        } catch (_: Exception) {
            // Best effort
        }
    }

    /**
     * Gets remote file counts for each file category (`getfilecount.cgi`).
     */
    suspend fun getFileCounts(): Map<Category, Int> {
        prepareCameraAccess()
        val authority = getAuthority()
        val timestamp = System.currentTimeMillis() / 1000L
        val queryPath = "getfilecount.cgi?"
        val queryParams = "-timestamp=$timestamp"
        val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)

        val responseText = transport.get(url) ?: return emptyMap()
        return Mai70Protocol.parseFileCountsResponse(responseText)
    }

    /**
     * Fetches complete list of files in category using robust pagination and query format fallbacks (`getfilelist.cgi`).
     *
     * @param category Category to query.
     * @return List of [FileEntry] objects found.
     */
    suspend fun listFiles(category: Category): List<FileEntry> {
        prepareCameraAccess()
        val authority = getAuthority()
        val allEntries = mutableListOf<FileEntry>()
        var startIndex = 0
        val pageSize = 100

        while (true) {
            val timestamp = System.currentTimeMillis() / 1000L
            val queryPath = "getfilelist.cgi?"
            val queryParams = "-start=$startIndex&-end=${startIndex + pageSize - 1}&-type=${category.type}&-timestamp=$timestamp"
            val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)

            val responseText = transport.get(url)
            var batch = Mai70Protocol.parseFileListResponse(responseText, category)

            // Fallback strategy if initial batch on page 0 is empty
            if ((batch == null || batch.isEmpty()) && startIndex == 0) {
                val fallbackFormats = listOf(0, 1, 2, 3)
                for (format in fallbackFormats) {
                    val altTimestamp = System.currentTimeMillis() / 1000L
                    val altUrl = when (format) {
                        0 -> {
                            val qp = "&-start=0&-end=${pageSize - 1}&-type=${category.type}&-timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        1 -> {
                            val qp = "-start=0&-end=${pageSize - 1}&-timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        2 -> {
                            val qp = "start=0&end=${pageSize - 1}&type=${category.type}&timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        3 -> {
                            val qp = "-start=1&-end=$pageSize&-type=${category.type}&-timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        else -> continue
                    }

                    val altText = transport.get(altUrl) ?: continue
                    val altBatch = Mai70Protocol.parseFileListResponse(altText, category)
                    if (!altBatch.isNullOrEmpty()) {
                        batch = altBatch
                        break
                    }
                }
            }

            if (batch.isNullOrEmpty()) {
                break
            }

            for (entry in batch) {
                val fullRemotePath = "/${category.categoryName}/${entry.name}"
                val cacheKey = "$host:$port/${fullRemotePath.removePrefix("/")}"
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

    /**
     * Builds candidate HTTP download URLs for a file.
     *
     * @param categoryName Folder/category name (e.g. "Normal").
     * @param fileName File name (e.g. "NO20240101-120000-000100.MP4").
     * @param remotePath Optional remote directory path as reported by camera.
     */
    fun getFileUrlCandidates(
        categoryName: String,
        fileName: String,
        remotePath: String? = null
    ): List<String> {
        val candidates = mutableListOf<String>()
        val cleanCategory = categoryName.removePrefix("/").removeSuffix("/")
        val cacheKey = "$host:$port/$cleanCategory/$fileName"
        val cachedEntry = fileEntriesCache[cacheKey]

        candidates.add("http://$host:$port/$cleanCategory/$fileName")
        if (cachedEntry != null) {
            val entryPath = cachedEntry.path.removePrefix("/").removeSuffix("/")
            candidates.add("http://$host:$port/$entryPath/${cachedEntry.name}")
            candidates.add("http://$host:$port/sd/$entryPath/${cachedEntry.name}")
        } else if (!remotePath.isNullOrEmpty()) {
            val cleanRemotePath = remotePath.removePrefix("/").removeSuffix("/")
            candidates.add("http://$host:$port/$cleanRemotePath/$fileName")
            candidates.add("http://$host:$port/sd/$cleanRemotePath/$fileName")
        }
        candidates.add("http://$host:$port/sd/$cleanCategory/$fileName")
        candidates.add("http://$host:$port/$fileName")

        return candidates.distinct()
    }

    /**
     * Builds candidate HTTP thumbnail URLs for a file.
     *
     * Generates signed CGI thumbnail endpoint URLs as well as direct static HTTP thumbnail file candidates.
     *
     * @param categoryName Folder/category name (e.g. "Normal").
     * @param fileName File name (e.g. "NO20240101-120000-000100.MP4").
     * @param remotePath Optional remote directory path as reported by camera.
     * @return List of distinct candidate URLs for retrieving the file's thumbnail.
     */
    fun getThumbnailUrlCandidates(
        categoryName: String,
        fileName: String,
        remotePath: String? = null
    ): List<String> {
        val candidates = mutableListOf<String>()
        val cleanCategory = categoryName.removePrefix("/").removeSuffix("/")
        val cacheKey = "$host:$port/$cleanCategory/$fileName"
        val cachedEntry = fileEntriesCache[cacheKey]

        val authority = getAuthority()
        val timestamp = System.currentTimeMillis() / 1000L

        val paths = mutableListOf<String>()
        if (cachedEntry != null) {
            paths.add(cachedEntry.path.removePrefix("/").removeSuffix("/"))
        }
        if (!remotePath.isNullOrEmpty()) {
            paths.add(remotePath.removePrefix("/").removeSuffix("/"))
        }
        paths.add(cleanCategory)
        paths.add("sd/$cleanCategory")
        paths.add("")

        val distinctPaths = paths.distinct()

        // 1. Signed CGI endpoints
        val cgiPaths = listOf("getthumbnail.cgi?", "getthumb.cgi?", "getfilethumb.cgi?")
        for (p in distinctPaths) {
            for (cgi in cgiPaths) {
                if (p.isNotEmpty()) {
                    candidates.add(Mai70Protocol.buildCgiUrl(authority, cgi, "-path=$p&-name=$fileName&-timestamp=$timestamp"))
                } else {
                    candidates.add(Mai70Protocol.buildCgiUrl(authority, cgi, "-name=$fileName&-timestamp=$timestamp"))
                }
            }
        }

        // 2. Direct static HTTP candidates
        val baseName = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        val thmNameCandidates = listOf(
            "$baseName.THM",
            "$baseName.thm",
            "$baseName.JPG",
            "$baseName.jpg",
            "${baseName}_THUMB.JPG",
            "${baseName}_thm.jpg",
            fileName
        ).distinct()

        for (p in distinctPaths) {
            val prefix = if (p.isEmpty()) "" else "$p/"
            for (thmName in thmNameCandidates) {
                candidates.add("http://$host:$port/${prefix}THUMB/$thmName")
                candidates.add("http://$host:$port/${prefix}.THUMB/$thmName")
                candidates.add("http://$host:$port/$prefix$thmName")
            }
        }

        return candidates.distinct()
    }

    /**
     * Opens an [InputStream] to download entry contents.
     *
     * Tries candidate URLs in order.
     */
    suspend fun openFile(entry: FileEntry): InputStream {
        prepareCameraAccess()
        val candidates = getFileUrlCandidates(entry.category.categoryName, entry.name, entry.path)
        for (url in candidates) {
            val stream = transport.openStream(url)
            if (stream != null) {
                return stream
            }
        }
        throw Mai70FileNotFoundException("Failed to open remote file ${entry.name} from candidates: $candidates")
    }

    /**
     * Opens an [InputStream] to retrieve thumbnail contents for a given file entry.
     *
     * Prepares camera access and tries candidate thumbnail URLs sequentially.
     *
     * @param entry Target file entry.
     * @return Open [InputStream] with thumbnail image data.
     * @throws Mai70FileNotFoundException if thumbnail could not be opened from any candidate URL.
     */
    suspend fun getThumbnail(entry: FileEntry): InputStream {
        return getThumbnail(entry.category.categoryName, entry.name, entry.path)
    }

    /**
     * Opens an [InputStream] to retrieve thumbnail contents for a category name and file name.
     *
     * Prepares camera access and tries candidate thumbnail URLs sequentially.
     *
     * @param categoryName Category or folder name (e.g. "Normal").
     * @param fileName File name (e.g. "NO20240101-120000-000100.MP4").
     * @param remotePath Optional remote directory path as reported by camera.
     * @return Open [InputStream] with thumbnail image data.
     * @throws Mai70FileNotFoundException if thumbnail could not be opened from any candidate URL.
     */
    suspend fun getThumbnail(
        categoryName: String,
        fileName: String,
        remotePath: String? = null
    ): InputStream {
        prepareCameraAccess()
        val candidates = getThumbnailUrlCandidates(categoryName, fileName, remotePath)
        for (url in candidates) {
            val stream = transport.openStream(url) ?: continue
            val headerBuffer = ByteArray(512)
            val initialBytesRead = try {
                stream.read(headerBuffer)
            } catch (_: Exception) {
                stream.close()
                continue
            }

            if (initialBytesRead <= 0) {
                stream.close()
                continue
            }

            val headerText = String(headerBuffer, 0, initialBytesRead, Charsets.UTF_8)
            if (headerText.trimStart().startsWith("{") &&
                (headerText.contains("ResultCode") || headerText.contains("status") || headerText.contains("Result"))
            ) {
                stream.close()
                continue
            }

            val prefixStream = java.io.ByteArrayInputStream(headerBuffer, 0, initialBytesRead)
            return java.io.SequenceInputStream(prefixStream, stream)
        }
        throw Mai70FileNotFoundException("Failed to open thumbnail for $fileName from candidates: $candidates")
    }

    /**
     * Probes remote file metadata (size, timestamp) for a category and file name.
     */
    suspend fun getFileInfo(categoryName: String, fileName: String): FileInfo {
        val cacheKey = "$host:$port/${categoryName.removePrefix("/")}/$fileName"
        val cachedEntry = fileEntriesCache[cacheKey]
        if (cachedEntry != null) {
            return FileInfo(isDirectory = false, size = cachedEntry.size, lastModifiedTime = Instant.EPOCH)
        }

        val candidates = getFileUrlCandidates(categoryName, fileName)
        for (url in candidates) {
            val probeResult = transport.probe(url)
            if (probeResult != null) {
                val (size, lastModifiedHeader) = probeResult
                val lastModifiedTime = if (lastModifiedHeader != null) {
                    try {
                        DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedHeader, Instant::from)
                    } catch (_: Exception) {
                        Instant.EPOCH
                    }
                } else {
                    Instant.EPOCH
                }
                return FileInfo(isDirectory = false, size = size, lastModifiedTime = lastModifiedTime)
            }
        }

        return FileInfo(isDirectory = false, size = 0L, lastModifiedTime = Instant.EPOCH)
    }

    /**
     * Deletes remote file using path candidates and fallback query formats (`delete.cgi`).
     *
     * @param entry Target file entry to delete.
     * @throws Mai70Exception if deletion is rejected across all path candidates.
     */
    suspend fun delete(entry: FileEntry) {
        prepareCameraAccess()
        val authority = getAuthority()
        val categoryName = entry.category.categoryName
        val fileName = entry.name
        val type = entry.category.type

        val cacheKey = "$host:$port/$categoryName/$fileName"
        val cachedEntry = fileEntriesCache[cacheKey]

        val pathCandidates = mutableListOf<String>()
        if (cachedEntry != null) {
            pathCandidates.add(cachedEntry.path)
            pathCandidates.add(cachedEntry.path.removePrefix("/"))
            pathCandidates.add("sd/${cachedEntry.path.removePrefix("/")}")
            pathCandidates.add("/${cachedEntry.path.removePrefix("/")}")
        }
        pathCandidates.add(entry.path)
        pathCandidates.add(entry.path.removePrefix("/"))
        pathCandidates.add(categoryName)
        pathCandidates.add("sd/$categoryName")
        pathCandidates.add("/$categoryName")
        pathCandidates.add("/sd/$categoryName")
        pathCandidates.add("/mnt/$categoryName")
        pathCandidates.add("/mnt/sd/$categoryName")
        pathCandidates.add("")

        val distinctCandidates = pathCandidates.distinct()
        var deleted = false

        for (p in distinctCandidates) {
            val timestamp = System.currentTimeMillis() / 1000L
            val queryPath = "delete.cgi?"
            val queryParamsList = mutableListOf<String>()
            queryParamsList.add("-path=$p&-name=$fileName&-timestamp=$timestamp")
            queryParamsList.add("&-path=$p&-name=$fileName&-timestamp=$timestamp")
            if (type >= 0) {
                queryParamsList.add("-path=$p&-name=$fileName&-type=$type&-timestamp=$timestamp")
                queryParamsList.add("-type=$type&-name=$fileName&-timestamp=$timestamp")
            }

            for (queryParams in queryParamsList) {
                val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)
                val responseText = transport.get(url)
                if (responseText != null && Mai70Protocol.isSuccessResponse(responseText)) {
                    deleted = true
                    break
                }
            }
            if (deleted) break
        }

        if (!deleted) {
            throw Mai70Exception("Failed to delete $fileName from 70mai camera")
        }

        fileEntriesCache.remove(cacheKey)
    }
}
