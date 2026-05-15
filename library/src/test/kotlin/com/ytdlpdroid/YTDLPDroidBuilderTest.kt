package com.ytdlpdroid

import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YTDLPDroidBuilderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `Builder with defaults builds without error`() {
        // QuickJsEngine loads the native lib — skip on JVM test by providing a stub JsEngine
        val ytdlp = YTDLPDroid.Builder(tmp.newFolder())
            .jsEngine { _, arg -> arg } // stub: passthrough
            .build()
        assertNotNull(ytdlp)
    }

    @Test
    fun `Builder memoryCacheCapacity is configurable`() {
        val ytdlp = YTDLPDroid.Builder(tmp.newFolder())
            .jsEngine { _, arg -> arg }
            .memoryCacheCapacity(10)
            .build()
        assertNotNull(ytdlp)
    }
}
