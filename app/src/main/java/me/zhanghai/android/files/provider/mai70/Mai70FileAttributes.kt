/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import android.os.Parcelable
import com.github.trunone.mai70protocol.models.FileInfo
import java8.nio.file.attribute.FileTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.provider.common.AbstractBasicFileAttributes
import me.zhanghai.android.files.provider.common.BasicFileType
import me.zhanghai.android.files.provider.common.FileTimeParceler

@Parcelize
internal data class Mai70FileAttributes(
    override val lastModifiedTime: @WriteWith<FileTimeParceler> FileTime,
    override val lastAccessTime: @WriteWith<FileTimeParceler> FileTime,
    override val creationTime: @WriteWith<FileTimeParceler> FileTime,
    override val type: BasicFileType,
    override val size: Long,
    override val fileKey: Parcelable
) : AbstractBasicFileAttributes() {
    companion object {
        fun from(info: FileInfo, path: Mai70Path): Mai70FileAttributes {
            val lastModifiedTime = FileTime.from(info.lastModifiedTime)
            val lastAccessTime = lastModifiedTime
            val creationTime = lastModifiedTime
            val type = if (info.isDirectory) {
                BasicFileType.DIRECTORY
            } else {
                BasicFileType.REGULAR_FILE
            }
            val size = info.size
            val fileKey = path
            return Mai70FileAttributes(
                lastModifiedTime, lastAccessTime, creationTime, type, size, fileKey
            )
        }
    }
}
