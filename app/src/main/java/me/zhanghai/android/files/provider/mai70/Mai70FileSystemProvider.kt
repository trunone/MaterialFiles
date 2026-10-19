/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import com.github.trunone.mai70protocol.Mai70Client
import com.github.trunone.mai70protocol.TokenStore
import com.github.trunone.mai70protocol.android.AndroidNetworkTransport
import com.github.trunone.mai70protocol.models.Authority
import com.github.trunone.mai70protocol.models.Category
import com.github.trunone.mai70protocol.models.FileEntry
import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.EMPTY
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.WatchServicePathObservable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.readFully
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toCopyOptions
import me.zhanghai.android.files.provider.common.toLinkOptions
import me.zhanghai.android.files.provider.common.toOpenOptions
import me.zhanghai.android.files.storage.Mai70ServerAuthenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java8.nio.file.Path as Java8Path

object Mai70FileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private val HIDDEN_FILE_NAME_PREFIX = ".".toByteString()

    private val fileSystems = mutableMapOf<Authority, Mai70FileSystem>()

    private val lock = Any()

    private val clients = ConcurrentHashMap<Authority, Mai70Client>()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val tokenStore = object : TokenStore {
        override fun getToken(authority: Authority): String? =
            Mai70ServerAuthenticator.getToken(authority)

        override fun saveToken(authority: Authority, token: String) {}
    }

    fun getMai70Client(authority: Authority): Mai70Client =
        clients.getOrPut(authority) {
            val transport = AndroidNetworkTransport(
                context = application,
                defaultCameraIp = authority.host
            )
            Mai70Client(
                transport = transport,
                tokenStore = tokenStore,
                host = authority.host,
                port = authority.port
            )
        }

    override fun getScheme(): String = "mai70"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val authority = uri.mai70Authority
        synchronized(lock) {
            if (fileSystems[authority] != null) {
                throw FileSystemAlreadyExistsException(authority.toString())
            }
            return newFileSystemLocked(authority)
        }
    }

    internal fun getOrNewFileSystem(authority: Authority): Mai70FileSystem =
        synchronized(lock) { fileSystems[authority] ?: newFileSystemLocked(authority) }

    private fun newFileSystemLocked(authority: Authority): Mai70FileSystem {
        val fileSystem = Mai70FileSystem(this, authority)
        fileSystems[authority] = fileSystem
        return fileSystem
    }

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val authority = uri.mai70Authority
        return synchronized(lock) { fileSystems[authority] }
            ?: throw FileSystemNotFoundException(authority.toString())
    }

    internal fun removeFileSystem(fileSystem: Mai70FileSystem) {
        val authority = fileSystem.authority
        synchronized(lock) { fileSystems.remove(authority) }
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val authority = uri.mai70Authority
        val path = uri.decodedPathByteString
            ?: throw IllegalArgumentException("URI must have a path")
        return getOrNewFileSystem(authority).getPath(path)
    }

    private fun URI.requireSameScheme() {
        require(scheme.equals("mai70", ignoreCase = true)) { "URI scheme $scheme must be mai70" }
    }

    private val URI.mai70Authority: Authority
        get() {
            val defaultPort = 80
            val uriPort = if (port != -1) port else defaultPort
            val token = userInfo.orEmpty()
            return Authority(host, uriPort, token)
        }

    @Throws(IOException::class)
    override fun newInputStream(file: Path, vararg options: OpenOption): InputStream {
        file as? Mai70Path ?: throw ProviderMismatchException(file.toString())
        val openOptions = options.toOpenOptions()
        openOptions.checkForMai70()
        if (openOptions.write) {
            throw UnsupportedOperationException(StandardOpenOption.WRITE.toString())
        }
        if (openOptions.append) {
            throw UnsupportedOperationException(StandardOpenOption.APPEND.toString())
        }
        if (openOptions.truncateExisting) {
            throw UnsupportedOperationException(StandardOpenOption.TRUNCATE_EXISTING.toString())
        }

        val segments = file.remotePath.split('/').filter { it.isNotEmpty() }
        val categoryName = segments.getOrNull(0) ?: ""
        val fileName = segments.getOrNull(1) ?: ""
        val category = Category.fromName(categoryName)
        val fileEntry = FileEntry(
            category = category,
            path = categoryName,
            name = fileName,
            size = 0L
        )
        val client = getMai70Client(file.authority)

        return runBlocking {
            try {
                client.openFile(fileEntry)
            } catch (e: Exception) {
                throw IOException("Failed to open input stream for $file: ${e.message}", e)
            }
        }
    }

    @Throws(IOException::class)
    override fun newOutputStream(file: Path, vararg options: OpenOption): OutputStream {
        file as? Mai70Path ?: throw ProviderMismatchException(file.toString())
        throw UnsupportedOperationException("Writing is not supported on 70mai server")
    }

    @Throws(IOException::class)
    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        file as? Mai70Path ?: throw ProviderMismatchException(file.toString())
        options.toOpenOptions().checkForMai70()
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        file as? Mai70Path ?: throw ProviderMismatchException(file.toString())
        val openOptions = options.toOpenOptions()
        openOptions.checkForMai70()
        if (openOptions.write) {
            throw UnsupportedOperationException(StandardOpenOption.WRITE.toString())
        }
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }

        val segments = file.remotePath.split('/').filter { it.isNotEmpty() }
        val categoryName = segments.getOrNull(0) ?: ""
        val fileName = segments.getOrNull(1) ?: ""
        val client = getMai70Client(file.authority)
        val candidateUrls = client.getFileUrlCandidates(categoryName, fileName)
        val fileSize = try {
            runBlocking { client.getFileInfo(categoryName, fileName).size }
        } catch (_: Exception) {
            null
        }

        return FileByteChannel(okHttpClient, file.authority, candidateUrls, fileSize, openOptions.append)
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        directory as? Mai70Path ?: throw ProviderMismatchException(directory.toString())
        val authority = directory.authority
        val remotePath = directory.remotePath
        val segments = remotePath.split('/').filter { it.isNotEmpty() }
        val client = getMai70Client(authority)

        val paths = runBlocking {
            if (segments.isEmpty()) {
                val counts = try {
                    client.getFileCounts()
                } catch (e: Exception) {
                    emptyMap()
                }
                val result = mutableListOf<Path>()
                for (category in Category.entries) {
                    if (category == Category.UNKNOWN) continue
                    val count = counts[category] ?: -1
                    if (count != 0) {
                        result.add(directory.resolve(category.categoryName))
                    }
                }
                result
            } else if (segments.size == 1) {
                val categoryName = segments[0]
                val category = Category.fromName(categoryName)
                val fileEntries = client.listFiles(category)
                fileEntries.map { entry -> directory.resolve(entry.name) }
            } else {
                emptyList()
            }
        }
        return PathListDirectoryStream(paths, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        directory as? Mai70Path ?: throw ProviderMismatchException(directory.toString())
        throw UnsupportedOperationException("Creating directory is not supported on 70mai server")
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        link as? Mai70Path ?: throw ProviderMismatchException(link.toString())
        throw UnsupportedOperationException()
    }

    override fun createLink(link: Path, existing: Path) {
        link as? Mai70Path ?: throw ProviderMismatchException(link.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) {
            throw IOException("Cannot delete directory $path")
        }
        val categoryName = segments[0]
        val fileName = segments[1]
        val category = Category.fromName(categoryName)
        val fileEntry = FileEntry(
            category = category,
            path = categoryName,
            name = fileName,
            size = 0L
        )
        val client = getMai70Client(path.authority)

        runBlocking {
            try {
                client.delete(fileEntry)
            } catch (e: Exception) {
                throw IOException("Failed to delete $path: ${e.message}", e)
            }
        }
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    override fun readSymbolicLink(link: Path): Path {
        link as? Mai70Path ?: throw ProviderMismatchException(link.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        source as? Mai70Path ?: throw ProviderMismatchException(source.toString())
        target as? Mai70Path ?: throw ProviderMismatchException(target.toString())
        val copyOptions = options.toCopyOptions()
        Mai70CopyMove.copy(source, target, copyOptions)
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? Mai70Path ?: throw ProviderMismatchException(source.toString())
        target as? Mai70Path ?: throw ProviderMismatchException(target.toString())
        val copyOptions = options.toCopyOptions()
        Mai70CopyMove.move(source, target, copyOptions)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        return path == path2
    }

    override fun isHidden(path: Path): Boolean {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        val fileName = path.fileNameByteString ?: return false
        return fileName.startsWith(HIDDEN_FILE_NAME_PREFIX)
    }

    override fun getFileStore(path: Path): FileStore {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        val accessModes = modes.toAccessModes()
        if (accessModes.write) {
            throw UnsupportedOperationException(AccessMode.WRITE.toString())
        }
        if (accessModes.execute) {
            throw UnsupportedOperationException(AccessMode.EXECUTE.toString())
        }
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        if (segments.size >= 2) {
            val categoryName = segments[0]
            val fileName = segments[1]
            val client = getMai70Client(path.authority)
            runBlocking {
                try {
                    client.getFileInfo(categoryName, fileName)
                } catch (e: Exception) {
                    throw IOException(e)
                }
            }
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, *options) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(Mai70FileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        if (!type.isAssignableFrom(BasicFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, *options).readAttributes() as A
    }

    private fun getFileAttributeView(path: Path, vararg options: LinkOption): Mai70FileAttributeView {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        val linkOptions = options.toLinkOptions()
        return Mai70FileAttributeView(path, linkOptions.noFollowLinks)
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        path as? Mai70Path ?: throw ProviderMismatchException(path.toString())
        return WatchServicePathObservable(path, intervalMillis)
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? Mai70Path ?: throw ProviderMismatchException(directory.toString())
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }

    private class FileByteChannel(
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

                    if (response.code == 403 && !attemptedReauth) {
                        response.close()
                        attemptedReauth = true
                        try {
                            val mai70Client = getMai70Client(authority)
                            runBlocking { mai70Client.prepareCameraAccess() }
                        } catch (_: Exception) {}
                        continue
                    }

                    if (response.code == 416) {
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
                        if (response.isSuccessful || response.code == 416) {
                            val contentRange = response.header("Content-Range")
                            if (contentRange != null && contentRange.contains("/")) {
                                contentRange.substringAfter("/").toLongOrNull()?.let { return it }
                            }
                            response.header("Content-Length")?.toLongOrNull()?.let { return it }
                        }
                    }
                } catch (_: Exception) {}
            }

            return fileSize ?: 0L
        }

        @Throws(IOException::class)
        override fun onClose() {}
    }
}
