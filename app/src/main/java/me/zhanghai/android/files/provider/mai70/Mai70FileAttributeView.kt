/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import com.github.trunone.mai70protocol.models.Category
import com.github.trunone.mai70protocol.models.FileInfo
import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.FileTime
import kotlinx.coroutines.runBlocking
import java.io.IOException

internal class Mai70FileAttributeView(
    private val path: Mai70Path,
    private val noFollowLinks: Boolean
) : BasicFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): Mai70FileAttributes {
        val segments = path.remotePath.split('/').filter { it.isNotEmpty() }
        val isDirectory = segments.isEmpty() || (segments.size == 1 && Category.fromName(segments[0]) != Category.UNKNOWN)

        val fileInfo = if (isDirectory) {
            FileInfo(isDirectory = true, size = 0L)
        } else {
            val categoryName = segments.getOrNull(0) ?: ""
            val fileName = segments.getOrNull(1) ?: ""
            val client = Mai70FileSystemProvider.getMai70Client(path.authority)
            runBlocking {
                try {
                    client.getFileInfo(categoryName, fileName)
                } catch (e: Exception) {
                    FileInfo(isDirectory = false, size = 0L)
                }
            }
        }
        return Mai70FileAttributes.from(fileInfo, path)
    }

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ) {
        throw UnsupportedOperationException("setTimes")
    }

    companion object {
        private val NAME = Mai70FileSystemProvider.scheme

        val SUPPORTED_NAMES = setOf("basic", NAME)
    }
}
