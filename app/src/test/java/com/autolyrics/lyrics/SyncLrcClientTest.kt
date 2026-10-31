package com.autolyrics.lyrics

import com.autolyrics.model.TrackInfo
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLrcClientTest {

    private val track = TrackInfo(
        title = "Provider",
        artist = "Example Artist",
        album = "Example Album",
        durationMs = 180_000L
    )

    @Test
    fun itunesResponseParsesTitleArtistPairs() {
        val json = """
            {"resultCount":3,"results":[
              {"trackName":"Connect","artistName":"ClariS"},
              {"trackName":"  ","artistName":"Nobody"},
              {"trackName":"Connect","artistName":"ClariS"},
              {"trackName":"コネクト","artistName":"WANIMANA"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("Connect" to "ClariS", "コネクト" to "WANIMANA"),
            SyncLrcClient.parseItunesResponse(json)
        )
        assertTrue(SyncLrcClient.parseItunesResponse("not json").isEmpty())
        assertTrue(SyncLrcClient.parseItunesResponse("{}").isEmpty())
    }

    @Test
    fun apiResponseFieldsHaveStableSerializedNames() {
        val expected = mapOf(
            "karaoke" to "karaoke",
            "synced" to "synced",
            "plain" to "plain",
            "lyrics" to "lyrics",
            "type" to "type",
            "id" to "id",
            "track" to "track",
            "artist" to "artist",
            "album" to "album",
            "duration" to "duration",
            "instrumental" to "instrumental"
        )

        val actual = SyncLrcClient.ApiResponse::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .associate { field ->
                field.name to field.getAnnotation(SerializedName::class.java)?.value
            }

        expected.forEach { (fieldName, jsonName) ->
            assertEquals(jsonName, actual[fieldName])
        }
    }

    @Test
    fun acceptsCurrentPublicKaraokeFieldResponse() {
        val json = """
            {
              "id": "abc",
              "track": "Provider",
              "artist": "Example Artist",
              "album": "Example Album",
              "duration": 180,
              "instrumental": false,
              "karaoke": "[00:01.00]<00:01.00>Pro<00:01.10>vi<00:01.20>der",
              "synced": "[00:01.00]Provider",
              "plain": "Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)

        assertEquals("Provider", result?.matchedTitle)
        assertEquals("Example Artist", result?.matchedArtist)
        assertEquals(180.0, result?.matchedDurationSec ?: 0.0, 0.001)
        assertEquals("Provider", result?.lines?.single()?.text)
        assertEquals(listOf("Pro", "vi", "der"), result?.lines?.single()?.words?.map { it.text })
    }

    @Test
    fun acceptsLegacyTypeSpecificKaraokeResponseForCompatibility() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "type": "karaoke",
              "lyrics": "[00:01.00]<00:01.00>Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)

        assertEquals("Provider", result?.lines?.single()?.text)
    }

    @Test
    fun acceptsSyncedOnlyResponseAsLineSync() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "synced": "[00:01.00]Provider",
              "plain": "Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)
        assertEquals("Provider", result?.lines?.single()?.text)
        assertEquals(LyricsProviderCandidate.SyncKind.LINE_SYNC, result?.syncKind)
    }

    @Test
    fun karaokePayloadWithoutTokensFallsBackToSyncedTier() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "karaoke": "[00:01.00]Provider",
              "synced": "[00:02.00]Provider synced"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)
        assertEquals("Provider synced", result?.lines?.single()?.text)
        assertEquals(LyricsProviderCandidate.SyncKind.LINE_SYNC, result?.syncKind)
    }

    @Test
    fun acceptsLegacyTypeSyncedLyricsField() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "type": "synced",
              "lyrics": "[00:01.00]Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)
        assertEquals(LyricsProviderCandidate.SyncKind.LINE_SYNC, result?.syncKind)
    }

    @Test
    fun acceptsLegacyTypePlainLyricsField() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "type": "plain",
              "lyrics": "Provider"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json, track)
        assertEquals("Provider", result?.lines?.single()?.text)
        assertEquals(LyricsProviderCandidate.SyncKind.PLAIN, result?.syncKind)
    }

    @Test
    fun rejectsInstrumentalResponse() {
        val json = """
            {
              "track": "Provider",
              "artist": "Example Artist",
              "instrumental": true,
              "karaoke": "[00:01.00]<00:01.00>Provider"
            }
        """.trimIndent()

        assertNull(SyncLrcClient.parseApiResponse(json, track))
    }

    @Test
    fun preservesJapaneseFineGrainedTimingForDisplayLayer() {
        val json = """
            {
              "track": "君を忘れない",
              "artist": "Example Artist",
              "karaoke": "[00:01.00]<00:01.00>君<00:01.10>を<00:01.20>忘<00:01.30>れ<00:01.40>な<00:01.50>い"
            }
        """.trimIndent()

        val result = SyncLrcClient.parseApiResponse(json)
        val line = result?.lines?.single()

        assertEquals("君を忘れない", line?.text)
        assertEquals(6, line?.words?.size)
        assertTrue(line?.words?.all { it.text.length == 1 } == true)
    }


    @Test
    fun pickBestPrefersExactTitleThenClosestDuration() {
        val candidate = SyncLrcClient.ApiResponse(track = "Yoru ni Kakeru", artist = "Yorushika")
        val far = SyncLrcClient.ApiResponse(
            track = "Yoru ni Kakeru",
            artist = "Yorushika",
            duration = 900.0,
            plain = "far"
        )
        val near = SyncLrcClient.ApiResponse(
            track = "Yoru ni Kakeru",
            artist = "Yorushika",
            duration = 260.0,
            plain = "near"
        )
        val other = SyncLrcClient.ApiResponse(track = "Elsewhere", artist = "Yorushika", plain = "x")
        val best = SyncLrcClient.pickBestCandidate(
            listOf(other, far, near),
            TrackInfo(title = "Yoru ni Kakeru", artist = "Yorushika", album = "", durationMs = 258_000L)
        )
        assertEquals("near", best?.plain)

        // Instrumental and payload-less rows never qualify.
        assertEquals(
            null,
            SyncLrcClient.pickBestCandidate(
                listOf(
                    candidate.copy(instrumental = true, plain = "y"),
                    
                ),
                TrackInfo(title = "Yoru ni Kakeru", artist = "Yorushika", album = "", durationMs = 0L)
            )
        )
    }

    @Test
    fun manualDiagUsesUniformLegVocabulary() {
        assertEquals("synclrc: 4", SyncLrcClient.manualDiag(4, 4, emptyList()))
        assertEquals("synclrc: 1/3 usable", SyncLrcClient.manualDiag(1, 3, emptyList()))
        assertEquals("synclrc: 0/6 usable", SyncLrcClient.manualDiag(0, 6, emptyList()))
        assertEquals("synclrc: HTTP 503", SyncLrcClient.manualDiag(0, 0, listOf("HTTP 503")))
        assertEquals("synclrc: 0 (no catalog match)", SyncLrcClient.manualDiag(0, 0, emptyList()))
    }

}
