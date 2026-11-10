package com.lhstack.tools.agent.resources

import com.intellij.openapi.project.Project
import com.lhstack.tools.db.service.*

/**
 * Catalog observations for prompt assembly; tools refresh through the same project and
 * authorization policy.
 */
data class RuntimeResourceSnapshot(
    val skills: List<SkillSource>,
    val projectSkills: List<ResourceConfigService.SkillDirectoryRecord>,
    val mcps: Map<String, com.lhstack.tools.db.entity.McpServerEntity>,
    val projectAgents: Map<String, ProjectSubagent>,
    val refresh: (() -> RuntimeResourceSnapshot)? = null,
) {
    fun prompt(): String = buildString {
        append(
            "\n## 资源来源\n同名全局与项目资源互不覆盖。skills_view 必须传 scope=global/project。查询所有可用 MCP 服务使用 cli mcp.list，不传 scope/id；获取某服务的工具定义使用 mcp.tools，必须传清单中的 scope 和 id；执行使用 mcp.call。配置变更立即影响后续工具调用，以实时列表为准。\n"
        )
        projectSkills
            .filter { it.available }
            .forEach { append("- Skill [project] ${it.name}: ${it.description.orEmpty()}\n") }
        mcps.forEach { (ref, server) ->
            val parts = ref.split(":", limit = 3)
            append("- MCP [${parts[0]}] id=${parts[2]} ${server.name}\n")
        }
    }
}

object RuntimeResourceResolver {
    fun resolve(
        project: Project?,
        globalSkills: Set<String>,
        environment: CodingEnvironmentConfig?,
        refreshEnvironment: (() -> CodingEnvironmentConfig)? = null,
        refreshGlobalSkills: (() -> Set<String>)? = null,
    ): RuntimeResourceSnapshot {
        val scoped = ScopedResources.forProject(project)
        val local = if (project == null) emptyList() else scoped.skills("project").listSkills()
        val sources = buildList {
            add(SkillSource("global", ResourceConfigService.global.skillsRootDir(), globalSkills))
            if (project != null)
                add(
                    SkillSource(
                        "project",
                        scoped.skills("project").skillsRootDir(),
                        local.filter { it.available }.map { it.name }.toSet(),
                    )
                )
        }
        val servers = linkedMapOf<String, com.lhstack.tools.db.entity.McpServerEntity>()
        if (environment != null)
            McpService.list()
                .filter {
                    it.enabled != 0 &&
                        (environment.resources.mcp[it.id.toString()]
                            ?: environment.resources.mcpIncludeNew)
                }
                .forEach { servers["global:mcp:${it.id}"] = it }
        if (project != null)
            scoped.mcps("project").forEach { row ->
                val id = row.get("id").asString
                servers["project:mcp:$id"] = scoped.mcp("project", id)
            }
        return RuntimeResourceSnapshot(
            sources,
            local,
            servers,
            if (project == null) emptyMap() else scoped.projectAgents(),
            refresh = {
                val currentEnvironment = refreshEnvironment?.invoke() ?: environment
                val currentSkills =
                    refreshGlobalSkills?.invoke()
                        ?: if (currentEnvironment != null)
                            CodingEnvironmentService.enabledSkillNames(
                                currentEnvironment,
                                ResourceConfigService.global.listSkills().map { it.name }.toSet(),
                            )
                        else globalSkills
                resolve(
                    project,
                    currentSkills,
                    currentEnvironment,
                    refreshEnvironment,
                    refreshGlobalSkills,
                )
            },
        )
    }
}
