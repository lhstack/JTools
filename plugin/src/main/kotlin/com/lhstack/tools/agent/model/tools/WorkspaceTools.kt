package com.lhstack.tools.agent.model.tools

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 工作区路径安全工具。完全照抄 awake-claw tools.rs 的 WorkspaceTools + paths.rs helper。
 *
 * 作用：把工具传入的相对/绝对路径规范化，并强制约束在工作区根目录内，防止越权访问。
 * root 传相对路径时以当前进程 CWD 为基准解析（对齐 awake WorkspaceTools::new）。
 *
 * canonicalize 用 File.canonicalFile（解析符号链接、去 . / ..），对齐 Rust 的 canonicalize_path。
 */
class WorkspaceTools(root: String) {

    /** 规范化后的工作区根目录。 */
    val root: File = run {
        val raw = File(root)
        if (raw.isAbsolute) raw else File(System.getProperty("user.dir")).resolve(raw)
    }

    /** 照抄 canonical_root：canonicalize 工作区根。 */
    fun canonicalRoot(): File = canonicalize(root)

    /**
     * 照抄 resolve_existing_path：join + canonicalize + 约束在工作区内。
     * 用于工具读取工作区内的已存在文件。
     */
    fun resolveExistingPath(path: String): File {
        val root = canonicalRoot()
        val joined = joinWorkspacePath(root, path)
        val canonical = canonicalize(joined)
        ensureInsideWorkspace(canonical, root)
        return canonical
    }

    /**
     * 照抄 display_path：把绝对路径转成工作区相对展示路径。
     * 不在工作区内时回退到相对路径推导。
     */
    fun displayPath(path: File): String {
        val root = canonicalRoot()
        val canonical = if (path.exists()) canonicalize(path) else path
        val relative = relativizeOrFallback(root, canonical)
        return relative.replace('\\', '/')
    }

    private fun relativizeOrFallback(root: File, target: File): String {
        val rootPath = root.toPath()
        val targetPath = target.toPath()
        return if (targetPath.startsWith(rootPath)) {
            rootPath.relativize(targetPath).toString()
        } else {
            relativePathBetween(rootPath, targetPath).toString()
        }
    }

    companion object {
        /** 照抄 canonicalize_path：解析真实路径；不存在时退回规范化的绝对路径。 */
        fun canonicalize(path: File): File =
            try {
                path.canonicalFile
            } catch (_: Throwable) {
                path.absoluteFile.normalize()
            }

        /** 照抄 join_workspace_path：拒绝 .. / 绝对路径穿越，只允许普通段。 */
        fun joinWorkspacePath(root: File, path: String): File {
            if (path.isBlank()) {
                throw ToolException.invalidPath(path)
            }
            val normalized = normalizeRelativeSegments(path) ?: throw ToolException.invalidPath(path)
            return root.resolve(normalized)
        }

        /** 照抄 ensure_inside_workspace。 */
        fun ensureInsideWorkspace(path: File, root: File) {
            if (!path.toPath().startsWith(root.toPath())) {
                throw ToolException.outsideWorkspace(path.path, root.path)
            }
        }

        /**
         * 把相对路径按段规范化：只保留 Normal 段，拒绝 ..、根、盘符前缀。
         * 返回 null 表示非法。对齐 awake join_workspace_path 的 component 遍历。
         */
        fun normalizeRelativeSegments(path: String): String? {
            val input = Paths.get(path)
            if (input.isAbsolute) return null
            val parts = mutableListOf<String>()
            for (segment in input) {
                val name = segment.toString()
                when (name) {
                    "", "." -> {}
                    ".." -> return null
                    else -> parts.add(name)
                }
            }
            if (parts.isEmpty()) return null
            return parts.joinToString(File.separator)
        }

        /** 照抄 relative_path_between：求两个绝对路径的相对路径。 */
        private fun relativePathBetween(from: Path, to: Path): Path {
            val fromParts = normalComponents(from)
            val toParts = normalComponents(to)
            var common = 0
            while (common < fromParts.size && common < toParts.size && fromParts[common] == toParts[common]) {
                common++
            }
            val builder = StringBuilder()
            for (i in common until fromParts.size) {
                if (builder.isNotEmpty()) builder.append(File.separator)
                builder.append("..")
            }
            for (i in common until toParts.size) {
                if (builder.isNotEmpty()) builder.append(File.separator)
                builder.append(toParts[i])
            }
            if (builder.isEmpty()) builder.append(".")
            return Paths.get(builder.toString())
        }

        private fun normalComponents(path: Path): List<String> =
            path.mapNotNull { it.toString().takeIf { name -> name.isNotEmpty() } }
    }
}
