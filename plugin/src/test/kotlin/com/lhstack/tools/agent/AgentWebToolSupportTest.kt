package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentWebToolSupportTest {

    @Test
    fun `default search engines use bing then baidu`() {
        assertEquals(listOf("bing", "baidu"), AgentWebToolSupport.defaultSearchEngineIds)
        assertEquals(listOf("Bing", "Baidu"), AgentWebToolSupport.defaultSearchEngines.map { it.name })
    }

    @Test
    fun `legacy google search engine is removed during normalization`() {
        val ids = AgentWebToolSupport.normalizeSearchEngineIds(listOf("google"))
        val engines = AgentWebToolSupport.normalizeSearchEngines(
            listOf(
                AgentWebSearchEngineState().apply {
                    name = "Google"
                    address = "https://www.google.com/search?q={query}"
                }
            ),
            ids
        )

        assertEquals(listOf("bing", "baidu"), ids)
        assertEquals(listOf("Bing", "Baidu"), engines.map { it.name })
    }

    @Test
    fun `google search engine config is rejected`() {
        val normalized = AgentWebToolSupport.normalizeSearchEngine(
            AgentWebSearchEngineState().apply {
                name = "Search"
                address = "https://www.google.com/search?q={query}"
            }
        )

        assertNull(normalized)
    }
}
