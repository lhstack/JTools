package com.lhstack.tools.agent.resources

import com.google.gson.JsonElement
import com.lhstack.tools.agent.model.http.ModelCancel
import com.lhstack.tools.agent.model.tools.McpRuntime
import com.lhstack.tools.concurrent.AgentExecutors
import com.lhstack.tools.db.entity.McpServerEntity
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The management tools request has one deadline covering initialization and listing. */
internal object McpInspectionSupport {
    fun tools(server: McpServerEntity, cwd: String?): JsonElement {
        val cancel = ModelCancel()
        val request =
            AgentExecutors.shared.submit<JsonElement> { McpRuntime(server, 5, cancel, cwd).tools() }
        try {
            return request.get(5, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            request.cancel(true)
            AgentExecutors.shared.submit { cancel.cancel() }
            throw IllegalStateException("获取 MCP 工具列表超时（5 秒）", error)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } catch (error: InterruptedException) {
            request.cancel(true)
            AgentExecutors.shared.submit { cancel.cancel() }
            Thread.currentThread().interrupt()
            throw error
        }
    }
}
