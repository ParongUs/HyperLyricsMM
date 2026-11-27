package com.hyperlyrics.mm.lyrics.ytm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicInnertubeParserTest {

    @Test
    fun `extractLyricsBrowseId prefers timed MPLYt over static MPLYd`() {
        val nextJson = JSONObject(
            """
            {
              "contents": {
                "singleColumnMusicWatchNextResultsRenderer": {
                  "tabbedRenderer": {
                    "watchNextTabbedResultsRenderer": {
                      "tabs": [
                        { "tabRenderer": { "endpoint": { "browseEndpoint": { "browseId": "MPLYd_static123" } } } },
                        { "tabRenderer": { "endpoint": { "browseEndpoint": { "browseId": "MPLYt_4U7yfKKFZLv-1" } } } }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )
        assertEquals("MPLYt_4U7yfKKFZLv-1", YouTubeMusicInnertubeClient.extractLyricsBrowseId(nextJson))
    }

    @Test
    fun `parseBrowseLyricsResponse parses official YouTube Music timedLyricsData`() {
        val browseJson = JSONObject(
            """
            {
              "contents": {
                "elementRenderer": {
                  "newElement": {
                    "type": {
                      "componentType": {
                        "model": {
                          "timedLyricsModel": {
                            "lyricsData": {
                              "sourceMessage": "Source: LyricFind",
                              "timedLyricsData": [
                                {
                                  "lyricLine": "♪",
                                  "cueRange": { "startTimeMilliseconds": "0", "endTimeMilliseconds": "13520" }
                                },
                                {
                                  "lyricLine": "I've been tryna call",
                                  "cueRange": { "startTimeMilliseconds": "27330", "endTimeMilliseconds": "30180" }
                                },
                                {
                                  "lyricLine": "I've been on my own for long enough",
                                  "cueRange": { "startTimeMilliseconds": "30180", "endTimeMilliseconds": "32870" }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val result = YouTubeMusicInnertubeClient.parseBrowseLyricsResponse(
            json = browseJson,
            videoId = "J7p4bzqLvCw",
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 200_000L
        )

        assertNotNull(result)
        result!!
        assertTrue(result.isSynced)
        assertEquals("Source: LyricFind", result.sourceMessage)
        assertEquals(3, result.lines.size)
        assertTrue(result.lines[0].isInterlude)
        assertEquals(0L, result.lines[0].startTimeMs)
        assertEquals("I've been tryna call", result.lines[1].text)
        assertEquals(27330L, result.lines[1].startTimeMs)
        assertEquals(30180L, result.lines[1].endTimeMs)
        assertFalse(result.lines[1].isInterlude)
    }

    @Test
    fun `parseBrowseLyricsResponse falls back to static musicDescriptionShelfRenderer`() {
        val staticJson = JSONObject(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicDescriptionShelfRenderer": {
                        "description": {
                          "runs": [{ "text": "Line one\nLine two\n\nLine three" }]
                        },
                        "footer": {
                          "runs": [{ "text": "Source: Musixmatch" }]
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val result = YouTubeMusicInnertubeClient.parseBrowseLyricsResponse(
            json = staticJson,
            videoId = "dQw4w9WgXcQ",
            title = "Sample",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertNotNull(result)
        result!!
        assertFalse(result.isSynced)
        assertEquals(3, result.lines.size)
        assertEquals("Line two", result.lines[1].text)
        assertEquals("Source: Musixmatch", result.sourceMessage)
    }

    @Test
    fun `extractVideoIdFromUri extracts valid 11-char video ids`() {
        assertEquals(
            "J7p4bzqLvCw",
            YouTubeMusicInnertubeClient.extractVideoIdFromUri("https://i.ytimg.com/vi/J7p4bzqLvCw/sddefault.jpg")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeMusicInnertubeClient.extractVideoIdFromUri("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM")
        )
        assertNull(YouTubeMusicInnertubeClient.extractVideoIdFromUri("https://lh3.googleusercontent.com/abc123"))
    }

    @Test
    fun `parseDurationMs and candidate scoring rank exact song highest`() {
        assertEquals(200_000L, YouTubeMusicInnertubeClient.parseDurationMs("Song • The Weeknd • 3:20"))
        assertEquals(3_735_000L, YouTubeMusicInnertubeClient.parseDurationMs("Live • 1:02:15"))

        val exactScore = YouTubeMusicInnertubeClient.scoreCandidate(
            candidateTitle = "Blinding Lights",
            candidateSubtitle = "The Weeknd • After Hours • 3:20",
            candidateDurationMs = 200_000L,
            expectedTitle = "Blinding Lights",
            expectedArtist = "The Weeknd",
            expectedAlbum = "After Hours",
            expectedDurationMs = 200_040L
        )
        val remixScore = YouTubeMusicInnertubeClient.scoreCandidate(
            candidateTitle = "Blinding Lights (Slowed + Reverb)",
            candidateSubtitle = "Random Channel • 4:45",
            candidateDurationMs = 285_000L,
            expectedTitle = "Blinding Lights",
            expectedArtist = "The Weeknd",
            expectedAlbum = "After Hours",
            expectedDurationMs = 200_040L
        )
        assertTrue("Exact match ($exactScore) must beat remix ($remixScore)", exactScore > remixScore + 30.0)
    }

    @Test
    fun `parseSearchCandidates parses ANDROID_MUSIC musicTwoColumnItemRenderer`() {
        val searchJson = JSONObject(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicTwoColumnItemRenderer": {
                                    "title": { "runs": [{ "text": "Instant Crush (feat. Julian Casablancas)" }] },
                                    "subtitle": {
                                      "runs": [
                                        { "text": "Daft Punk" },
                                        { "text": " ve " },
                                        { "text": "Julian Casablancas" },
                                        { "text": " • " },
                                        { "text": "5:38" },
                                        { "text": " • " },
                                        { "text": "1,2 Mr dinlenme" }
                                      ]
                                    },
                                    "navigationEndpoint": {
                                      "watchEndpoint": { "videoId": "khnokW3Mw24" }
                                    }
                                  }
                                },
                                {
                                  "musicTwoColumnItemRenderer": {
                                    "title": { "runs": [{ "text": "Get Lucky" }] },
                                    "subtitle": { "runs": [{ "text": "Daft Punk • 6:10" }] },
                                    "navigationEndpoint": {
                                      "watchEndpoint": { "videoId": "4D7u5KF7SP8" }
                                    }
                                  }
                                }
                              ]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()
        )

        val candidates = YouTubeMusicInnertubeClient.parseSearchCandidates(
            json = searchJson,
            expectedTitle = "Daft Punk Instant Crush",
            expectedArtist = "",
            expectedAlbum = "",
            expectedDurationMs = 0L
        )

        assertEquals(2, candidates.size)
        assertEquals("khnokW3Mw24", candidates.first().videoId)
        assertEquals("Instant Crush (feat. Julian Casablancas)", candidates.first().title)
        assertEquals("Daft Punk ve Julian Casablancas", candidates.first().parsedArtist)
        assertEquals(338_000L, candidates.first().durationMs)
    }
}
