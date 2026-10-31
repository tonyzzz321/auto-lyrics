package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PetitLyricsClientTest {

    @Test
    fun parsesType3WordSyncWithWordTimingAndExactSpacing() {
        val payload = """
            <wsy>
              <line>
                <linestring>Alpha line</linestring>
                <wordnum>2</wordnum>
                <word>
                  <starttime>1200</starttime>
                  <endtime>1500</endtime>
                  <wordstring>Alpha </wordstring>
                </word>
                <word>
                  <starttime>1700</starttime>
                  <endtime>2100</endtime>
                  <wordstring>line</wordstring>
                </word>
              </line>
              <line>
                <linestring>Beta</linestring>
                <wordnum>1</wordnum>
                <word>
                  <starttime>3400</starttime>
                  <endtime>3700</endtime>
                  <wordstring>Beta</wordstring>
                </word>
              </line>
            </wsy>
        """.trimIndent()

        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val response = """
            <result>
              <songs>
                <song>
                  <lyricsId>42</lyricsId>
                  <title>Test Song</title>
                  <artist>Test Artist</artist>
                  <album>Test Album</album>
                  <duration>123.4</duration>
                  <lyricsType>3</lyricsType>
                  <lyricsData>$encoded</lyricsData>
                </song>
              </songs>
            </result>
        """.trimIndent()

        val result = PetitLyricsClient.parseApiResponse(response)

        assertEquals(3, result?.lyricsType)
        assertEquals(2, result?.lines?.size)

        val firstLine = result?.lines?.get(0)
        assertEquals(1200L, firstLine?.timeMs)
        assertEquals("Alpha line", firstLine?.text)
        assertEquals(2, firstLine?.words?.size)
        assertEquals(1200L, firstLine?.words?.get(0)?.timeMs)
        assertEquals(1500L, firstLine?.words?.get(0)?.endTimeMs)
        assertEquals("Alpha ", firstLine?.words?.get(0)?.text)
        assertEquals(1700L, firstLine?.words?.get(1)?.timeMs)
        assertEquals(2100L, firstLine?.words?.get(1)?.endTimeMs)
        assertEquals("line", firstLine?.words?.get(1)?.text)
        assertEquals(
            firstLine?.text,
            firstLine?.words?.joinToString(separator = "") { it.text }
        )

        assertEquals(3400L, result?.lines?.get(1)?.timeMs)
        assertEquals("Beta", result?.lines?.get(1)?.text)
        assertEquals("Test Song", result?.matchedTitle)
        assertEquals("Test Artist", result?.matchedArtist)
        assertEquals("Test Album", result?.matchedAlbum)
        assertEquals(123.4, result?.matchedDurationSec ?: 0.0, 0.001)
        assertEquals("42", result?.lyricsId)
    }

    @Test
    fun blankWordSyncLineIsStrippedAtIngest() {
        val payload = """
            <wsy>
              <line>
                <linestring></linestring>
                <word>
                  <starttime>5000</starttime>
                  <endtime>6000</endtime>
                  <wordstring></wordstring>
                </word>
              </line>
            </wsy>
        """.trimIndent()

        val lines = PetitLyricsClient.parseWordSyncPayload(payload)

        // Non-lyric lines (blank = instrumental marker) are stripped at
        // ingest; the instrumental stretch is derived from timing silence.
        assertEquals(0, lines.size)
    }

    @Test
    fun decodesType2LineSyncWithPlainTextCompanion() {
        val protectionKey = 0x2345
        val encrypted = buildLineSyncPayload(
            protectionKey = protectionKey,
            timesCentiseconds = intArrayOf(123, 456)
        )
        val syncBase64 = Base64.getEncoder().encodeToString(encrypted)
        val plainBase64 = Base64.getEncoder()
            .encodeToString("First line\nSecond line".toByteArray(Charsets.UTF_8))

        val lines = PetitLyricsClient.decodeLineSyncPayload(syncBase64, plainBase64)

        assertEquals(2, lines.size)
        assertEquals(1230L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(4560L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun lineSyncDecoderHandlesCentisecondCounterRollover() {
        val encrypted = buildLineSyncPayload(
            protectionKey = 0x0102,
            timesCentiseconds = intArrayOf(65_000, 66_000)
        )
        val syncBase64 = Base64.getEncoder().encodeToString(encrypted)
        val plainBase64 = Base64.getEncoder()
            .encodeToString("Before rollover\nAfter rollover".toByteArray(Charsets.UTF_8))

        val lines = PetitLyricsClient.decodeLineSyncPayload(syncBase64, plainBase64)

        assertEquals(650_000L, lines[0].timeMs)
        assertEquals(660_000L, lines[1].timeMs)
    }

    @Test
    fun exactJapaneseTitleAllowsRomanizedArtistMismatchWithAlbumEvidence() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val response = """
            <result>
              <songs>
                <song>
                  <lyricsId>70129</lyricsId>
                  <title>巡恋歌</title>
                  <artist>長渕 剛</artist>
                  <album>風は南から</album>
                  <lyricsType>2</lyricsType>
                  <lyricsData>$encoded</lyricsData>
                </song>
              </songs>
            </result>
        """.trimIndent()

        val candidates = PetitLyricsClient.parseCandidates(response)
        val selected = PetitLyricsClient.selectBestCandidate(
            candidates = candidates,
            requestedTitle = "巡恋歌",
            requestedArtist = "Tsuyoshi Nagabuchi",
            requestedAlbum = "風は南から"
        )

        assertNotNull(selected)
        assertEquals("70129", selected?.lyricsId)
        assertEquals(2, selected?.lyricsType)
    }

    @Test
    fun artistConstrainedQueryAllowsCrossScriptArtistWithDifferentAlbum() {
        val candidate = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = "99",
            title = "君を忘れない",
            artist = "松山千春",
            album = "別アルバム",
            lyricsType = 3,
            lyricsData = "AA=="
        )

        val selected = PetitLyricsClient.selectBestCandidate(
            candidates = listOf(candidate),
            requestedTitle = "君を忘れない",
            requestedArtist = "Chiharu Matsuyama",
            requestedAlbum = "TOUR",
            artistQueryCorroborated = true
        )

        assertNotNull(selected)
        assertTrue(selected?.artistQueryCorroborated == true)
    }

    @Test
    fun titleOnlyQueryStillRejectsCrossScriptArtistWithoutOtherEvidence() {
        val candidate = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = "100",
            title = "君を忘れない",
            artist = "別の歌手",
            album = "別アルバム",
            lyricsType = 3,
            lyricsData = "AA=="
        )

        val selected = PetitLyricsClient.selectBestCandidate(
            candidates = listOf(candidate),
            requestedTitle = "君を忘れない",
            requestedArtist = "Chiharu Matsuyama",
            requestedAlbum = "TOUR",
            artistQueryCorroborated = false
        )

        assertNull(selected)
    }

    @Test
    fun clearlyDifferentTitleIsRejected() {
        val candidate = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = "1",
            title = "乾杯",
            artist = "長渕 剛",
            album = "",
            lyricsType = 2,
            lyricsData = "AA=="
        )

        assertNull(
            PetitLyricsClient.selectBestCandidate(
                candidates = listOf(candidate),
                requestedTitle = "巡恋歌",
                requestedArtist = "Tsuyoshi Nagabuchi",
                requestedAlbum = ""
            )
        )
    }

    @Test
    fun plainCompanionWithoutLyricsIdUsesMetadataInsteadOfFirstResult() {
        val synced = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = null,
            title = "同じタイトル",
            artist = "正しい歌手",
            album = "正しいアルバム",
            lyricsType = 2,
            lyricsData = "AA=="
        )
        val wrongFirst = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = null,
            title = "同じタイトル",
            artist = "別の歌手",
            album = "別のアルバム",
            lyricsType = 1,
            lyricsData = "V3Jvbmc="
        )
        val correctSecond = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = null,
            title = "同じタイトル",
            artist = "正しい歌手",
            album = "正しいアルバム",
            lyricsType = 1,
            lyricsData = "Q29ycmVjdA=="
        )

        val selected = PetitLyricsClient.selectPlainCompanion(
            syncedCandidate = synced,
            plainCandidates = listOf(wrongFirst, correctSecond)
        )

        assertEquals("正しい歌手", selected?.artist)
        assertEquals("Q29ycmVjdA==", selected?.lyricsData)
    }

    @Test
    fun malformedResponseReturnsNull() {
        assertNull(PetitLyricsClient.parseApiResponse("<not-closed"))
    }

    private fun buildLineSyncPayload(
        protectionKey: Int,
        timesCentiseconds: IntArray
    ): ByteArray {
        val bytes = ByteArray(0xcc + timesCentiseconds.size * 2)
        bytes[0x19] = 0 // protection key permutation disabled for this fixture
        writeUInt16Le(bytes, 0x1a, protectionKey)
        writeUInt32Le(bytes, 0x38, timesCentiseconds.size)
        writeUInt16Le(bytes, 0x42, 64)

        timesCentiseconds.forEachIndexed { index, absoluteCs ->
            val modulo = absoluteCs and 0xffff
            val raw = modulo xor protectionKey
            writeUInt16Le(bytes, 0xcc + index * 2, raw)
        }
        return bytes
    }

    private fun writeUInt16Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    @Test
    fun manualHitsMapCandidateTypesToLazyRows() {
        val word = PetitLyricsClient.PetitLyricsCandidate(
            lyricsId = "id-1",
            title = "コネクト",
            artist = "ClariS",
            album = "BIRTHDAY",
            lyricsType = 3,
            lyricsData = "",
            durationSec = 271.0
        )
        val line = word.copy(lyricsId = "id-2", lyricsType = 2)

        val hits = PetitLyricsClient.manualHits(listOf(line, word), "コネクト", "ClariS")

        assertEquals(2, hits.size)
        assertTrue(hits.all { it.provider == "PetitLyrics" })
        assertTrue(hits.all { it.lines.isEmpty() && it.fetch != null })
        val karaoke = hits.first { it.kind == ManualHitKind.KARAOKE }
        val synced = hits.first { it.kind == ManualHitKind.SYNCED }
        assertEquals("PetitLyrics · Karaoke", karaoke.source)
        assertEquals("PetitLyrics · Synced", synced.source)
        assertEquals(271, karaoke.durationSec)
    }
}
