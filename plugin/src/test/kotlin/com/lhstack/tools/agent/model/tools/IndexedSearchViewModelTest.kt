package com.lhstack.tools.agent.model.tools

import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class IndexedSearchViewModelTest {
    @Test
    fun `getters return constructor values without recursive property access`() {
        val project = proxy(Project::class.java)
        val model = proxy(ChooseByNameModel::class.java)
        val viewModel = IndexedSearchViewModel(project, model, false, 101)

        assertSame(project, viewModel.project)
        assertSame(model, viewModel.model)
        assertFalse(viewModel.isSearchInAnyPlace)
        assertEquals(101, viewModel.maximumListSizeLimit)
        assertEquals("AgentChatPanel", viewModel.transformPattern("AgentChatPanel"))
    }

    private fun <T> proxy(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        },
    )
}
