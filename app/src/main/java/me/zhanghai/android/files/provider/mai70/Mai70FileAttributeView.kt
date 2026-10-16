/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import java8.nio.file.attribute.BasicFileAttributeView
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.mai70.client.Client
import java.io.IOException

internal class Mai70FileAttributeView(
    private val path: Mai70Path,
    private val noFollowLinks: Boolean
) : BasicFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): Mai70FileAttributes {
        val info = Client.listFile(path)
        return Mai70FileAttributes.from(info, path)
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
