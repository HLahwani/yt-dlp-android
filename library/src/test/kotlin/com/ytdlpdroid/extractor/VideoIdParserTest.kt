package com.ytdlpdroid.extractor

import com.ytdlpdroid.model.YTDLPError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoIdParserTest {

    private fun parse(input: String) = VideoIdParser.parse(input)

    @Test fun `standard watch url`() =
        assertEquals("dQw4w9WgXcQ", parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

    @Test fun `watch url with playlist params strips to video id only`() =
        assertEquals("dQw4w9WgXcQ", parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2"))

    @Test fun `short youtu_be url`() =
        assertEquals("dQw4w9WgXcQ", parse("https://youtu.be/dQw4w9WgXcQ"))

    @Test fun `youtu_be url with timestamp`() =
        assertEquals("dQw4w9WgXcQ", parse("https://youtu.be/dQw4w9WgXcQ?t=42"))

    @Test fun `embed url`() =
        assertEquals("dQw4w9WgXcQ", parse("https://www.youtube.com/embed/dQw4w9WgXcQ"))

    @Test fun `shorts url`() =
        assertEquals("dQw4w9WgXcQ", parse("https://www.youtube.com/shorts/dQw4w9WgXcQ"))

    @Test fun `music youtube url`() =
        assertEquals("dQw4w9WgXcQ", parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))

    @Test fun `mobile m youtube url`() =
        assertEquals("dQw4w9WgXcQ", parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))

    @Test fun `raw video id`() =
        assertEquals("dQw4w9WgXcQ", parse("dQw4w9WgXcQ"))

    @Test fun `invalid short string throws`() {
        assertThrows(YTDLPError.InvalidUrl::class.java) { parse("not_a_url") }
    }

    @Test fun `empty string throws`() {
        assertThrows(YTDLPError.InvalidUrl::class.java) { parse("") }
    }

    @Test fun `vimeo url throws`() {
        assertThrows(YTDLPError.InvalidUrl::class.java) { parse("https://vimeo.com/123") }
    }
}
