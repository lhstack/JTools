package com.lhstack.tools.agent.resources

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMediaServerTest {
    @Test
    fun `authorized media streams ranges and rejects unknown tokens`() {
        val file = Files.createTempFile("jtools-media", ".webm").toFile()
        try {
            file.writeBytes(ByteArray(100) { it.toByte() })
            LocalMediaServer().use { server ->
                val url = server.register(file).getValue("url")
                val client = HttpClient.newHttpClient()
                fun request(
                    range: String? = null,
                    target: String = url,
                    method: String = "GET",
                ): HttpResponse<ByteArray> {
                    val builder =
                        HttpRequest.newBuilder(URI(target))
                            .method(method, HttpRequest.BodyPublishers.noBody())
                    range?.let { builder.header("Range", it) }
                    return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                }
                assertEquals(100, request().body().size)
                val partial = request("bytes=10-19")
                assertEquals(206, partial.statusCode())
                assertEquals("bytes 10-19/100", partial.headers().firstValue("Content-Range").get())
                assertEquals((10..19).map { it.toByte() }, partial.body().toList())
                assertEquals(5, request("bytes=-5").body().size)
                assertEquals(416, request("bytes=100-").statusCode())
                assertEquals(404, request(target = url + "unknown").statusCode())
                assertEquals(0, request(method = "HEAD").body().size)
            }
        } finally {
            file.delete()
        }
    }
}
