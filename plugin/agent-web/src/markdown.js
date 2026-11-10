import DOMPurify from "dompurify";
import { marked } from "marked";
import { highlightCodeHtml } from "./codeHighlight.js";

marked.setOptions({
  async: false,
  breaks: true,
  gfm: true,
});

function htmlSanitizeOptions() {
  return {
    ADD_TAGS: [
      "audio",
      "video",
      "source",
      "figure",
      "figcaption",
      "iframe",
      "button",
      "textarea",
    ],
    ADD_ATTR: [
      "controls",
      "src",
      "srcdoc",
      "type",
      "alt",
      "title",
      "sandbox",
      "scrolling",
      "loading",
      "data-resource-reference",
      "data-resource-image",
      "referrerpolicy",
      "allow",
      "class",
      "style",
      "hidden",
      "readonly",
      "data-code-action",
      "data-code-lang",
      "data-html-view",
    ],
  };
}

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function escapeHtmlAttr(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function codeLanguage(lang) {
  return (
    String(lang || "")
      .trim()
      .split(/\s+/)[0] || "text"
  );
}

function codeBlockToolbar(lang, extraActions = "") {
  const language = codeLanguage(lang);
  return `<div class="code-block-toolbar">
    <span class="code-block-lang">${escapeHtml(language)}</span>
    <span class="code-block-actions">
      <button type="button" data-code-action="copy">复制</button>
      <button type="button" data-code-action="download">下载</button>
      ${extraActions}
    </span>
  </div>`;
}

function codeBlockHtml(text, lang) {
  const language = codeLanguage(lang);
  const className = language
    ? ` class="language-${escapeHtmlAttr(language)}"`
    : "";
  const source = String(text || "");
  return `<div class="rendered-code-block" data-code-lang="${escapeHtmlAttr(language)}">
    ${codeBlockToolbar(language)}
    <pre><code${className}>${highlightCodeHtml(source, language)}</code></pre>
    <textarea class="code-source" hidden readonly>${escapeHtml(source)}</textarea>
  </div>`;
}

function htmlPreviewSource(source) {
  const previewStyle = `<style>
html, body { width: 100% !important; height: 100% !important; margin: 0 !important; overflow: auto; }
* { box-sizing: border-box; }
</style>`;
  if (/<\/head>/i.test(source))
    return source.replace(/<\/head>/i, `${previewStyle}</head>`);
  if (/<html\b/i.test(source))
    return source.replace(
      /<html\b([^>]*)>/i,
      `<html$1><head>${previewStyle}</head>`,
    );
  return `${previewStyle}${source}`;
}

function htmlDocumentFrame(html) {
  const source = html.trim();
  return `<div class="rendered-code-block embedded-html-block" data-code-lang="html" data-html-view="preview">
    ${codeBlockToolbar("html", '<button type="button" data-code-action="toggle-html">源码</button>')}
    <iframe class="embedded-html-page" title="HTML preview" sandbox="allow-scripts allow-forms allow-popups allow-downloads" loading="lazy" referrerpolicy="no-referrer" srcdoc="${escapeHtmlAttr(htmlPreviewSource(source))}"></iframe>
    <pre class="embedded-html-source"><code>${highlightCodeHtml(source, "html")}</code></pre>
    <textarea class="code-source" hidden readonly>${escapeHtml(source)}</textarea>
  </div>`;
}

export function isLocalResource(reference) {
  return (
    Boolean(reference) &&
    !reference.startsWith("#") &&
    !/^(?:https?:|mailto:|data:|blob:|\/\/)/i.test(reference) &&
    (!/^[a-z][a-z0-9+.-]*:/i.test(reference) ||
      /^file:/i.test(reference) ||
      /^[a-z]:[\\/]/i.test(reference))
  );
}

function createMarkdownRenderer() {
  const renderer = new marked.Renderer();
  renderer.link = function (token) {
    const body = this.parser.parseInline(token.tokens);
    const ref = token.href || "";
    if (isLocalResource(ref))
      return `<a href="#" data-resource-reference="${escapeHtmlAttr(ref)}">${body}</a>`;
    return `<a href="${escapeHtmlAttr(ref)}">${body}</a>`;
  };
  renderer.image = function (token) {
    const ref = token.href || "";
    if (isLocalResource(ref))
      return `<span class="local-image" data-resource-image="${escapeHtmlAttr(ref)}" role="img" aria-label="${escapeHtmlAttr(token.text)}"><span>正在加载图片：${escapeHtml(token.text || ref)}</span></span>`;
    return `<img src="${escapeHtmlAttr(ref)}" alt="${escapeHtmlAttr(token.text)}" loading="lazy" />`;
  };
  renderer.code = ({ text, lang }) => {
    if (codeLanguage(lang) === "html") return htmlDocumentFrame(text);
    return codeBlockHtml(text, lang);
  };
  return renderer;
}

export const markdown = {
  render(text) {
    const htmlBlocks = [];
    let markdownText = String(text || "").replace(
      /(^|\n)[ \t]*(?:```|~~~)[ \t]*html[^\n]*\n([\s\S]*?)\n[ \t]*(?:```|~~~)[ \t]*(?=\n|$)/gi,
      (match, prefix, html) => {
        const index = htmlBlocks.length;
        htmlBlocks.push(htmlDocumentFrame(html));
        return `${prefix}\nHTML_BLOCK_${index}\n`;
      },
    );
    markdownText = markdownText.replace(
      /(^|\n)[ \t]*((?:<!doctype\s+html[^>]*>\s*)?<html\b[\s\S]*?<\/html>)[ \t]*(?=\n|$)/gi,
      (match, prefix, html) => {
        const index = htmlBlocks.length;
        htmlBlocks.push(htmlDocumentFrame(html));
        return `${prefix}\nHTML_BLOCK_${index}\n`;
      },
    );
    let rendered = DOMPurify.sanitize(
      marked.parse(markdownText, { renderer: createMarkdownRenderer() }),
      htmlSanitizeOptions(),
    );
    htmlBlocks.forEach((html, index) => {
      const token = `HTML_BLOCK_${index}`;
      rendered = rendered
        .replace(new RegExp(`<p>\\s*${token}\\s*</p>`, "g"), html)
        .replace(new RegExp(token, "g"), html);
    });
    return rendered;
  },
};
