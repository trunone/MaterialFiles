/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70.client

enum class Protocol(val scheme: String, val defaultPort: Int) {
    MAI70("mai70", 80);

    companion object {
        val SCHEMES = entries.map { it.scheme }

        fun fromScheme(scheme: String): Protocol =
            entries.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown scheme: $scheme")
    }
}
