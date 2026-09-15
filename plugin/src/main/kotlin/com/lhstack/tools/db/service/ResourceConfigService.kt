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

    fun createSkill(name: String, description: String): String {
        val root = skillsRootDir()
        val childName = validateNewSkillName(name)
        val desc = description.trim()
        require(desc.isNotBlank()) { "SKILL.md 必须包含非空 description" }
        val dir = canonicalFile(root.resolve(childName))
        require(dir.toPath().startsWith(root.toPath())) { "Skill 路径越界: $name" }
        require(!dir.exists()) { "技能已存在: $childName" }
        require(dir.mkdirs()) { "技能目录创建失败: $childName" }
        try {
            dir.resolve("SKILL.md").writeText(skillMarkdownTemplate(desc), Charsets.UTF_8)
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
        return childName
    }

    fun importSkills(sourcePath: String): SkillImportReport {
        val trimmed = sourcePath.trim()
        require(trimmed.isNotBlank()) { "导入路径不能为空" }
        val source = canonicalFile(File(trimmed))
        require(source.isDirectory) { "导入路径必须是目录: ${source.path}" }
        val root = canonicalFile(skillsRootDir())
        val discovered = discoverImportSkillDirs(source)
        require(discovered.isNotEmpty()) { "导入目录下没有包含 SKILL.md 的技能" }
        val nameBase = if (File(source, "SKILL.md").isFile) source.parentFile ?: source else source
        val imported = mutableListOf<SkillDirectoryRecord>()
        val failed = mutableListOf<SkillImportFailure>()
        for (skillDir in discovered) {
            val relative = try {
                nameBase.toPath().relativize(skillDir.toPath()).toString().replace('\\', '/').trim('/')
            } catch (error: Throwable) {
                failed += SkillImportFailure(skillDir.path, error.message ?: error.toString())
                continue
            }
            try {
                imported += importOneSkill(root, skillDir, relative)
            } catch (error: Throwable) {
                failed += SkillImportFailure(relative.ifBlank { skillDir.name }, error.message ?: error.toString())
            }
        }
        return SkillImportReport(imported, failed)
    }

    private fun importOneSkill(root: File, skillDir: File, relative: String): SkillDirectoryRecord {
        val destRelative = relative.trim('/').ifBlank { skillDir.name }
        require(destRelative.isNotBlank() && !destRelative.split('/').contains("..")) { "Skill 路径无效: $destRelative" }
        val content = File(skillDir, "SKILL.md").readText(Charsets.UTF_8)
        require(content.contains("---")) { "SKILL.md 缺少 frontmatter" }
        val dest = canonicalFile(root.resolve(destRelative))
        require(dest.toPath().startsWith(root.toPath())) { "Skill 路径越界: $destRelative" }
        require(!dest.exists()) { "Skill `$destRelative` 已存在" }
        dest.parentFile?.mkdirs()
        try {
            copySkillDirectory(skillDir, dest)
        } catch (error: Throwable) {
            dest.deleteRecursively()
            throw error
        }
        val canonical = canonicalFile(dest)
        require(canonical.toPath().startsWith(root.toPath())) { "Skill 路径越界: $destRelative" }
        return skillDirectoryRecord(root.toPath(), canonical.toPath())
    }

    private fun discoverImportSkillDirs(source: File): List<File> {
        val dirs = mutableListOf<File>()
        collectImportSkillDirs(source, dirs)
        return dirs
    }

    private fun collectImportSkillDirs(current: File, dirs: MutableList<File>) {
        if (File(current, "SKILL.md").isFile) {
            dirs += current
            return
        }
        current.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { child ->
            collectImportSkillDirs(child, dirs)
        }
    }

    private fun copySkillDirectory(source: File, destination: File) {
        require(destination.mkdir()) { "创建 Skill 目录失败: ${destination.path}" }
        val entries = source.listFiles() ?: return
        for (entry in entries) {
            val target = File(destination, entry.name)
            when {
                entry.isDirectory -> copySkillDirectory(entry, target)
                entry.isFile -> entry.copyTo(target, overwrite = false)
                else -> error("Skill 包含不支持的文件类型: ${entry.path}")
            }
        }
    }

    private fun validateNewSkillName(name: String): String {
        val childName = name.trim()
        require(childName.isNotBlank()) { "技能名称不能为空" }
        require(childName != "." && childName != ".." && !childName.startsWith('.') && !childName.contains('/') && !childName.contains('\\')) {
            "技能名称无效: $name"
        }
        return childName
    }

    private fun skillMarkdownTemplate(description: String): String {
        val escaped = description.replace("\\", "\\\\").replace("\"", "\\\"")
        return "---\ndescription: \"$escaped\"\n---\n"
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

    data class SkillImportFailure(val name: String, val error: String)
    data class SkillImportReport(val imported: List<SkillDirectoryRecord>, val failed: List<SkillImportFailure>)

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
