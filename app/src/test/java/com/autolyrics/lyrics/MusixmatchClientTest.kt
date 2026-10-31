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
        assertEquals("HeyJude", lines[0].words.joinToString("") { it.text })
        assertEquals(listOf(1_500L, 2_000L, 2_500L, 3_000L, 3_500L), lines[0].words.map { it.timeMs })
        assertEquals("Hey", lines[0].words[0].text)
        assertEquals(1_500L, lines[0].words[0].timeMs)
        assertEquals("Jude", lines[0].words.drop(1).joinToString("") { it.text })
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
        assertEquals("君を忘れない", lines[0].words.joinToString("") { it.text })
        assertEquals("君を", lines[0].words[0].text)
        assertEquals(2_000L, lines[0].words[0].timeMs)
        assertEquals(listOf("忘", "れ", "な", "い"), lines[0].words.drop(1).map { it.text })
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

    @Test
    fun richSyncSingleTokenLineSplitsAcrossToNextLineWhenTeIsPolluted() {
        val richSyncBody =
            "[{\"ts\":40.0,\"te\":40.2,\"x\":\"この時にかかっているんだよ\",\"l\":[{\"c\":\"この時にかかっているんだよ\",\"o\":0}]},{\"ts\":43.1,\"te\":44.0,\"x\":\"next\",\"l\":[{\"c\":\"next\",\"o\":0}]}]"
        val json = wrappedRichSync(richSyncBody)

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals("この時にかかっているんだよ", lines[0].words!!.joinToString("") { it.text })
        assertEquals(13, lines[0].words!!.size)
        assertEquals(40_000L, lines[0].words!!.first().timeMs)
        assert(lines[0].words!!.last().timeMs < 43_100L)
        assertEquals(listOf(43_100L), lines[1].words!!.map { it.timeMs })
    }

    @Test
    fun richSyncShortSingleTokenLineStillSplits() {
        val richSyncBody =
            "[{\"ts\":50.0,\"te\":50.1,\"x\":\"どの席になるか\",\"l\":[{\"c\":\"どの席になるか\",\"o\":0}]},{\"ts\":52.5,\"te\":53.0,\"x\":\"ok\",\"l\":[{\"c\":\"ok\",\"o\":0}]}]"
        val json = wrappedRichSync(richSyncBody)

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals("どの席になるか", lines[0].words!!.joinToString("") { it.text })
        assertEquals(7, lines[0].words!!.size)
    }

    @Test
    fun richSyncPunctuationTokensCollapseIntoPrecedingWord() {
        val richSyncBody =
            "[{\"ts\":10.0,\"te\":13.5,\"x\":\"はい！\",\"l\":[{\"c\":\"はい\",\"o\":0},{\"c\":\"！\",\"o\":2.5}]}]"
        val json = wrappedRichSync(richSyncBody)

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(listOf("は", "い！"), lines[0].words!!.map { it.text })
    }

    @Test
    fun richSyncUncoveredLineTailGetsSynthesizedTokens() {
        val words = listOf(
            com.autolyrics.model.LyricWord(timeMs = 30_000L, text = "君を"),
            com.autolyrics.model.LyricWord(timeMs = 31_000L, text = "想う")
        )

        val filled = MusixmatchClient.fillUncoveredText("君を想う夜もある", words, lineEndMs = 35_000L)

        assertEquals("君を想う夜もある", filled.joinToString("") { it.text })
        assertEquals(31_000L, filled[2].timeMs)
        assert(filled.all { it.timeMs in 30_000L..35_000L })
        assertEquals(filled.map { it.timeMs }, filled.map { it.timeMs }.sorted())
        assert(filled.last().timeMs < 35_000L)
    }

    @Test
    fun richSyncUncoveredMiddleRunIsSpreadBeforeNextToken() {
        val words = listOf(
            com.autolyrics.model.LyricWord(timeMs = 0L, text = "hello"),
            com.autolyrics.model.LyricWord(timeMs = 10_000L, text = "world")
        )

        val filled = MusixmatchClient.fillUncoveredText("hello bright world", words, lineEndMs = 12_000L)

        assertEquals("hellobrightworld", filled.joinToString("") { it.text })
        assertEquals("b", filled[1].text)
        assert(filled[1].timeMs in 0L..10_000L)
        assertEquals(10_000L, filled.last().timeMs)
        assertEquals("world", filled.last().text)
    }

    @Test
    fun richSyncLongGapsSpreadMultiCharacterTokensAcrossTheGap() {
        val words = listOf(
            com.autolyrics.model.LyricWord(timeMs = 10_000L, text = "Hello"),
            com.autolyrics.model.LyricWord(timeMs = 11_000L, text = "小悪魔"),
            com.autolyrics.model.LyricWord(timeMs = 17_000L, text = "world")
        )

        val spread = MusixmatchClient.spreadLongGaps(words, lineEndMs = 20_000L)

        assertEquals("Hello小悪魔world", spread.joinToString("") { it.text })
        assertEquals(11_000L, spread[1].timeMs)
        assertEquals(13_000L, spread[2].timeMs)
        assertEquals(15_000L, spread[3].timeMs)
        // Short-gap token stays whole; the final held word spreads to line end.
        assertEquals("Hello", spread[0].text)
        assertEquals(listOf("w", "o", "r", "l", "d"), spread.drop(4).map { it.text })
        assertEquals(19_400L, spread.last().timeMs)
    }

    @Test
    fun richSyncWithoutLongGapsIsUntouched() {
        val words = listOf(
            com.autolyrics.model.LyricWord(timeMs = 1_000L, text = "君を"),
            com.autolyrics.model.LyricWord(timeMs = 2_000L, text = "想う")
        )

        assertEquals(words, MusixmatchClient.spreadLongGaps(words, lineEndMs = 3_000L))
    }

    @Test
    fun manualTrackSearchReadsAliasFieldsAndDuration() {
        val json = """
            {"message":{"header":{"status_code":200},"body":{"track_list":[
              {"track":{"commontrack_id":7,"track_name":"Alias Song","artist_name":"Alias Artist","track_length_seconds":212}}
            ]}}}
        """.trimIndent()

        val candidates = MusixmatchClient.parseTrackSearchResponse(json)

        assertEquals(1, candidates.size)
        assertEquals("Alias Song", candidates[0].title)
        assertEquals("Alias Artist", candidates[0].artist)
        assertEquals(212, candidates[0].durationSec)
    }

    @Test
    fun richSyncChunksWithOutOfOrderOffsetsAreSortedChronologically() {
        val richSyncBody =
            "[{\"ts\":9.5,\"x\":\"君を さえない 忘れない\",\"l\":[{\"c\":\"君を\",\"o\":0.0},{\"c\":\"忘れない\",\"o\":2.5},{\"c\":\"さえない\",\"o\":0.7}]}]"
        val json = wrappedRichSync(richSyncBody)

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(1, lines.size)
        assertEquals(
            listOf(9_500L, 10_200L, 10_650L, 11_100L, 11_550L, 12_000L),
            lines[0].words.map { it.timeMs }
        )
    }

    @Test
    fun richSyncAbsoluteOffsetsAreReinterpretedRelativeToLine() {
        val richSyncBody =
            "[{\"ts\":30.0,\"te\":34.0,\"x\":\"君を想い出せない夜もある\",\"l\":[{\"c\":\"君を\",\"o\":0.0},{\"c\":\"想い出せない\",\"o\":1.2},{\"c\":\"夜も\",\"o\":32.4},{\"c\":\"ある\",\"o\":33.1}]}]"
        val json = wrappedRichSync(richSyncBody)

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals(1, lines.size)
        assertEquals(
            listOf(
                30_000L, 30_600L, 31_200L, 31_400L, 31_600L,
                31_800L, 32_000L, 32_200L, 32_400L, 33_100L
            ),
            lines[0].words.map { it.timeMs }
        )
    }

    @Test
    fun richSyncRelativeOffsetsBeyondLineEndAreLeftAlone() {
        val richSyncBody =
            "[{\"ts\":30.0,\"te\":34.0,\"x\":\"hello world\",\"l\":[{\"c\":\"hello\",\"o\":0.0},{\"c\":\"world\",\"o\":2.0}]}]"
        val json = wrappedRichSync(richSyncBody)

        val lines = MusixmatchClient.parseRichSyncResponse(json)

        assertEquals("helloworld", lines[0].words.joinToString("") { it.text })
        assertEquals(30_000L, lines[0].words.first().timeMs)
        assertEquals(32_000L, lines[0].words.first { it.text == "w" }.timeMs)
    }

    private fun wrappedRichSync(richSyncBody: String): String {
        val escapedBody = com.google.gson.Gson().toJson(richSyncBody)
        return """{"message":{"header":{"status_code":200},"body":{"richsync":{"richsync_body":$escapedBody}}}}"""
    }
}
