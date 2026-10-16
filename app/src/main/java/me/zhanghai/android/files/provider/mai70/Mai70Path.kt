/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.UriAuthority
import me.zhanghai.android.files.provider.mai70.client.Authority
import me.zhanghai.android.files.provider.mai70.client.Client
import me.zhanghai.android.files.util.readParcelable
import java.io.File
import java.io.IOException

internal class Mai70Path : ByteStringListPath<Mai70Path>, Client.Path {
    private val fileSystem: Mai70FileSystem

    constructor(
        fileSystem: Mai70FileSystem,
        path: ByteString
    ) : super(Mai70FileSystem.SEPARATOR, path) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: Mai70FileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(Mai70FileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == Mai70FileSystem.SEPARATOR

    override fun createPath(path: ByteString): Mai70Path = Mai70Path(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): Mai70Path =
        Mai70Path(fileSystem, absolute, segments)

    override val uriScheme: String
        get() = fileSystem.authority.protocol.scheme

    override val uriAuthority: UriAuthority
        get() = fileSystem.authority.toUriAuthority()

    override val defaultDirectory: Mai70Path
        get() = fileSystem.defaultDirectory

    override fun getFileSystem(): FileSystem = fileSystem

    override fun getRoot(): Mai70Path? = if (isAbsolute) fileSystem.rootDirectory else null

    @Throws(IOException::class)
    override fun toRealPath(vararg options: LinkOption): Mai70Path {
        throw UnsupportedOperationException()
    }

    override fun toFile(): File {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey {
        if (watcher !is LocalWatchService) {
            throw ProviderMismatchException(watcher.toString())
        }
        return watcher.register(this, events, *modifiers)
    }

    override val authority: Authority
        get() = fileSystem.authority

    override val remotePath: String
        get() = toString()

    private constructor(source: Parcel) : super(source) {
        fileSystem = source.readParcelable()!!
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)

        dest.writeParcelable(fileSystem, flags)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<Mai70Path> {
            override fun createFromParcel(source: Parcel): Mai70Path = Mai70Path(source)

            override fun newArray(size: Int): Array<Mai70Path?> = arrayOfNulls(size)
        }
    }
}

val Path.isMai70Path: Boolean
    get() = this is Mai70Path
