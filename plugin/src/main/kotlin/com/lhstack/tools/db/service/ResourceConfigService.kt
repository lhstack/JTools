package com.lhstack.tools.db.service

import com.lhstack.tools.const.Const
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Skills 固定使用 JTOOLS_PLUGIN_HOME/skills 目录，不写入 XML。 */
object ResourceConfigService {

    fun skillsRootDir(): File = defaultSkillsRootDir().apply { mkdirs() }

    fun listSkills(): List<SkillDirectoryRecord> {
        val root = skillsRootDir()
        if (!root.isDirectory) return emptyList()
        return Files.walk(root.toPath()).use { stream ->
            stream.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("SKILL.md")) }
                .map { skillDirectoryRecord(root.toPath(), it) }
                .toList()
                .sortedBy { it.name }
        }
    }

    fun skillFileTree(name: String): SkillFileNode {
        val dir = resolveSkillDir(name)
        return buildFileTree(dir, dir)
    }

    fun readSkillFile(name: String, relativePath: String?): SkillFileContent {
        val dir = resolveSkillDir(name)
        val path = resolveSkillFile(dir, relativePath ?: "SKILL.md")
        return SkillFileContent(name, dir.toPath().relativize(path.toPath()).toString().replace('\\', '/'), path.readText())
    }

    fun writeSkillFile(name: String, relativePath: String, content: String) {
        val dir = resolveSkillDir(name)
        resolveSkillFile(dir, relativePath).writeText(content.replace("\\r\\n", "\\n"))
    }

    fun createSkillFile(name: String, parentPath: String?, fileName: String): String {
        val root = resolveSkillDir(name)
        val parent = resolveSkillDirectory(root, parentPath)
        val file = resolveNewSkillChild(root, parent, fileName)
        file.writeText("", Charsets.UTF_8)
        return root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    }

    fun createSkillDirectory(name: String, parentPath: String?, directoryName: String): String {
        val root = resolveSkillDir(name)
        val parent = resolveSkillDirectory(root, parentPath)
        val directory = resolveNewSkillChild(root, parent, directoryName)
        require(directory.mkdir()) { "目录创建失败: $directoryName" }
        return root.toPath().relativize(directory.toPath()).toString().replace('\\', '/')
    }

    fun deleteSkill(name: String) {
        val dir = resolveSkillDir(name)
        require(dir.deleteRecursively()) { "Skill 删除失败: $name" }
    }

    fun deleteSkillEntry(name: String, relativePath: String) {
        val root = resolveSkillDir(name)
        val normalized = relativePath.replace('\\', '/').trim('/')
        require(normalized.isNotBlank()) { "不能删除 Skill 根目录" }
        require(!normalized.split('/').contains("..")) { "Skill 路径无效: $relativePath" }
        val entry = canonicalFile(root.resolve(normalized))
        require(entry.toPath().startsWith(root.toPath()) && entry.exists()) { "Skill 文件或目录不存在: $relativePath" }
        require(entry != root) { "不能删除 Skill 根目录" }
        require(entry.deleteRecursively()) { "删除失败: $relativePath" }
    }

    data class SkillDirectoryRecord(
        val name: String,
        val relativePath: String,
        val path: String,
        val description: String?,
        val available: Boolean,
        val failReason: String?,
        val files: List<String>,
    )

    data class SkillFileNode(val name: String, val path: String, val kind: String, val size: Long?, val children: List<SkillFileNode>)
    data class SkillFileContent(val skill: String, val path: String, val content: String)

    private fun resolveSkillDir(name: String): File {
        val root = skillsRootDir()
        val relative = name.trim().replace('\\', '/')
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.split('/').contains("..")) { "Skill 路径无效: $name" }
        val dir = canonicalFile(root.resolve(relative))
        require(dir.toPath().startsWith(root.toPath()) && dir.isDirectory && dir.resolve("SKILL.md").isFile) { "Skill 不存在或缺少 SKILL.md: $name" }
        return dir
    }

    private fun resolveSkillDirectory(root: File, relativePath: String?): File {
        val normalized = relativePath.orEmpty().replace('\\', '/').trim('/')
        if (normalized.isBlank()) return root
        require(!normalized.split('/').contains("..")) { "Skill 目录路径无效: $relativePath" }
        val directory = canonicalFile(root.resolve(normalized))
        require(directory.toPath().startsWith(root.toPath()) && directory.isDirectory) { "Skill 目录不存在: $relativePath" }
        return directory
    }

    private fun resolveNewSkillChild(root: File, parent: File, name: String): File {
        val childName = name.trim()
        require(childName.isNotBlank()) { "名称不能为空" }
        require(childName != "." && childName != ".." && !childName.startsWith('.') && !childName.contains('/') && !childName.contains('\\')) { "名称无效: $name" }
        val child = canonicalFile(parent.resolve(childName))
        require(child.toPath().startsWith(root.toPath())) { "Skill 路径越界: $name" }
        require(!child.exists()) { "文件或目录已存在: $childName" }
        return child
    }

    private fun resolveSkillFile(root: File, relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.split('/').contains("..")) { "Skill 文件路径无效: $relativePath" }
        val path = canonicalFile(root.resolve(normalized))
        require(path.toPath().startsWith(root.toPath()) && path.isFile) { "Skill 文件不存在: $relativePath" }
        return path
    }

    private fun skillDirectoryRecord(root: Path, dir: Path): SkillDirectoryRecord {
        val content = Files.readString(dir.resolve("SKILL.md"))
        val metadata = com.lhstack.tools.agent.model.tools.SkillMetadataSupport.skillMetadata(content)
        val availability = com.lhstack.tools.agent.model.tools.SkillMetadataSupport.skillAvailability(dir.toFile(), metadata)
        return SkillDirectoryRecord(
            name = root.relativize(dir).toString().replace('\\', '/'),
            relativePath = root.relativize(dir).toString().replace('\\', '/'),
            path = dir.toString(),
            description = metadata.description,
            available = availability.available,
            failReason = availability.failReason,
            files = listFiles(dir, dir),
        )
    }

    private fun listFiles(root: Path, current: Path): List<String> {
        val result = mutableListOf<String>()
        current.toFile().listFiles().orEmpty().filterNot { it.name.startsWith(".") }.forEach { child ->
            if (child.isDirectory) result.addAll(listFiles(root, child.toPath()))
            else result.add(root.relativize(child.toPath()).toString().replace('\\', '/'))
        }
        return result.sorted()
    }

    private fun buildFileTree(root: File, current: File): SkillFileNode {
        val children = current.listFiles()?.filterNot { it.name.startsWith(".") }?.sortedBy { it.name }?.map { child ->
            if (child.isDirectory) buildFileTree(root, child)
            else SkillFileNode(child.name, root.toPath().relativize(child.toPath()).toString().replace('\\', '/'), "file", child.length(), emptyList())
        }.orEmpty()
        return SkillFileNode(current.name, root.toPath().relativize(current.toPath()).toString().replace('\\', '/'), if (current.isDirectory) "dir" else "file", if (current.isFile) current.length() else null, children)
    }

    private fun defaultSkillsRootDir(): File = canonicalFile(File(Const.JTOOLS_PLUGIN_HOME, "skills"))

    private fun canonicalFile(file: File): File = file.canonicalFile
}
