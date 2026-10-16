/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.mai70.client.Authority
import me.zhanghai.android.files.provider.mai70.createMai70RootPath
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import kotlin.random.Random

@Parcelize
class Mai70Server(
    override val id: Long,
    override val customName: String?,
    val authority: Authority,
    val relativePath: String
) : Storage() {
    constructor(
        id: Long?,
        customName: String?,
        authority: Authority,
        relativePath: String
    ) : this(id ?: Random.nextLong(), customName, authority, relativePath)

    override val iconRes: Int
        @DrawableRes
        get() = R.drawable.computer_icon_white_24dp

    override fun getDefaultName(context: Context): String =
        if (relativePath.isNotEmpty()) "$authority/$relativePath" else authority.toString()

    override val description: String
        get() = authority.toString()

    override val path: Path
        get() = authority.createMai70RootPath().resolve(relativePath)

    override fun createEditIntent(): Intent =
        EditMai70ServerActivity::class.createIntent().putArgs(EditMai70ServerFragment.Args(this))
}
