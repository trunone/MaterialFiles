/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import java8.nio.file.Path
import me.zhanghai.android.files.provider.mai70.client.Authority

fun Authority.createMai70RootPath(): Path =
    Mai70FileSystemProvider.getOrNewFileSystem(this).rootDirectory
