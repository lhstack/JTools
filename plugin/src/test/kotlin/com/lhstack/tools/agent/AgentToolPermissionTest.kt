package com.lhstack.tools.agent

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolPermissionTest {

    @Test
    fun `permission and approval options use short labels with full tooltips`() {
        assertEquals("只读", AgentToolPermissionScope.READ_ONLY.displayName)
        assertEquals("项目", AgentToolPermissionScope.WORKSPACE_WRITE.displayName)
        assertEquals("完全", AgentToolPermissionScope.DANGER_FULL_ACCESS.displayName)
        assertEquals("只读：允许读取项目内文件；项目外完整路径需要完全访问。", AgentToolPermissionScope.READ_ONLY.tooltip)
        assertEquals("项目：允许读取和写入当前项目目录。", AgentToolPermissionScope.WORKSPACE_WRITE.tooltip)
        assertEquals("完全：允许访问项目外完整路径和执行系统级操作。", AgentToolPermissionScope.DANGER_FULL_ACCESS.tooltip)

        assertEquals("自动", AgentToolApprovalPolicy.AUTO_APPROVE.displayName)
        assertEquals("确认", AgentToolApprovalPolicy.CONFIRM_DANGEROUS.displayName)
        assertEquals("拒绝", AgentToolApprovalPolicy.DENY_DANGEROUS.displayName)
        assertEquals("自动：权限范围允许的工具直接执行。", AgentToolApprovalPolicy.AUTO_APPROVE.tooltip)
        assertEquals("确认：危险操作执行前弹窗确认。", AgentToolApprovalPolicy.CONFIRM_DANGEROUS.tooltip)
        assertEquals("拒绝：危险操作直接拦截。", AgentToolApprovalPolicy.DENY_DANGEROUS.tooltip)
    }

    @Test
    fun `permission help text explains scopes policies and tool requirements`() {
        val help = AgentToolPermissionHelp.fullText()

        assertTrue(help.contains("访问权限"))
        assertTrue(help.contains("危险操作策略"))
        assertTrue(help.contains("工具权限"))
        assertTrue(help.contains("read_file"))
        assertTrue(help.contains("write_file / edit_file"))
        assertTrue(help.contains("glob_search / grep_search"))
        assertTrue(help.contains("bash"))
        assertTrue(help.contains("WebFetch / WebSearch"))
        assertTrue(help.contains("项目外完整路径需要完全访问"))
        assertTrue(help.contains("需要项目或完全访问的工具调用会被视为危险操作"))
        assertTrue(help.contains("项目内写入、编辑、创建目录也会受危险操作策略控制"))
        assertTrue(help.contains("jtools_skill_delete"))
        assertTrue(help.contains("jtools_mcp_update_server / jtools_mcp_delete_server"))
        assertTrue(help.contains("jtools_install_plugin_from_file / jtools_uninstall_plugin"))
        assertTrue(help.contains("jtools_get_env_var"))
        assertTrue(help.contains("动态 MCP 工具"))
        assertTrue(help.contains("外部插件工具"))
    }

    @Test
    fun `workspace read commands stay within workspace write tier`() {
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.classifyBashPermission("rg AgentToolRegistry plugin/src/main")
        )
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.classifyBashPermission("find . -name '*.kt'")
        )
    }

    @Test
    fun `explicit absolute path detection supports unix windows drive and unc paths`() {
        assertTrue(AgentToolPermissionSupport.isExplicitAbsolutePath("/tmp/demo.txt"))
        assertTrue(AgentToolPermissionSupport.isExplicitAbsolutePath("~/demo.txt"))
        assertTrue(AgentToolPermissionSupport.isExplicitAbsolutePath("C:\\Users\\demo\\file.txt"))
        assertTrue(AgentToolPermissionSupport.isExplicitAbsolutePath("D:/workspace/file.txt"))
        assertTrue(AgentToolPermissionSupport.isExplicitAbsolutePath("\\\\server\\share\\file.txt"))
        assertFalse(AgentToolPermissionSupport.isExplicitAbsolutePath("src/main/App.kt"))
    }

    @Test
    fun `project external explicit paths require full access`() {
        val projectRoot = Files.createTempDirectory("agent-permission-project")
        val insidePath = projectRoot.resolve("notes.txt").toString()
        val outsidePath = Files.createTempDirectory("agent-permission-outside").resolve("notes.txt").toString()

        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.requiredPermissionForPath(
                projectRoot = projectRoot.toFile(),
                path = insidePath,
                baseRequired = AgentToolPermissionScope.READ_ONLY,
            )
        )
        assertEquals(
            AgentToolPermissionScope.DANGER_FULL_ACCESS,
            AgentToolPermissionSupport.requiredPermissionForPath(
                projectRoot = projectRoot.toFile(),
                path = outsidePath,
                baseRequired = AgentToolPermissionScope.READ_ONLY,
            )
        )
        assertEquals(
            AgentToolPermissionScope.DANGER_FULL_ACCESS,
            AgentToolPermissionSupport.requiredPermissionForPath(
                projectRoot = projectRoot.toFile(),
                path = "C:\\Users\\demo\\file.txt",
                baseRequired = AgentToolPermissionScope.WORKSPACE_WRITE,
            )
        )
    }

    @Test
    fun `relative paths keep their tool permission tier`() {
        val projectRoot = File("/project")

        assertEquals(
            AgentToolPermissionScope.WORKSPACE_WRITE,
            AgentToolPermissionSupport.requiredPermissionForPath(
                projectRoot = projectRoot,
                path = "src/main/App.kt",
                baseRequired = AgentToolPermissionScope.WORKSPACE_WRITE,
            )
        )
    }

    @Test
    fun `unknown tools default to workspace write`() {
        val tool = AgentTool(
            name = "plugin_unknown",
            description = "unknown plugin tool",
            parametersJson = "{}",
            call = { "{}" }
        )

        assertEquals(AgentToolPermissionScope.WORKSPACE_WRITE, tool.requiredPermission)
    }

    @Test
    fun `time commands are read only`() {
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.classifyBashPermission("date")
        )
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.classifyBashPermission("time")
        )
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.classifyBashPermission("Get-Date")
        )
        assertEquals(
            AgentToolPermissionScope.READ_ONLY,
            AgentToolPermissionSupport.classifyBashPermission("Get-ComputerInfo | Select-Object OsName, OsVersion")
        )
    }

    @Test
    fun `dangerous shell commands require full access`() {
        assertEquals(
            AgentToolPermissionScope.DANGER_FULL_ACCESS,
            AgentToolPermissionSupport.classifyBashPermission("rm -rf /tmp/demo")
        )
        assertEquals(
            AgentToolPermissionScope.DANGER_FULL_ACCESS,
            AgentToolPermissionSupport.classifyBashPermission("cat /etc/hosts")
        )
    }

    @Test
    fun `deny dangerous policy rejects dangerous project writes`() {
        val outcome = AgentToolPermissionSupport.evaluate(
            selectedScope = AgentToolPermissionScope.WORKSPACE_WRITE,
            approvalPolicy = AgentToolApprovalPolicy.DENY_DANGEROUS,
            requiredScope = AgentToolPermissionScope.WORKSPACE_WRITE
        )

        assertFalse(outcome.allowed)
        assertFalse(outcome.requiresConfirmation)
    }

    @Test
    fun `confirm dangerous policy asks before project writes`() {
        val outcome = AgentToolPermissionSupport.evaluate(
            selectedScope = AgentToolPermissionScope.WORKSPACE_WRITE,
            approvalPolicy = AgentToolApprovalPolicy.CONFIRM_DANGEROUS,
            requiredScope = AgentToolPermissionScope.WORKSPACE_WRITE
        )

        assertTrue(outcome.allowed)
        assertTrue(outcome.requiresConfirmation)
    }

    @Test
    fun `confirm dangerous policy asks before full access commands`() {
        val outcome = AgentToolPermissionSupport.evaluate(
            selectedScope = AgentToolPermissionScope.DANGER_FULL_ACCESS,
            approvalPolicy = AgentToolApprovalPolicy.CONFIRM_DANGEROUS,
            requiredScope = AgentToolPermissionScope.DANGER_FULL_ACCESS
        )

        assertTrue(outcome.allowed)
        assertTrue(outcome.requiresConfirmation)
    }

    @Test
    fun `confirm dangerous policy does not ask before read only tools`() {
        val outcome = AgentToolPermissionSupport.evaluate(
            selectedScope = AgentToolPermissionScope.READ_ONLY,
            approvalPolicy = AgentToolApprovalPolicy.CONFIRM_DANGEROUS,
            requiredScope = AgentToolPermissionScope.READ_ONLY
        )

        assertTrue(outcome.allowed)
        assertFalse(outcome.requiresConfirmation)
    }
}
