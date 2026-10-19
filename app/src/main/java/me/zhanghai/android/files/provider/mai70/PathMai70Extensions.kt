/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import com.github.trunone.mai70protocol.models.Authority
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.UriAuthority

fun Authority.toUriAuthority(): UriAuthority {
    val userInfo = token.takeIf { it.isNotEmpty() }
    val uriPort = port.takeIf { it != 80 }
    return UriAuthority(userInfo, host, uriPort)
}

fun Authority.createMai70RootPath(): Path =
    Mai70FileSystemProvider.getOrNewFileSystem(this).rootDirectory
