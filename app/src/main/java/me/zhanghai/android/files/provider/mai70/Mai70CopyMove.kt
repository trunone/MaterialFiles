/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.mai70.client.Client
import java.io.IOException

internal object Mai70CopyMove {
    @Throws(IOException::class)
    fun copy(source: Mai70Path, target: Mai70Path, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceInfo = Client.listFile(source)
        val targetInfo = try {
            Client.listFile(target)
        } catch (e: Exception) {
            null
        }
        val sourceSize = sourceInfo.size
        if (targetInfo != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            Client.deleteFile(target)
        }
        if (sourceInfo.isDirectory) {
            throw UnsupportedOperationException("Cannot copy directory on 70mai server")
        } else {
            val sourceInputStream = Client.retrieveFile(source)
            try {
                val targetOutputStream = target.newOutputStream()
                var successful = false
                try {
                    sourceInputStream.copyTo(
                        targetOutputStream, copyOptions.progressIntervalMillis,
                        copyOptions.progressListener
                    )
                    successful = true
                } finally {
                    try {
                        targetOutputStream.close()
                    } finally {
                        if (!successful) {
                            try {
                                target.deleteIfExists()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
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
