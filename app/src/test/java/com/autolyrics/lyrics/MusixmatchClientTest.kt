package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchClientTest {

    @Test
    fun parsesAnonymousToken() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {"user_token": "abc123token"}
              }
            }
        """.trimIndent()

        assertEquals("abc123token", MusixmatchClient.parseTokenResponse(json))
    }

    @Test
    fun rejectsUpgradeOnlyToken() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {"user_token": "UpgradeOnly-token"}
              }
            }
        """.trimIndent()

        assertEquals(null, MusixmatchClient.parseTokenResponse(json))
    }

    @Test
    fun parsesMacroTrackAndLineSubtitle() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "macro_calls": {
                    "matcher.track.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "track": {
                            "track_id": 123,
                            "commontrack_id": 456,
                            "track_name": "Hey Jude",
                            "artist_name": "The Beatles",
                            "album_name": "1",
                            "track_length": 431,
                            "has_richsync": 1,
                            "instrumental": 0
                          }
                        }
                      }
                    },
                    "track.subtitles.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "subtitle_list": [
                            {
                              "subtitle": {
                                "subtitle_body": "[00:01.00]Hey Jude\n[00:03.00]Don't make it bad"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = MusixmatchClient.parseMacroResponse(json)

        assertNotNull(result)
        assertEquals(123L, result!!.candidate.trackId)
        assertEquals(456L, result.candidate.commonTrackId)
        assertEquals("Hey Jude", result.candidate.title)
        assertEquals("The Beatles", result.candidate.artist)
        assertEquals("1", result.candidate.album)
        assertEquals(431.0, result.candidate.durationSec ?: 0.0, 0.001)
        assertTrue(result.candidate.hasRichSync)
        assertEquals("[00:01.00]Hey Jude\n[00:03.00]Don't make it bad", result.subtitleBody)
    }

    @Test
    fun capturesRichSyncEmbeddedInMacroResponse() {
        val richSyncBody = """[{"ts":1.5,"x":"Hey Jude","l":[{"c":"Hey","o":0.0},{"c":"Jude","o":0.5}]}]"""
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "macro_calls": {
                    "matcher.track.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "track": {
                            "track_id": 123,
                            "commontrack_id": 456,
                            "track_name": "Hey Jude",
                            "artist_name": "The Beatles",
                            "album_name": "1",
                            "track_length": 431,
                            "has_richsync": 1,
                            "instrumental": 0
                          }
                        }
                      }
                    },
                    "track.richsync.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "richsync": {
                            "richsync_body": "$richSyncBody"
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = MusixmatchClient.parseMacroResponse(json)

        assertNotNull(result)
        assertNotNull(result!!.richSyncResponseJson)
        val lines = MusixmatchClient.parseRichSyncResponse(result.richSyncResponseJson!!)
        assertEquals(1, lines.size)
        assertEquals("Hey Jude", lines[0].text)
        assertEquals(1_500L, lines[0].timeMs)
    }

    @Test
    fun rejectsMacroWhenMatcherTrackCallFails() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "macro_calls": {
                    "matcher.track.get": {
                      "message": {
                        "header": {"status_code": 404},
                        "body": {}
                      }
                    },
                    "track.subtitles.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "subtitle_list": [
                            {"subtitle": {"subtitle_body": "[00:01.00]Wrong song"}}
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertNull(MusixmatchClient.parseMacroResponse(json))
    }

    @Test
    fun parsesLineSubtitleIntoTimedLines() {
        val lines = MusixmatchClient.parseSubtitleBody(
            "[00:01.00]Line one\n[00:03.25]Line two"
        )

        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("Line one", lines[0].text)
        assertEquals(3_250L, lines[1].timeMs)
        assertEquals("Line two", lines[1].text)
    }

    @Test
    fun parsesRichSyncIntoLineAndWordTiming() {
        val richSyncBody = """[{"ts":1.5,"te":4.0,"x":"Hey Jude","l":[{"c":"Hey","o":0.0},{"c":" ","o":0.3},{"c":"Jude","o":0.5}]}]"""
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "richsync": {
                    "richsync_body": "$richSyncBody"
                  }
                }
              }
            }
        """.trimIndent()

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(1, lines.size)
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals("Hey Jude", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals("Hey", lines[0].words[0].text)
        assertEquals(1_500L, lines[0].words[0].timeMs)
        assertEquals("Jude", lines[0].words[1].text)
        assertEquals(2_000L, lines[0].words[1].timeMs)
    }

    @Test
    fun japaneseRichSyncKeepsExactLineTextAndWordTiming() {
        val richSyncBody = """[{"ts":2.0,"te":5.0,"x":"君を忘れない","l":[{"c":"君を","o":0.0},{"c":"忘れない","o":0.8}]}]"""
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {"richsync": {"richsync_body": "$richSyncBody"}}
              }
            }
        """.trimIndent()

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(1, lines.size)
        assertEquals("君を忘れない", lines[0].text)
        assertEquals(2, lines[0].words.size)
        assertEquals("君を", lines[0].words[0].text)
        assertEquals(2_000L, lines[0].words[0].timeMs)
        assertEquals("忘れない", lines[0].words[1].text)
        assertEquals(2_800L, lines[0].words[1].timeMs)
    }

    @Test
    fun manualTrackSearchParsesCandidatesAndSkipsInvalidRows() {
        val json = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {"track": {"commontrack_id": 123, "track_id": "tr1",
                     "name": "Blue Spring", "primary_artist_name": "YOASOBI",
                     "has_richsync": 1}},
                    {"track": {"name": "No id", "primary_artist_name": "X"}},
                    {"track": {"commontrack_id": 456, "name": "Idol",
                     "primary_artist_name": "YOASOBI", "has_richsync": 0}}
                  ]
                }
              }
            }
        """.trimIndent()

        val candidates = MusixmatchClient.parseTrackSearchResponse(json)

        assertEquals(listOf(123L, 456L), candidates.map { it.commonTrackId })
        assertEquals("tr1", candidates.first().trackId)
        assertEquals(true, candidates.first().hasRichSync)
        assertEquals(null, candidates[1].trackId)
        assertEquals("Idol", candidates[1].title)
        assertEquals(false, candidates[1].hasRichSync)
        assertEquals(
            0,
            MusixmatchClient.parseTrackSearchResponse(
                """{"message":{"header":{"status_code":401},"body":{}}}"""
            ).size
        )
    }

    @Test
    fun plainLyricsBodyDropsAdvertisementAndFooterNoise() {
        val body = """
            Advertisement

            first line
            second line
            *** This lyrics text is used for informational purposes only.
        """.trimIndent()

        assertEquals(
            listOf("first line", "second line"),
            MusixmatchClient.cleanPlainBody(body)
        )
    }
}
