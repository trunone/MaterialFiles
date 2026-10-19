/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.mai70

import com.github.trunone.mai70protocol.Mai70Protocol
import com.github.trunone.mai70protocol.models.Authority
import me.zhanghai.android.files.storage.Mai70Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.URI

class Mai70ProviderTest {

    @Test
    fun testAuthorityUriFormat() {
        val authority = Authority("192.168.0.1", 80, "")
        assertEquals("192.168.0.1", authority.toUriAuthority().toString())

        val authorityWithPortAndToken = Authority("192.168.0.1", 8080, "abcdef123456")
        assertEquals("abcdef123456@192.168.0.1:8080", authorityWithPortAndToken.toUriAuthority().toString())
    }

    @Test
    fun testFileSystemProviderGetPath() {
        val uri = URI.create("mai70://192.168.0.1/Normal/test.mp4")
        val path = Mai70FileSystemProvider.getPath(uri)
        assertNotNull(path)
        assertEquals("mai70", path.fileSystem.provider().scheme)
        assertEquals("/Normal/test.mp4", path.toString())
    }

    @Test
    fun testMai70ServerCreation() {
        val authority = Authority("192.168.0.1", 80, "testtoken")
        val server = Mai70Server(1L, "My Dashcam", authority, "Normal")
        assertEquals("My Dashcam", server.customName)
        assertEquals(authority, server.authority)
        assertEquals("Normal", server.relativePath)
    }

    @Test
    fun testProtocolMd5() {
        val query = "getfilelist.cgi?-start=0&-end=99&-type=0&-timestamp=1690000000"
        val token = "1234567890abcdef1234567890abcdef"
        val expectedSignKey = Mai70Protocol.calculateMD5(query + token)
        assertEquals(32, expectedSignKey.length)
    }
}
