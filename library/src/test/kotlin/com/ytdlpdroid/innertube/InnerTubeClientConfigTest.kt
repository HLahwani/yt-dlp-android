package com.ytdlpdroid.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeClientConfigTest {

    @Test
    fun `ANDROID body contains videoId and clientName`() {
        val body = InnerTubeClientConfig.ANDROID.buildRequestBody("test123")
        assertTrue(body.contains("\"videoId\": \"test123\""))
        assertTrue(body.contains("\"clientName\": \"ANDROID\""))
    }

    @Test
    fun `WEB body contains correct clientName`() {
        val body = InnerTubeClientConfig.WEB.buildRequestBody("abc123XYZab")
        assertTrue(body.contains("\"clientName\": \"WEB\""))
        assertTrue(body.contains("\"videoId\": \"abc123XYZab\""))
    }

    @Test
    fun `ANDROID body includes androidSdkVersion`() {
        val body = InnerTubeClientConfig.ANDROID.buildRequestBody("test123")
        assertTrue(body.contains("\"androidSdkVersion\": 30"))
    }

    @Test
    fun `WEB body does not include androidSdkVersion`() {
        val body = InnerTubeClientConfig.WEB.buildRequestBody("test123")
        assertTrue(!body.contains("androidSdkVersion"))
    }

    @Test
    fun `ANDROID body includes params field`() {
        val body = InnerTubeClientConfig.ANDROID.buildRequestBody("test123")
        assertTrue(body.contains("\"params\""))
        assertTrue(body.contains("8AEB"))
    }

    @Test
    fun `IOS config has populated fields`() {
        val cfg = InnerTubeClientConfig.IOS
        assertTrue(cfg.clientName.isNotEmpty())
        assertTrue(cfg.clientVersion.isNotEmpty())
        assertTrue(cfg.userAgent.contains("iPhone"))
    }

    @Test
    fun `all configs have populated fields`() {
        val configs = listOf(
            InnerTubeClientConfig.ANDROID,
            InnerTubeClientConfig.ANDROID_VR,
            InnerTubeClientConfig.IOS,
            InnerTubeClientConfig.WEB_EMBEDDED,
            InnerTubeClientConfig.WEB,
        )
        for (cfg in configs) {
            assertTrue("clientName empty for ${cfg::class.simpleName}", cfg.clientName.isNotEmpty())
            assertTrue("clientVersion empty for ${cfg::class.simpleName}", cfg.clientVersion.isNotEmpty())
            assertTrue("userAgent empty for ${cfg::class.simpleName}", cfg.userAgent.isNotEmpty())
        }
    }

    @Test
    fun `body contains racyCheckOk and contentCheckOk`() {
        val body = InnerTubeClientConfig.ANDROID.buildRequestBody("test123")
        assertTrue(body.contains("\"racyCheckOk\": true"))
        assertTrue(body.contains("\"contentCheckOk\": true"))
    }
}
