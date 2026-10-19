/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import java8.nio.file.attribute.BasicFileAttributes
import java.io.IOException

internal object Mai70CopyMove {
    @Throws(IOException::class)
    fun copy(source: Mai70Path, target: Mai70Path, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceAttributeView = Mai70FileAttributeView(source, false)
        val sourceAttributes = sourceAttributeView.readAttributes()
        val targetAttributes = try {
            Mai70FileAttributeView(target, false).readAttributes()
        } catch (_: Exception) {
            null
        }
        val sourceSize = sourceAttributes.size()
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            Mai70FileSystemProvider.delete(target)
        }
        if (sourceAttributes.isDirectory) {
            throw UnsupportedOperationException("Cannot copy directory on 70mai server")
        } else {
            val sourceInputStream = Mai70FileSystemProvider.newInputStream(source)
            try {
                throw UnsupportedOperationException("Writing to 70mai server is not supported")
            } finally {
                sourceInputStream.close()
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: Mai70Path, target: Mai70Path, copyOptions: CopyOptions) {
        throw UnsupportedOperationException("Moving files on 70mai server is not supported")
    }
}
