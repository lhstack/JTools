package com.lhstack.tools.agent.model.http

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SseParserTest {

    @Test
    fun parsesMultipleDataLinesAndEventName() {
        val parser = parser("event: message_delta\ndata: {\"usage\":" +
            "\ndata: {\"output_tokens\":2}\n\n")

        parser.use {
            val event = it.next()
            assertEquals("message_delta", event?.event)
            assertEquals("{\"usage\":\n{\"output_tokens\":2}", event?.data)
            assertNull(it.next())
        }
    }

    @Test
    fun defaultEventNameAndBomMatchEventsourceStream() {
        val parser = parser("\uFEFFdata: done\r\n\r\n")

        parser.use {
            val event = it.next()
            assertEquals("message", event?.event)
            assertEquals("done", event?.data)
            assertNull(it.next())
        }
    }

    @Test
    fun incompleteEventAtEofIsDropped() {
        val parser = parser("data: incomplete\n")

        parser.use {
            assertNull(it.next())
        }
    }

    @Test
    fun cancelledParserThrowsCancellation() {
        val cancel = ModelCancel()
        cancel.cancel()
        val parser = parser("data: late\n\n", cancel)

        parser.use {
            assertFailsWith<ModelRequestCancelledException> { it.next() }
        }
    }

    private fun parser(content: String, cancel: ModelCancel? = null): SseParser {
        val response: Response = Response.Builder()
            .request(Request.Builder().url("http://localhost/stream").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(content.toResponseBody())
            .build()
        return SseParser(response, response.body!!.source(), cancel)
    }
}
