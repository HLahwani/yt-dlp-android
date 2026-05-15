package com.ytdlpdroid.innertube

import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.innertube.model.json
import com.ytdlpdroid.model.YTDLPError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResponseParserTest {

    private fun loadFixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun parse(fixture: String) =
        json.decodeFromString<RawPlayerResponse>(loadFixture(fixture))

    @Test
    fun `checkPlayability OK does not throw`() {
        PlayerResponseParser.checkPlayability(parse("player_response_ok.json"), "jNQXAC9IVRw")
    }

    @Test
    fun `checkPlayability LOGIN_REQUIRED throws AgeRestricted`() {
        assertThrows(YTDLPError.AgeRestricted::class.java) {
            PlayerResponseParser.checkPlayability(parse("player_response_login_required.json"), "vid")
        }
    }

    @Test
    fun `checkPlayability UNPLAYABLE with geo reason throws GeoBlocked`() {
        // The fixture reason is "not available in your country" — triggers GeoBlocked
        assertThrows(YTDLPError.GeoBlocked::class.java) {
            PlayerResponseParser.checkPlayability(parse("player_response_unplayable.json"), "vid")
        }
    }

    @Test
    fun `checkPlayability UNPLAYABLE without geo reason throws VideoUnavailable`() {
        val raw = json.decodeFromString<com.ytdlpdroid.innertube.model.RawPlayerResponse>(
            """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Content is private."}}""")
        assertThrows(YTDLPError.VideoUnavailable::class.java) {
            PlayerResponseParser.checkPlayability(raw, "vid")
        }
    }

    @Test
    fun `extractVideoInfo maps fields correctly`() {
        val info = PlayerResponseParser.extractVideoInfo(parse("player_response_ok.json"))
        assertEquals("jNQXAC9IVRw", info.videoId)
        assertEquals("Me at the zoo", info.title)
        assertEquals("jawed", info.author)
        assertEquals(19L, info.durationSeconds)
        assertFalse(info.isLive)
        assertTrue(info.keywords.isNotEmpty())
    }

    @Test
    fun `extractVideoInfo selects highest resolution thumbnail`() {
        val info = PlayerResponseParser.extractVideoInfo(parse("player_response_ok.json"))
        // Fixture has 120x90, 320x180, 480x360, 1280x720 — highest is maxresdefault
        assertTrue(info.thumbnailUrl.contains("maxresdefault"))
    }

    @Test
    fun `isLive returns false for regular video`() {
        assertFalse(PlayerResponseParser.isLive(parse("player_response_ok.json")))
    }

    @Test
    fun `isLive returns true when isLiveContent flag is set`() {
        val raw = json.decodeFromString<RawPlayerResponse>("""
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"videoId":"x","title":"t","isLiveContent":true}}
        """.trimIndent())
        assertTrue(PlayerResponseParser.isLive(raw))
    }
}
