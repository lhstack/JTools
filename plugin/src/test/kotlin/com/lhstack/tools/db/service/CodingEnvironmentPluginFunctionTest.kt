package com.lhstack.tools.db.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingEnvironmentPluginFunctionTest {
    @Test
    fun `parses plugin function keys from json object`() {
        val config = CodingEnvironmentService.parseConfig(
            """
            {
              "plugin_functions": {
                "plugin:text-tools:text_transform": true,
                "plugin:text-tools:text_apply_function": false
              },
              "plugin_functions_include_new": false
            }
            """.trimIndent()
        )
        assertTrue(
            CodingEnvironmentService.pluginFunctionEnabled(
                config,
                "plugin:text-tools:text_transform",
                "text_transform",
            )
        )
        assertFalse(
            CodingEnvironmentService.pluginFunctionEnabled(
                config,
                "plugin:text-tools:text_apply_function",
                "text_apply_function",
            )
        )
        assertFalse(
            CodingEnvironmentService.pluginFunctionEnabled(
                config,
                "plugin:other:new_fn",
                "new_fn",
            )
        )
    }
}
