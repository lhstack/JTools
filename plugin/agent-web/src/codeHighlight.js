import { html } from '@codemirror/lang-html'
import { css } from '@codemirror/lang-css'
import { javascript } from '@codemirror/lang-javascript'
import { json } from '@codemirror/lang-json'
import { java } from '@codemirror/lang-java'
import { rust } from '@codemirror/lang-rust'
import { python } from '@codemirror/lang-python'
import { cpp } from '@codemirror/lang-cpp'
import { go } from '@codemirror/lang-go'
import { xml } from '@codemirror/lang-xml'
import { markdown } from '@codemirror/lang-markdown'
import { sql } from '@codemirror/lang-sql'
import { StreamLanguage } from '@codemirror/language'
import { kotlin } from '@codemirror/legacy-modes/mode/clike'
import { cmake } from '@codemirror/legacy-modes/mode/cmake'
import { diff } from '@codemirror/legacy-modes/mode/diff'
import { dockerFile } from '@codemirror/legacy-modes/mode/dockerfile'
import { http } from '@codemirror/legacy-modes/mode/http'
import { lua } from '@codemirror/legacy-modes/mode/lua'
import { nginx } from '@codemirror/legacy-modes/mode/nginx'
import { powerShell } from '@codemirror/legacy-modes/mode/powershell'
import { properties } from '@codemirror/legacy-modes/mode/properties'
import { protobuf } from '@codemirror/legacy-modes/mode/protobuf'
import { ruby } from '@codemirror/legacy-modes/mode/ruby'
import { shell } from '@codemirror/legacy-modes/mode/shell'
import { toml } from '@codemirror/legacy-modes/mode/toml'
import { yaml } from '@codemirror/legacy-modes/mode/yaml'
import { classHighlighter, highlightCode } from '@lezer/highlight'

const parserCache = new Map()

function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function streamParser(mode) {
  return StreamLanguage.define(mode).parser
}

function createParser(language) {
  if (['js', 'javascript', 'jsx', 'mjs', 'cjs'].includes(language)) {
    return javascript({ jsx: true }).language.parser
  }
  if (['ts', 'typescript', 'mts', 'cts'].includes(language)) {
    return javascript({ typescript: true }).language.parser
  }
  if (language === 'tsx') return javascript({ jsx: true, typescript: true }).language.parser
  if (['rs', 'rust'].includes(language)) return rust().language.parser
  if (language === 'java') return java().language.parser
  if (['go', 'golang'].includes(language)) return go().language.parser
  if (['py', 'python'].includes(language)) return python().language.parser
  if (['c', 'h', 'cc', 'cpp', 'cxx', 'hxx', 'hpp', 'c++'].includes(language)) return cpp().language.parser
  if (['kt', 'kts', 'kotlin'].includes(language)) return streamParser(kotlin)
  if (['json', 'json5', 'jsonc'].includes(language)) return json().language.parser
  if (['xml', 'xsd', 'xsl', 'xslt', 'svg', 'plist'].includes(language)) return xml().language.parser
  if (['html', 'htm', 'vue'].includes(language)) return html().language.parser
  if (['css', 'scss', 'less'].includes(language)) return css().language.parser
  if (['md', 'markdown'].includes(language)) return markdown().language.parser
  if (language === 'sql') return sql().language.parser
  if (['sh', 'bash', 'shell', 'zsh'].includes(language)) return streamParser(shell)
  if (['ps1', 'powershell', 'pwsh'].includes(language)) return streamParser(powerShell)
  if (['yml', 'yaml'].includes(language)) return streamParser(yaml)
  if (language === 'toml') return streamParser(toml)
  if (['ini', 'conf', 'properties'].includes(language)) return streamParser(properties)
  if (['diff', 'patch'].includes(language)) return streamParser(diff)
  if (['docker', 'dockerfile'].includes(language)) return streamParser(dockerFile)
  if (language === 'nginx') return streamParser(nginx)
  if (['rb', 'ruby'].includes(language)) return streamParser(ruby)
  if (language === 'lua') return streamParser(lua)
  if (['proto', 'protobuf'].includes(language)) return streamParser(protobuf)
  if (language === 'cmake') return streamParser(cmake)
  if (language === 'http') return streamParser(http)
  return null
}

function languageParser(lang) {
  const language = String(lang || '').trim().split(/\s+/)[0].toLowerCase()
  if (!language || language === 'text' || language === 'plaintext' || language === 'plain') {
    return null
  }
  if (parserCache.has(language)) return parserCache.get(language)
  const parser = createParser(language)
  parserCache.set(language, parser)
  return parser
}

export function highlightCodeHtml(text, lang) {
  const source = String(text ?? '')
  const parser = languageParser(lang)
  if (!parser) return escapeHtml(source)
  try {
    const tree = parser.parse(source)
    let html = ''
    highlightCode(
      source,
      tree,
      classHighlighter,
      (code, classes) => {
        const escaped = escapeHtml(code)
        html += classes ? `<span class="${classes}">${escaped}</span>` : escaped
      },
      () => {
        html += '\n'
      }
    )
    return html || escapeHtml(source)
  } catch {
    return escapeHtml(source)
  }
}
