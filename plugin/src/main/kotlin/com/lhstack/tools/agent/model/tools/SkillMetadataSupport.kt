package com.lhstack.tools.agent.model.tools

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Skill 元数据与可用性检测。完全照抄 awake-claw common/skill_metadata.rs。
 *
 * 从 SKILL.md 的 YAML frontmatter 读取 description / supportPlatformTypes / checkScripts，
 * 再据此判断技能在当前环境是否可用：
 *  - 有 checkScripts：逐个执行，全成功才可用（单脚本 30s 超时）。
 *  - 无 checkScripts 有 supportPlatformTypes：当前平台在列才可用。
 *  - 都没有：默认可用。
 *
 * frontmatter 解析用 snakeyaml（IntelliJ 平台自带），因为要读数组字段，
 * 现有的 SimpleYamlParser 只支持 Map<String,String>。
 */
data class SkillMetadata(
    val description: String?,
    val supportPlatformTypes: List<String>,
    val checkScripts: List<String>,
    val parseError: String?,
)

data class SkillAvailability(
    val available: Boolean,
    val failReason: String?,
)

object SkillMetadataSupport {

    /** 照抄 skill_metadata：解析 frontmatter，异常时记录 parseError。 */
    fun skillMetadata(content: String): SkillMetadata {
        val yaml = frontmatter(content)
            ?: return SkillMetadata(null, emptyList(), emptyList(), null)
        return try {
            val parsed = Yaml().load<Any?>(yaml)
            val map = parsed as? Map<*, *> ?: emptyMap<Any?, Any?>()
            SkillMetadata(
                description = normalizeOptionalText(map["description"]?.toString()),
                supportPlatformTypes = normalizeStringList(map["supportPlatformTypes"]),
                checkScripts = normalizeStringList(map["checkScripts"]),
                parseError = null,
            )
        } catch (e: Throwable) {
            SkillMetadata(null, emptyList(), emptyList(), e.message ?: e.toString())
        }
    }

    /** 照抄 skill_availability。 */
    fun skillAvailability(skillDir: File, metadata: SkillMetadata): SkillAvailability {
        metadata.parseError?.let {
            return unavailable("Skill 元数据解析失败: $it")
        }
        if (metadata.checkScripts.isNotEmpty()) {
            return checkScriptsAvailability(skillDir, metadata.checkScripts)
        }
        return platformAvailability(metadata.supportPlatformTypes)
    }

    /** 照抄 frontmatter：提取 --- 包裹的 YAML 段。 */
    private fun frontmatter(content: String): String? {
        val stripped = content.removePrefix("---")
        if (stripped.length == content.length) return null
        val body = when {
            stripped.startsWith("\n") -> stripped.substring(1)
            stripped.startsWith("\r\n") -> stripped.substring(2)
            else -> return null
        }
        val endLf = body.indexOf("\n---")
        val endCrlf = body.indexOf("\r\n---")
        val end = when {
            endLf < 0 -> endCrlf
            endCrlf < 0 -> endLf
            else -> minOf(endLf, endCrlf)
        }
        if (end < 0) return null
        return body.substring(0, end)
    }

    private fun checkScriptsAvailability(skillDir: File, checkScripts: List<String>): SkillAvailability {
        for (script in checkScripts) {
            val reason = runCheckScript(skillDir, script)
            if (reason != null) {
                return unavailable(reason)
            }
        }
        return available()
    }

    /** 照抄 run_check_script：sh -c / cmd /C 执行，30s 超时，非零退出取首行错误。 */
    private fun runCheckScript(skillDir: File, script: String): String? {
        val builder = shellProcessBuilder(script)
            .directory(skillDir)
            .redirectErrorStream(false)
        val process = try {
            builder.start()
        } catch (e: Throwable) {
            return "可用检测脚本 `$script` 启动失败: ${e.message ?: e.toString()}"
        }
        val finished = try {
            process.waitFor(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return "可用检测脚本 `$script` 状态读取失败: ${e.message}"
        }
        if (!finished) {
            process.destroyForcibly()
            return "可用检测脚本 `$script` 超时"
        }
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        if (process.exitValue() == 0) {
            return null
        }
        val detail = firstNonEmptyLine(stderr)
            ?: firstNonEmptyLine(stdout)
            ?: "退出码 ${process.exitValue()}"
        return "可用检测脚本 `$script` 失败: $detail"
    }

    /** 照抄 platform_availability。 */
    private fun platformAvailability(supportedPlatforms: List<String>): SkillAvailability {
        if (supportedPlatforms.isEmpty()) {
            return available()
        }
        val current = currentPlatformType()
        if (supportedPlatforms.any { it.equals(current, ignoreCase = true) }) {
            return available()
        }
        return unavailable("$current 平台不可用，仅支持 ${supportedPlatforms.joinToString(", ")} 平台")
    }

    private fun currentPlatformType(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> "macos"
            os.contains("win") -> "windows"
            os.contains("nux") || os.contains("nix") -> "linux"
            else -> os
        }
    }

    private fun shellProcessBuilder(command: String): ProcessBuilder {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            ProcessBuilder("cmd", "/C", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
    }

    private fun normalizeOptionalText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizeStringList(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { normalizeOptionalText(it?.toString()) }
    }

    private fun firstNonEmptyLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private fun available() = SkillAvailability(available = true, failReason = null)

    private fun unavailable(reason: String) = SkillAvailability(available = false, failReason = reason)
}
