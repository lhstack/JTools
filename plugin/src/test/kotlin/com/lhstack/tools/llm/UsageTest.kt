package com.lhstack.tools.llm

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageTest {
    @Test
    fun `accumulates numbers recursively and appends arrays`() {
        val total = Usage.fromJson(JsonParser.parseString("""{
          "input_tokens": 10,
          "input_tokens_details": {"cached_tokens": 3},
          "iterations": [{"id": 1}],
          "service_tier": "standard"
        }"""))
        total.add(Usage.fromJson(JsonParser.parseString("""{
          "input_tokens": 7,
          "input_tokens_details": {"cached_tokens": 2, "audio_tokens": 1},
          "iterations": [{"id": 2}],
          "service_tier": "priority"
        }""")))

        val json = total.toJson()
        assertEquals(17, json["input_tokens"].asLong)
        assertEquals(5, json["input_tokens_details"].asJsonObject["cached_tokens"].asLong)
        assertEquals(1, json["input_tokens_details"].asJsonObject["audio_tokens"].asLong)
        assertEquals(2, json["iterations"].asJsonArray.size())
        assertEquals("priority", json["service_tier"].asString)
    }

    @Test
    fun `snapshot merge replaces cumulative numbers instead of adding`() {
        val usage = Usage.fromJson(JsonParser.parseString("""{
          "input_tokens": 12,
          "output_tokens": 2,
          "output_tokens_details": {"thinking_tokens": 1}
        }"""))
        usage.mergeSnapshot(Usage.fromJson(JsonParser.parseString("""{
          "output_tokens": 9,
          "output_tokens_details": {"thinking_tokens": 6}
        }""")))

        val json = usage.toJson()
        assertEquals(12, json["input_tokens"].asLong)
        assertEquals(9, json["output_tokens"].asLong)
        assertEquals(6, json["output_tokens_details"].asJsonObject["thinking_tokens"].asLong)
    }
}
