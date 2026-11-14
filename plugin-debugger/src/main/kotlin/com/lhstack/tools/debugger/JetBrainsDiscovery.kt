package com.lhstack.tools.debugger

import com.google.gson.JsonParser
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Bounded discovery of installed JetBrains products; never scans project trees or disks recursively. */
internal class JetBrainsDiscovery(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val currentIde: Path? = null,
    private val currentPlugins: Path? = null,
    private val currentJtools: Path? = null,
    private val searchRoots: List<Path>? = null,
    private val inspectProcesses: Boolean = true,
) {
    data class Candidate(val product: String, val version: String, val code: String, val ide: Path,
                         val launcher: Path, val vmOptions: Path, val distributions: List<Path>, val current: Boolean) {
        fun json() = mapOf("product" to product, "version" to version, "product_code" to code,
            "ide_home" to ide.toString(), "launcher" to launcher.toString(), "vm_options" to vmOptions.toString(),
            "jtools_distributions" to distributions.map(Path::toString), "current" to current)
    }

    fun discover(): List<Candidate> {
        val installations = linkedSetOf<Path>()
        currentIde?.let { installations.add(it) }
        if (inspectProcesses) ProcessHandle.allProcesses().use { processes ->
            processes.forEach { process -> process.info().command().ifPresent { command ->
                var path: Path? = Path.of(command).parent
                repeat(5) {
                    path?.let { if (productInfo(it) != null) installations.add(it); path = it.parent }
                }
            } }
        }
        val roots = searchRoots ?: (listOf(Path.of("/Applications"), home.resolve("Applications"), Path.of("/opt"),
            home.resolve(".local/share/JetBrains/Toolbox/apps"), home.resolve("Library/Application Support/JetBrains/Toolbox/apps")) +
            listOfNotNull(System.getenv("LOCALAPPDATA")?.let { Path.of(it).resolve("JetBrains/Toolbox/apps") },
                System.getenv("ProgramFiles")?.let { Path.of(it).resolve("JetBrains") }))
        roots.filter(Files::isDirectory).forEach { root ->
            Files.walkFileTree(root, emptySet(), 8, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    val installed = when {
                        productInfo(directory) != null -> directory
                        productInfo(directory.resolve("Contents")) != null -> directory.resolve("Contents")
                        else -> null
                    }
                    if (installed != null) {
                        installations.add(installed)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val name = directory.fileName.toString()
                    return if (name.endsWith(".app") || name in setOf("lib", "plugins", "node_modules", ".git", "jbr", "cache", "logs"))
                        FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
            })
        }
        return installations.mapNotNull(::candidate).distinctBy { it.ide }.sortedWith(compareByDescending<Candidate> { it.current }.thenBy { it.product }.thenBy { it.version })
    }

    private fun productInfo(ide: Path): Path? = listOf(ide.resolve("product-info.json"), ide.resolve("Resources/product-info.json"))
        .firstOrNull(Files::isRegularFile)

    private fun candidate(path: Path): Candidate? {
        val info = productInfo(path) ?: return null
        val data = JsonParser.parseString(Files.readString(info)).asJsonObject
        val os = System.getProperty("os.name").lowercase().let { if (it.contains("mac")) "macOS" else if (it.contains("win")) "Windows" else "Linux" }
        fun normalizedArch(value: String) = when (value.lowercase()) { "arm64", "aarch64" -> "aarch64"; "x86_64", "amd64" -> "amd64"; else -> value }
        val arch = normalizedArch(System.getProperty("os.arch"))
        val launches = data.getAsJsonArray("launch").map { it.asJsonObject }.filter { it.get("os")?.asString == os }
        val launch = launches.firstOrNull { it.get("arch")?.asString?.let(::normalizedArch) == arch } ?: launches.singleOrNull() ?: return null
        val launcher = info.parent.resolve(launch.get("launcherPath").asString).normalize()
        val vm = info.parent.resolve(launch.get("vmOptionsFilePath").asString).normalize()
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher) || !Files.isRegularFile(vm)) return null
        val ide = path.toRealPath()
        val current = currentIde?.toRealPath() == ide
        val selector = data.get("dataDirectoryName")?.asString
        val pluginRoots = mutableListOf<Path>()
        if (current) currentPlugins?.let(pluginRoots::add)
        if (selector != null) {
            pluginRoots.add(home.resolve("Library/Application Support/JetBrains/$selector/plugins"))
            pluginRoots.add(home.resolve(".local/share/JetBrains/$selector"))
            pluginRoots.add(home.resolve(".$selector/config/plugins"))
            System.getenv("APPDATA")?.let { pluginRoots.add(Path.of(it).resolve("JetBrains/$selector/plugins")) }
        }
        pluginRoots.add(ide.resolve("plugins"))
        val distributions = linkedSetOf<Path>()
        val loaded = if (current) currentJtools?.takeIf(::isJtools)?.toRealPath() else null
        if (loaded != null) distributions.add(loaded)
        // The loaded descriptor is authoritative; disk copies are not alternative active installations.
        if (loaded == null) pluginRoots.distinct().filter(Files::isDirectory).forEach { root ->
            Files.list(root).use { children -> children.filter(Files::isDirectory).filter(::isJtools).forEach { distributions.add(it.toRealPath()) } }
        }
        return Candidate(data.get("name").asString, data.get("version").asString, data.get("productCode").asString,
            ide, launcher.toRealPath(), vm.toRealPath(), distributions.toList(), current)
    }

    private fun isJtools(root: Path): Boolean {
        val lib = root.resolve("lib")
        if (!Files.isDirectory(lib)) return false
        return Files.list(lib).use { jars -> jars.filter { it.fileName.toString().endsWith(".jar") }.anyMatch { jar ->
            ZipFile(jar.toFile()).use { zip ->
                val descriptor = zip.getEntry("META-INF/plugin.xml") ?: return@use false
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                zip.getInputStream(descriptor).use { factory.newDocumentBuilder().parse(it).getElementsByTagName("id").item(0)?.textContent == "com.lhstack.tools" }
            }
        } }
    }

    companion object {
        const val DOWNLOAD = "https://plugins.jetbrains.com/plugin/com.lhstack.tools"
        const val RELEASES = "https://github.com/lhstack/JTools/releases"
    }
}
