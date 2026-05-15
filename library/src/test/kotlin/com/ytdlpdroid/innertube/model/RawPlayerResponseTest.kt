package com.ytdlpdroid.innertube.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RawPlayerResponseTest {

    private fun loadFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().readText()

    @Test
    fun `parses ok response with streaming data and video details`() {
        val raw = json.decodeFromString<RawPlayerResponse>(loadFixture("player_response_ok.json"))

        assertEquals("OK", raw.playabilityStatus.status)
        assertNotNull(raw.streamingData)
        assertEquals(1, raw.streamingData!!.formats.size)
        assertEquals(4, raw.streamingData.adaptiveFormats.size)
        assertNotNull(raw.videoDetails)
        assertEquals("jNQXAC9IVRw", raw.videoDetails!!.videoId)
        assertEquals("Me at the zoo", raw.videoDetails.title)
        assertEquals(4, raw.videoDetails.thumbnail!!.thumbnails.size)
    }

    @Test
    fun `streaming data absent for unavailable video does not throw`() {
        val raw = json.decodeFromString<RawPlayerResponse>(loadFixture("player_response_unplayable.json"))

        assertEquals("UNPLAYABLE", raw.playabilityStatus.status)
        assertNull(raw.streamingData)
        assertNull(raw.videoDetails)
    }

    @Test
    fun `parses login required response`() {
        val raw = json.decodeFromString<RawPlayerResponse>(loadFixture("player_response_login_required.json"))

        assertEquals("LOGIN_REQUIRED", raw.playabilityStatus.status)
        assertNull(raw.streamingData)
    }

    @Test
    fun `parses adaptive format ranges and codec`() {
        val raw = json.decodeFromString<RawPlayerResponse>(loadFixture("player_response_ok.json"))
        val opus = raw.streamingData!!.adaptiveFormats.first { it.itag == 251 }

        assertEquals("audio/webm; codecs=\"opus\"", opus.mimeType)
        assertEquals("0", opus.initRange!!.start)
        assertEquals("48000", opus.audioSampleRate)
    }
}
