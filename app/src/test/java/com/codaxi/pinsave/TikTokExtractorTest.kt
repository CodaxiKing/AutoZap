package com.codaxi.pinsave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokExtractorTest {

    @Test
    fun recognisesTikTokLinks() {
        val text = "veja https://vm.tiktok.com/ZMabc123/ e https://www.tiktok.com/@fulano/video/7301234567890123456?is_from_webapp=1"
        assertEquals(
            listOf(
                "https://vm.tiktok.com/ZMabc123/",
                "https://www.tiktok.com/@fulano/video/7301234567890123456?is_from_webapp=1"
            ),
            TikTokExtractor.findLinks(text)
        )
        assertTrue(TikTokExtractor.isTikTok("https://www.tiktok.com/t/ZTabc/"))
        assertTrue(TikTokExtractor.findLinks("https://www.tiktok.com/@fulano").isEmpty())
        assertEquals("TikTok", Links.source("https://vt.tiktok.com/ZSabc/"))
        assertEquals("https://vt.tiktok.com/ZSabc/", Links.findLink("abc https://vt.tiktok.com/ZSabc/"))
    }

    @Test
    fun picksTheOriginalFileNotTheWatermarkedCopy() {
        val html = """
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"seo":{"desc":"texto de SEO"},"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"1",
             "desc":"Legenda do vídeo #cars","video":{
               "downloadAddr":"https://v16.tiktokcdn.com/marca.mp4?a=1&b=2",
               "playAddr":"https://v16.tiktokcdn.com/original.mp4?a=1&b=2"}}}}}
            </script>
        """
        val parsed = TikTokExtractor.parse(html)
        assertEquals(1, parsed.media.size)
        assertTrue(parsed.media[0].isVideo)
        assertEquals("https://v16.tiktokcdn.com/original.mp4?a=1&b=2", parsed.media[0].urls[0])
        assertEquals("Legenda do vídeo #cars", parsed.caption)
    }

    @Test
    fun photoPostGivesEveryImage() {
        val html = """
            {"itemStruct":{"desc":"fotos","imagePost":{"images":[
              {"imageURL":{"urlList":["https://p16.tiktokcdn.com/um.jpeg","https://p19.tiktokcdn.com/um.jpeg"]}},
              {"imageURL":{"urlList":["https://p16.tiktokcdn.com/dois.jpeg"]}}],
              "cover":{"imageURL":{"urlList":["https://p16.tiktokcdn.com/capa.jpeg"]}}},
             "video":{"playAddr":"https://v16.tiktokcdn.com/musica.mp4"}}}
        """
        val media = TikTokExtractor.parse(html).media
        assertEquals(2, media.size)
        assertEquals(listOf("https://p16.tiktokcdn.com/um.jpeg"), media[0].urls)
        assertEquals(listOf("https://p16.tiktokcdn.com/dois.jpeg"), media[1].urls)
        assertTrue(media.none { it.isVideo })
    }

    @Test
    fun blockedPageHasNoMedia() {
        assertTrue(TikTokExtractor.parse("<html>captcha</html>").media.isEmpty())
    }
}
