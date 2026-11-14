package com.codaxi.pinsave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinterestExtractorTest {
    private val h1 = "0123456789abcdef0123456789abcdef"
    private val h2 = "ffffffffffffffffffffffffffffffff"

    @Test
    fun findsLinksInsideText() {
        assertEquals("https://pin.it/4AbCdEf", PinterestExtractor.findLink("Olha isso https://pin.it/4AbCdEf"))
        assertEquals(
            "https://br.pinterest.com/pin/123456789/",
            PinterestExtractor.findLink("veja: https://br.pinterest.com/pin/123456789/ legal")
        )
        assertEquals(
            "https://www.pinterest.co.uk/pin/titulo-legal--987/",
            PinterestExtractor.findLink("https://www.pinterest.co.uk/pin/titulo-legal--987/")
        )
        assertNull(PinterestExtractor.findLink("https://www.youtube.com/watch?v=abc"))
        assertNull(PinterestExtractor.findLink("https://www.pinterest.com/usuario/pasta/"))
        assertNull(PinterestExtractor.findLink(null))
    }

    @Test
    fun extractsPinId() {
        assertEquals("123456789", PinterestExtractor.pinId("https://br.pinterest.com/pin/123456789/"))
        assertEquals("987", PinterestExtractor.pinId("https://www.pinterest.com/pin/titulo-legal--987/?x=1"))
        assertEquals("55", PinterestExtractor.pinId("https://www.pinterest.com/pin/55"))
        assertNull(PinterestExtractor.pinId("https://pin.it/4AbCdEf"))
    }

    @Test
    fun prefersHighestQualityMp4OfTheMainPin() {
        val html = """
            <script type="application/ld+json">{"contentUrl":"https://v1.pinimg.com/videos/mc/720p/01/23/45/$h1.mp4"}</script>
            {"V_HLSV4":{"url":"https:\/\/v1.pinimg.com\/videos\/mc\/hls\/01\/23\/45\/$h1.m3u8"},
             "V_1080P":{"url":"https://v1.pinimg.com/videos/mc/1080p/01/23/45/$h1.mp4"},
             "related":{"url":"https://v1.pinimg.com/videos/mc/720p/ff/ff/ff/$h2.mp4"}}
        """
        val c = PinterestExtractor.candidates(html)
        assertEquals("https://v1.pinimg.com/videos/mc/1080p/01/23/45/$h1.mp4", c[0])
        assertEquals("https://v1.pinimg.com/videos/mc/720p/01/23/45/$h1.mp4", c[1])
        assertTrue(c.none { it.contains(h2) })
        assertTrue(c.none { it.endsWith(".m3u8") })
    }

    @Test
    fun convertsHlsWhenThereIsNoMp4() {
        val html = """<video src="https://v1.pinimg.com/videos/iht/hls/01/23/45/$h1.m3u8">"""
        assertEquals(
            listOf("https://v1.pinimg.com/videos/iht/720p/01/23/45/$h1.mp4"),
            PinterestExtractor.candidates(html)
        )
    }

    @Test
    fun imagePinHasNoCandidates() {
        assertTrue(PinterestExtractor.candidates("""<img src="https://i.pinimg.com/736x/aa/bb/cc/x.jpg">""").isEmpty())
    }

    @Test
    fun captionFromVideoMetadata() {
        val html = """
            <meta property="og:title" content="Bolo de cenoura f&#225;cil | Pinterest">
            <script data-test-id="video-snippet" type="application/ld+json">
            {"@type":"VideoObject","name":"x","description":"Receita em 3 passos \"sem erro\"\n#bolo #receita","creator":{"name":"Fulana"}}
            </script>
        """
        assertEquals(
            "Bolo de cenoura fácil\n\nReceita em 3 passos \"sem erro\"\n#bolo #receita",
            PinterestExtractor.caption(html)
        )
    }

    @Test
    fun captionIgnoresPinterestBoilerplate() {
        val html = """
            <meta property="og:title" content="Pinterest">
            <meta property="og:description" content="This Pin was discovered by Someone. Discover (and save!) your own Pins on Pinterest">
        """
        assertEquals("", PinterestExtractor.caption(html))
        assertEquals("", PinterestExtractor.caption("{}"))
    }

    @Test
    fun captionDropsTitleRepeatedInDescription() {
        val html = """
            <meta content="Treino de pernas em casa" property="og:title">
            <meta name="og:description" content="Treino de pernas em casa, sem equipamento">
        """
        assertEquals("Treino de pernas em casa, sem equipamento", PinterestExtractor.caption(html))
    }
}
