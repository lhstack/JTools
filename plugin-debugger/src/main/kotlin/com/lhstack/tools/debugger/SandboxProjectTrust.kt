package com.lhstack.tools.debugger

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** 只向独立沙箱配置登记本次自建项目；不信任父目录，不关闭 IDE 信任检查。 */
internal object SandboxProjectTrust {
    fun prepare(config: Path, project: Path) {
        val options = config.resolve("options")
        Files.createDirectories(options)
        require(Files.isDirectory(options, NOFOLLOW_LINKS)) { "沙箱 options 目录不能是符号链接" }
        val target = options.resolve("trusted-paths.xml")
        require(!Files.isSymbolicLink(target)) { "沙箱信任配置不能是符号链接" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder()
        val document = if (Files.exists(target)) {
            Files.newInputStream(target).use { builder.parse(it) }
        } else {
            builder.newDocument().apply { appendChild(createElement("application")) }
        }
        val application = document.documentElement
        require(application.tagName == "application") { "沙箱信任配置根节点无效" }
        // 每次只保留当前自建项目，避免复用配置时不断积累失效路径。
        val children = (0 until application.childNodes.length).map { application.childNodes.item(it) }
        children.filterIsInstance<Element>().filter {
            it.tagName == "component" && it.getAttribute("name") == "Trusted.Paths.Settings"
        }.forEach(application::removeChild)
        fun Element.child(name: String, vararg attributes: Pair<String, String>): Element =
            document.createElement(name).also { element ->
                attributes.forEach { (key, value) -> element.setAttribute(key, value) }
                appendChild(element)
            }
        application.child("component", "name" to "Trusted.Paths.Settings")
            .child("option", "name" to "TRUSTED_PATHS")
            .child("list")
            .child("option", "value" to project.toRealPath().toString())
        val transformer = TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer()
        Files.newOutputStream(target).use { transformer.transform(DOMSource(document), StreamResult(it)) }
    }
}
