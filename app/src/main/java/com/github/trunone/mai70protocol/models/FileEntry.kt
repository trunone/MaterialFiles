package com.github.trunone.mai70protocol.models

/**
 * Represents a single remote file entry returned by the camera CGI API.
 *
 * @property path Remote relative directory path on camera storage (e.g., "Normal" or "sd/Normal/Front").
 * @property name File name with extension (e.g., "NO20240101-120000-000100.MP4").
 * @property size File size in bytes.
 * @property category Category associated with this file entry.
 */
data class FileEntry(
    val path: String,
    val name: String,
    val size: Long,
    val category: Category = Category.UNKNOWN
)
