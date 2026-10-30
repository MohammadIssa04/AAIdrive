package com.musik.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearchTest {
    private fun s(id: String, t: String, a: String) = Song(id, t, a, "A $id", "al$id", "ar$a", id, 200)
    private val raw = SearchResult(
        listOf(Artist("x1", "Habibi Funk", null, 1), Artist("ad", "Amr Diab", null, 9)), emptyList(),
        listOf(s("u1", "Habibi", "Unknown Rapper"), s("u2", "Habibi (Remix)", "Random DJ"), s("f1", "Habibi Ya Nour El Ain", "Amr Diab"),
            s("u4", "Habibi", "Another Guy"), s("f1b", "Habibi Ya Nour El Ain", "Amr Diab"), s("z9", "Totally Different", "Someone")))
    private val taste = Taste(mapOf("amr diab" to ("Amr Diab" to 1f)), mapOf("f1" to 10), emptySet(), emptyMap(), emptyMap(), emptySet(), true)

    @Test fun familiarArtistFirstWhenRelevant() {
        val r = SmartSearch.rank("habibi", raw, emptyList(), taste, true, emptySet(), emptySet())
        assertEquals("f1", r.result.songs.first().id)
        assertTrue(r.why["f1"]!!.contains("played this 10 times"))
    }
    @Test fun duplicatesAndIrrelevantRemoved() {
        val r = SmartSearch.rank("habibi", raw, emptyList(), taste, true, emptySet(), emptySet())
        assertEquals(1, r.result.songs.count { it.title == "Habibi Ya Nour El Ain" })
        assertTrue(r.result.songs.none { it.id == "z9" })
    }
    @Test fun noHistoryMeansPlainRelevance() {
        val r = SmartSearch.rank("habibi", raw, emptyList(), null, true, emptySet(), emptySet())
        assertEquals("u1", r.result.songs.first().id)
        assertTrue(r.why.isEmpty())
    }
    @Test fun personalOffMeansPlainRelevance() {
        val r = SmartSearch.rank("habibi", raw, emptyList(), taste, false, emptySet(), emptySet())
        assertEquals("u1", r.result.songs.first().id)
    }
    @Test fun skipsLowerASong() {
        val skipped = Taste(taste.artists, taste.songs, emptySet(), mapOf(SmartSearch.key("Habibi Ya Nour El Ain", "Amr Diab") to 5), emptyMap(), emptySet(), true)
        val r = SmartSearch.rank("habibi", raw, emptyList(), skipped, true, emptySet(), emptySet())
        assertTrue(r.result.songs.indexOfFirst { it.id.startsWith("f1") } > 0)
    }
    @Test fun typosAndPartialTitles() {
        assertTrue(SmartSearch.match("creepn", "Creepin", "Metro Boomin") >= 50)
        assertTrue(SmartSearch.match("creepin metro", "Creepin", "Metro Boomin") >= 95)
        assertTrue(SmartSearch.match("bohemian", "Bohemian Rhapsody", "Queen") >= 88)
        assertTrue(SmartSearch.match("drake", "Totally Different", "Someone") < 30)
    }
    @Test fun artistQueryGivesArtistTopResult() {
        val r = SmartSearch.rank("amr diab", raw, emptyList(), taste, true, emptySet(), emptySet())
        assertEquals("artist", r.kind)
        assertTrue(r.top is TopHit.ArtistTop)
        assertEquals("Amr Diab", SmartSearch.mainArtistOf(r.result.songs.first().artist))
    }
    @Test fun smallWordsDontMakeAnArtistMatch() {
        // "morning with fairouz": the song named so comes before an artist that only shares the name Fairouz
        val rr = SearchResult(listOf(Artist("o", "Fairouz Oudjida", null, 1)), emptyList(),
            listOf(s("o1", "Rendez-vous", "Fairouz Oudjida"), s("m1", "Morning with Fairouz", "Fairuz"), s("w1", "Dance with me", "Someone")))
        val r = SmartSearch.rank("morning with fairouz", rr, emptyList(), null, true, emptySet(), emptySet())
        assertEquals("m1", r.result.songs.first().id)
        assertTrue(SmartSearch.match("morning with fairouz", "Dance with me", "Someone") < 30)
    }
    @Test fun arabicSpellingMatches() {
        val fr = SearchResult(emptyList(), emptyList(), listOf(s("y1", "Ya Reit", "Fairuz"), s("o1", "Other", "Nobody")))
        val r = SmartSearch.rank("فيروز", fr, emptyList(), null, true, emptySet(), emptySet())
        assertEquals("y1", r.result.songs.first().id)
    }
    @Test fun lyricLineBecomesLyricHit() {
        val hit = LyricHit(s("f1", "Habibi Ya Nour El Ain", "Amr Diab"), "habibi ya nour el ain ya sakin khayali")
        val r = SmartSearch.rank("ya sakin khayali habibi", raw, listOf(hit), taste, true, emptySet(), emptySet())
        assertEquals("lyrics", r.kind)
        assertEquals("f1", r.lyrics.first().song.id)
    }

    @Test fun originalBeforeRemix() {
        val rr = SearchResult(emptyList(), emptyList(), listOf(s("r1", "Amazing (Remix)", "INNA"), s("k1", "Amazing (Karaoke Version)", "Hits"), s("o1", "Amazing", "INNA")))
        val r = SmartSearch.rank("amazing", rr, emptyList(), null, true, emptySet(), emptySet())
        assertEquals("o1", r.result.songs.first().id)
        assertEquals(3, r.result.songs.size) // remix and karaoke are still there, just after the original
        val r2 = SmartSearch.rank("amazing remix", rr, emptyList(), null, true, emptySet(), emptySet())
        assertEquals("r1", r2.result.songs.first().id)
        assertTrue(SmartSearch.isVariant("Amazing INNA (Remix)") && SmartSearch.isVariant("Song (Sped Up)") && !SmartSearch.isVariant("Amazing"))
    }

    @Test fun lyricLineWithTyposAndWrongWord() {
        val text = "Dangerous feelings break out my soul\nWhy you're looking like that\nI'm burning like fire\nI wanna be higher"
        val (sc, line) = SmartSearch.lyricMatch("why you walking like that im buring like fire".split(" "), text)
        assertTrue(sc >= 0.7f)
        assertTrue(line.contains("burning like fire"))
        assertTrue(SmartSearch.lyricMatch("totally different words here".split(" "), text).first < 0.7f)
        assertTrue(SmartSearch.isVariant("Concrete Remixed"))
    }
}
