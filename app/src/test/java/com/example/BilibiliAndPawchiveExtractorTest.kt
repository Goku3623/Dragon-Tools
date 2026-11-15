package com.example

import com.example.data.downloader.BackendApiClient
import com.example.data.downloader.ExtractorException
import com.example.data.downloader.MediaExtractionEngine
import com.example.data.model.DownloaderErrorType
import com.example.data.model.MediaPlatform
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BilibiliAndPawchiveExtractorTest {

    @Test
    fun testBilibiliTvFullLengthDashVideoAndPlayEpisodeExtraction() {
        val mockResponses = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val (code, mime, body) = when {
                url.contains("bilibili.tv/en/video/4790123456") -> Triple(
                    200,
                    "text/html",
                    """
                    <html>
                      <head>
                        <meta property="og:title" content="Full 15 Min Gameplay Showcase - Bilibili.tv" />
                        <meta property="og:image" content="https://pic.bstarstatic.com/cover/demo.jpg" />
                      </head>
                      <body><div>Bilibili</div></body>
                    </html>
                    """.trimIndent()
                )
                url.contains("api.bilibili.tv/intl/gateway/web/playurl") && url.contains("aid=4790123456") -> Triple(
                    200,
                    "application/json",
                    """
                    {
                      "code": 0,
                      "message": "0",
                      "data": {
                        "playurl": {
                          "duration": 900000,
                          "video": [
                            {
                              "stream_info": { "quality": 80, "desc_words": "1080P HD", "need_vip": false },
                              "video_resource": {
                                "url": "https://upos-sz-mirroraliov.bilivideo.com/upgcxcode/12/34/full_1080p.m4s?token=abc",
                                "backup_url": [
                                  "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/12/34/full_1080p_bak.m4s?token=abc"
                                ],
                                "bandwidth": 4500000,
                                "codecs": "avc1.640028",
                                "width": 1920,
                                "height": 1080,
                                "duration": 900000,
                                "size": 506250000
                              }
                            },
                            {
                              "stream_info": { "quality": 64, "desc_words": "720P", "need_vip": false },
                              "video_resource": {
                                "url": "https://upos-sz-mirroraliov.bilivideo.com/upgcxcode/12/34/full_720p.m4s?token=abc",
                                "backup_url": [],
                                "bandwidth": 2200000,
                                "codecs": "avc1.4d401f",
                                "width": 1280,
                                "height": 720,
                                "duration": 900000,
                                "size": 247500000
                              }
                            }
                          ],
                          "audio_resource": [
                            {
                              "url": "https://upos-sz-mirroraliov.bilivideo.com/upgcxcode/12/34/full_audio.m4s?token=abc",
                              "backup_url": ["https://upos-hz-mirrorakam.akamaized.net/upgcxcode/12/34/full_audio_bak.m4s"],
                              "bandwidth": 192000,
                              "codecs": "mp4a.40.2",
                              "duration": 900000,
                              "size": 21600000
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                )
                url.contains("bilibili.tv/en/play/105678/112233") -> Triple(
                    200,
                    "text/html",
                    "<html><head><title>Anime Series - Bilibili</title></head><body></body></html>"
                )
                url.contains("api.bilibili.tv/intl/gateway/web/v2/ogv/view/app/season") && url.contains("ep_id=112233") -> Triple(
                    200,
                    "application/json",
                    """
                    {
                      "code": 0,
                      "data": {
                        "title": "Dragon Chronicles",
                        "horizontal_cover": "https://pic.bstarstatic.com/ogv/ep1.jpg",
                        "modules": [
                          {
                            "data": {
                              "episodes": [
                                {
                                  "episode_id": "112233",
                                  "aid": 99887766,
                                  "title_display": "EP 1",
                                  "duration": 1440000
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                url.contains("api.bilibili.tv/intl/gateway/web/playurl") && url.contains("ep_id=112233") -> Triple(
                    200,
                    "application/json",
                    """
                    {
                      "code": 0,
                      "data": {
                        "playurl": {
                          "duration": 1440000,
                          "video": [
                            {
                              "stream_info": { "quality": 80, "desc_words": "1080P" },
                              "video_resource": {
                                "url": "https://upos-sz-mirroraliov.bilivideo.com/ep1_1080p.m4s",
                                "bandwidth": 3800000,
                                "width": 1920,
                                "height": 1080
                              }
                            }
                          ],
                          "audio_resource": [
                            {
                              "url": "https://upos-sz-mirroraliov.bilivideo.com/ep1_audio.m4s",
                              "bandwidth": 128000
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                )
                url.contains("bilibili.tv/en/video/999000111") -> Triple(
                    200,
                    "text/html",
                    "<html><head><title>VIP Locked</title></head><body>VIP Only</body></html>"
                )
                url.contains("api.bilibili.tv/intl/gateway/web/") && url.contains("999000111") -> Triple(
                    200,
                    "application/json",
                    """{"code": -10403, "message": "VIP required", "data": {"playurl": {"video": [{"stream_info": {"need_vip": true}, "video_resource": {"url": ""}}]}}}"""
                )
                else -> Triple(404, "text/plain", "Not Found")
            }

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody(mime.toMediaType()))
                .build()
        }

        val testClient = OkHttpClient.Builder().addInterceptor(mockResponses).build()
        val backend = BackendApiClient(testClient)
        val engine = MediaExtractionEngine(testClient, backend, null)

        // 1. Standard bilibili.tv full-length video extraction with DASH video + audio pairing
        val videoInfo = engine.extractMediaInfo("https://www.bilibili.tv/en/video/4790123456")
        assertEquals(MediaPlatform.BILIBILI, videoInfo.platform)
        assertEquals(900L, videoInfo.durationSeconds)
        assertEquals("15:00", videoInfo.durationFormatted)
        assertEquals("Full 15 Min Gameplay Showcase", videoInfo.title)
        val bestFormat = videoInfo.videoFormats.first()
        assertTrue(bestFormat.streamUrl.contains("full_1080p.m4s"))
        assertTrue(bestFormat.needsMerging)
        assertEquals(
            "https://upos-sz-mirroraliov.bilivideo.com/upgcxcode/12/34/full_audio.m4s?token=abc",
            bestFormat.secondaryAudioStreamUrl
        )
        assertEquals("https://www.bilibili.tv/", bestFormat.requestHeaders["Referer"])

        // 2. Anime/Drama episode /play/<seasonId>/<epId> full 24-minute episode extraction
        val epInfo = engine.extractMediaInfo("https://www.bilibili.tv/en/play/105678/112233")
        assertEquals(MediaPlatform.BILIBILI, epInfo.platform)
        assertEquals("Dragon Chronicles - EP 1", epInfo.title)
        assertEquals(1440L, epInfo.durationSeconds)
        assertEquals("24:00", epInfo.durationFormatted)
        assertTrue(epInfo.videoFormats.first().streamUrl.contains("ep1_1080p.m4s"))

        // 3. VIP-locked video without credentials must fail cleanly rather than synthesizing fake media
        try {
            engine.extractMediaInfo("https://www.bilibili.tv/en/video/999000111")
            fail("Locked VIP Bilibili video must throw PRIVATE_OR_RESTRICTED")
        } catch (ex: ExtractorException) {
            assertEquals(DownloaderErrorType.PRIVATE_OR_RESTRICTED, ex.errorType)
        }
    }

    @Test
    fun testPawchivePwFullLengthExtractionIgnoresPreviewAndParsesFullHlsDuration() {
        val mockResponses = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val (code, mime, body) = when {
                url.contains("pawchive.pw/posts/full-pack-8821") -> Triple(
                    200,
                    "text/html",
                    """
                    <html>
                      <head>
                        <meta property="og:title" content="Full 14 Minute Showcase - Pawchive" />
                        <meta property="og:image" content="https://cdn.pawchive.pw/posters/8821.jpg" />
                        <script type="application/ld+json">
                          {
                            "@type": "VideoObject",
                            "name": "Full 14 Minute Showcase",
                            "duration": "PT14M0S",
                            "author": { "name": "StudioArchive" }
                          }
                        </script>
                      </head>
                      <body>
                        <script>
                          self.__next_f.push([1,"{\"preview_url\":\"https:\\/\\/cdn.pawchive.pw\\/preview\\/teaser_20s.mp4\",\"hls_url\":\"https:\\/\\/cdn.pawchive.pw\\/streams\\/8821\\/master.m3u8?sig=fullkey\"}"])
                        </script>
                      </body>
                    </html>
                    """.trimIndent()
                )
                url.contains("cdn.pawchive.pw/streams/8821/master.m3u8") -> Triple(
                    200,
                    "application/vnd.apple.mpegurl",
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,FRAME-RATE=60
                    https://cdn.pawchive.pw/streams/8821/1080p.m3u8?sig=fullkey
                    #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,FRAME-RATE=30
                    https://cdn.pawchive.pw/streams/8821/720p.m3u8?sig=fullkey
                    """.trimIndent()
                )
                url.contains("cdn.pawchive.pw/streams/8821/1080p.m3u8") -> Triple(
                    200,
                    "application/vnd.apple.mpegurl",
                    buildString {
                        appendLine("#EXTM3U")
                        appendLine("#EXT-X-TARGETDURATION:10")
                        repeat(84) { idx ->
                            appendLine("#EXTINF:10.0,")
                            appendLine("seg_$idx.ts")
                        }
                        appendLine("#EXT-X-ENDLIST")
                    }
                )
                url.contains("pawchive.pw/posts/locked-post-404") -> Triple(
                    403,
                    "text/html",
                    "<html><head><title>Members Only</title></head><body>Login required</body></html>"
                )
                else -> Triple(404, "text/plain", "Not Found")
            }

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody(mime.toMediaType()))
                .build()
        }

        val testClient = OkHttpClient.Builder().addInterceptor(mockResponses).build()
        val backend = BackendApiClient(testClient)
        val engine = MediaExtractionEngine(testClient, backend, null)

        val info = engine.extractMediaInfo("https://pawchive.pw/posts/full-pack-8821")
        assertEquals(MediaPlatform.PAWCHIVE, info.platform)
        assertEquals("Full 14 Minute Showcase", info.title)
        assertEquals("StudioArchive", info.creator)
        assertEquals(840L, info.durationSeconds)
        assertEquals("14:00", info.durationFormatted)
        assertTrue(
            "Best stream must select the 1080p full-length playlist, not teaser_20s",
            info.videoFormats.first().streamUrl.contains("1080p.m3u8?sig=fullkey")
        )
        assertFalse(
            "Preview teaser URL must never be included in extracted formats",
            info.videoFormats.any { it.streamUrl.contains("teaser_20s") }
        )
        assertEquals("https://pawchive.pw/", info.videoFormats.first().requestHeaders["Referer"])

        try {
            engine.extractMediaInfo("https://pawchive.pw/posts/locked-post-404")
            fail("Inaccessible Pawchive post must throw PRIVATE_OR_RESTRICTED")
        } catch (ex: ExtractorException) {
            assertEquals(DownloaderErrorType.PRIVATE_OR_RESTRICTED, ex.errorType)
        }
    }
}
