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
class PatreonExtractorTest {

    @Test
    fun testPatreonFullLengthExtractionRejectsPreviewClipAndEnforcesPaywall() {
        val mockResponses = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val (code, mime, body) = when {
                url.contains("/api/posts/123456") -> Triple(
                    200,
                    "application/vnd.api+json",
                    """
                    {
                      "data": {
                        "id": "123456",
                        "type": "post",
                        "attributes": {
                          "title": "Full 7 Minute Episode",
                          "is_paid": false,
                          "current_user_can_view": true,
                          "post_metadata": {
                            "video_preview": { "mux_playback_id": "PREVIEW30S" },
                            "platform": { "mux_playback_id": "FULL420S", "mux_token": "tok123" }
                          }
                        },
                        "relationships": {
                          "video": { "data": { "type": "media", "id": "m_full" } },
                          "video_preview": { "data": { "type": "media", "id": "m_prev" } }
                        }
                      },
                      "included": [
                        {
                          "id": "m_prev",
                          "type": "media",
                          "attributes": {
                            "owner_relationship": "video_preview",
                            "is_preview": true,
                            "metadata": { "duration": 30, "mux_playback_id": "PREVIEW30S" }
                          }
                        },
                        {
                          "id": "m_full",
                          "type": "media",
                          "attributes": {
                            "owner_relationship": "main",
                            "metadata": { "mux_playback_id": "FULL420S", "token": "tok123" }
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
                url.contains("/api/posts/999999") -> Triple(
                    403,
                    "application/vnd.api+json",
                    """{"errors":[{"status":"403","title":"Forbidden"}]}"""
                )
                url.contains("patreon.com/posts/locked-post-999999") -> Triple(
                    200,
                    "text/html",
                    """<html><head><title>Locked</title></head><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"bootstrapEnvelope":{"pageBootstrap":{"post":{"data":{"attributes":{"title":"Locked Post","is_paid":true,"current_user_can_view":false}}}}}}}}</script></body></html>"""
                )
                url.contains("patreon.com/posts/full-episode-123456") -> Triple(
                    200,
                    "text/html",
                    """<html><head><title>Full 7 Minute Episode</title></head><body>https://stream.mux.com/PREVIEW30S.m3u8</body></html>"""
                )
                url.contains("stream.mux.com/FULL420S.m3u8") -> Triple(
                    200,
                    "application/vnd.apple.mpegurl",
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,FRAME-RATE=60
                    https://stream.mux.com/full_1080p.m3u8?token=tok123
                    """.trimIndent()
                )
                url.contains("stream.mux.com/full_1080p.m3u8") -> Triple(
                    200,
                    "application/vnd.apple.mpegurl",
                    buildString {
                        appendLine("#EXTM3U")
                        appendLine("#EXT-X-TARGETDURATION:10")
                        repeat(42) { idx ->
                            appendLine("#EXTINF:10.0,")
                            appendLine("seg_$idx.ts")
                        }
                        appendLine("#EXT-X-ENDLIST")
                    }
                )
                url.contains("stream.mux.com/PREVIEW30S.m3u8") -> Triple(
                    200,
                    "application/vnd.apple.mpegurl",
                    """
                    #EXTM3U
                    #EXTINF:30.0,
                    preview.ts
                    #EXT-X-ENDLIST
                    """.trimIndent()
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

        // 1. Unlocked Patreon post must resolve full 420s HLS stream & preserve token, ignoring 30s preview
        val info = engine.extractMediaInfo("https://www.patreon.com/posts/full-episode-123456")
        assertEquals(MediaPlatform.PATREON, info.platform)
        assertEquals(420L, info.durationSeconds)
        assertEquals("7:00", info.durationFormatted)
        assertTrue(
            "Best stream URL must target full 1080p HLS playlist with token, not PREVIEW30S",
            info.videoFormats.first().streamUrl.contains("full_1080p.m3u8?token=tok123")
        )
        assertFalse(
            "Preview clip must never appear in selected stream or fallbacks",
            info.videoFormats.any { it.streamUrl.contains("PREVIEW30S") || it.fallbackStreamUrls.any { fb -> fb.contains("PREVIEW30S") } }
        )

        // 2. Locked paid Patreon post must strictly throw PRIVATE_OR_RESTRICTED
        try {
            engine.extractMediaInfo("https://www.patreon.com/posts/locked-post-999999")
            fail("Locked Patreon post must reject unauthorized extraction")
        } catch (ex: ExtractorException) {
            assertEquals(DownloaderErrorType.PRIVATE_OR_RESTRICTED, ex.errorType)
        }
    }
}
