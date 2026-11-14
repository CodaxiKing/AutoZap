package com.codaxi.pinsave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramExtractorTest {

    @Test
    fun recognisesPostAndReelLinks() {
        val text = "olha https://www.instagram.com/reel/Cabc_12-x/?igsh=abc== e https://instagram.com/p/XyZ123/"
        assertEquals(
            listOf("https://www.instagram.com/reel/Cabc_12-x/?igsh=abc==", "https://instagram.com/p/XyZ123/"),
            InstagramExtractor.findLinks(text)
        )
        assertEquals("Cabc_12-x", InstagramExtractor.shortcode("https://www.instagram.com/reel/Cabc_12-x/?igsh=abc=="))
        assertEquals("XyZ123", InstagramExtractor.shortcode("https://www.instagram.com/fulano/p/XyZ123/"))
        assertNull(InstagramExtractor.shortcode("https://www.instagram.com/share/reel/BAbc"))
        assertTrue(InstagramExtractor.findLinks("https://www.instagram.com/fulano/").isEmpty())
        assertTrue(InstagramExtractor.findLinks("https://www.instagram.com/stories/fulano/123/").isEmpty())
    }

    @Test
    fun linksKeepTheOrderTheyAppearIn() {
        val text = "https://www.instagram.com/p/AAA/ depois https://pin.it/bbb"
        assertEquals(listOf("https://www.instagram.com/p/AAA/", "https://pin.it/bbb"), Links.findLinks(text))
        assertEquals("https://pin.it/bbb", Links.findLink("só https://pin.it/bbb"))
        assertNull(Links.findLink("https://example.com/p/AAA/"))
    }

    @Test
    fun parsesReelFromGraphqlJson() {
        val json = """
            {"data":{"xdt_shortcode_media":{"__typename":"XDTGraphVideo","id":"1",
             "display_url":"https:\/\/scontent.cdninstagram.com\/v\/capa.jpg?a=1\u0026b=2",
             "video_url":"https:\/\/scontent.cdninstagram.com\/v\/video.mp4?a=1\u0026b=2",
             "edge_media_to_caption":{"edges":[{"node":{"created_at":"1","text":"Legenda \"top\"\n#reels","id":"9"}}]}}}}
        """
        val parsed = InstagramExtractor.parse(json)
        assertEquals(1, parsed.media.size)
        assertTrue(parsed.media[0].isVideo)
        assertEquals("https://scontent.cdninstagram.com/v/video.mp4?a=1&b=2", parsed.media[0].urls[0])
        assertEquals("Legenda \"top\"\n#reels", parsed.caption)
    }

    @Test
    fun parsesCarouselWithImageAndVideo() {
        val json = """
            {"__typename":"XDTGraphSidecar","display_url":"https://cdn/capa.jpg",
             "edge_sidecar_to_children":{"edges":[
               {"node":{"__typename":"XDTGraphImage","display_url":"https://cdn/um.jpg","is_video":false}},
               {"node":{"__typename":"XDTGraphVideo","display_url":"https://cdn/dois.jpg","video_url":"https://cdn/dois.mp4"}}
             ]}}
        """
        val media = InstagramExtractor.parse(json).media
        assertEquals(2, media.size)
        assertEquals(listOf("https://cdn/um.jpg"), media[0].urls)
        assertEquals(false, media[0].isVideo)
        assertEquals(listOf("https://cdn/dois.mp4"), media[1].urls)
        assertEquals(true, media[1].isVideo)
    }

    @Test
    fun parsesJsonEmbeddedInTheEmbedPage() {
        val html = """<script>s.handle({"contextJSON":"{\"gql_data\":{\"shortcode_media\":{\"__typename\":\"GraphVideo\",\"display_url\":\"https:\\\/\\\/cdn\\\/c.jpg\",\"video_url\":\"https:\\\/\\\/cdn\\\/v.mp4?x=1\\u0026y=2\"}}}"});</script>"""
        val media = InstagramExtractor.parse(html).media
        assertEquals(1, media.size)
        assertEquals("https://cdn/v.mp4?x=1&y=2", media[0].urls[0])
    }

    @Test
    fun loginPageHasNoMedia() {
        assertTrue(InstagramExtractor.parse("<html><title>Login • Instagram</title></html>").media.isEmpty())
    }
}
