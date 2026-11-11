<script setup>
import { computed, ref, watch, nextTick, onBeforeUnmount } from "vue";
import { ElMessage } from "element-plus";
import { api, invoke } from "../../bridge/jcefBridge";
import ImagePreview from "./ImagePreview.vue";
import { markdown, isLocalResource } from "../../markdown.js";

const props = defineProps({
  content: { type: String, default: "" },
  sessionId: { type: [String, Number], default: null },
});
const root = ref(null);
const previewImage = ref(null);
let generation = 0;
let observer;
const previews = new Map();
let lastSession = props.sessionId;
const html = computed(() => markdown.render(props.content));

function codeFileExtension(lang) {
  const language = String(lang || "").toLowerCase();
  const extensions = {
    javascript: "js",
    js: "js",
    typescript: "ts",
    ts: "ts",
    json: "json",
    rust: "rs",
    rs: "rs",
    python: "py",
    py: "py",
    shell: "sh",
    bash: "sh",
    sh: "sh",
    markdown: "md",
    md: "md",
    vue: "vue",
    sql: "sql",
    yaml: "yaml",
    yml: "yml",
    html: "html",
    css: "css",
    java: "java",
    kt: "kt",
    kotlin: "kt",
  };
  return extensions[language] || "txt";
}

function codeBlockSource(block) {
  const source = block.querySelector(".code-source");
  if (source) return source.value || source.textContent || "";
  return block.querySelector("code")?.textContent || "";
}

async function loadImage(node, version) {
  const reference = node.dataset.resourceImage;
  try {
    if (!props.sessionId) throw Error("图片缺少会话上下文");
    const key = `${props.sessionId}:${reference}`;
    let cached = previews.get(key);
    if (cached && Date.now() - cached.createdAt > 5000) {
      previews.delete(key);
      cached = null;
    }
    if (!cached) {
      cached = {
        createdAt: Date.now(),
        request: api("resource.preview", {
          sessionId: String(props.sessionId),
          reference,
        }),
      };
      if (previews.size >= 24) previews.delete(previews.keys().next().value);
      previews.set(key, cached);
    }
    const result = await cached.request;
    if (version !== generation || !node.isConnected) return;
    const image = document.createElement("img");
    image.src = result.url;
    image.alt = node.getAttribute("aria-label") || reference;
    image.addEventListener(
      "error",
      () => {
        node.textContent = "图片解码失败，点击重试";
        node.dataset.failed = "true";
        previews.delete(key);
      },
      { once: true },
    );
    node.replaceChildren(image);
  } catch (error) {
    previews.delete(`${props.sessionId}:${reference}`);
    if (version === generation && node.isConnected) {
      node.textContent = `图片加载失败：${error.message}，点击重试`;
      node.dataset.failed = "true";
    }
  }
}
function mediaFailure(media) {
  const code = media.error?.code;
  if (code === 4 || code === 3) return "当前 IDE 无法解码此媒体格式。JCEF 通常不支持 H.264／H.265／AAC，可使用 WebM（VP8／VP9 + Opus）或通过下方链接打开原文件。";
  if (code === 2) return "媒体网络请求失败，请检查地址、网络连接或访问权限。";
  return "媒体加载失败，请检查文件地址和编码格式。";
}
async function prepareMedia(media, version) {
  const source = media.matches("[src],[data-resource-media]") ? media : media.querySelector("source[src],source[data-resource-media]");
  const reference = source?.dataset.resourceMedia || source?.getAttribute("src");
  if (!reference) return;
  const frame = document.createElement("div");
  frame.className = "markdown-media";
  media.before(frame);
  frame.append(media);
  media.controls = true;
  media.preload = "metadata";
  const status = document.createElement("div");
  status.className = "media-status";
  status.textContent = "正在加载媒体…";
  frame.append(status);
  const link = document.createElement("a");
  link.textContent = "打开原文件";
  if (isLocalResource(reference)) {
    link.href = "#";
    link.dataset.resourceReference = reference;
  } else link.href = reference;
  frame.append(link);
  media.addEventListener("error", () => { status.textContent = mediaFailure(media); });
  const candidates = [...media.querySelectorAll("source")];
  const failed = new Set();
  candidates.forEach(candidate => candidate.addEventListener("error", () => {
    failed.add(candidate);
    if (failed.size === candidates.length) status.textContent = "当前 IDE 无法解码此媒体格式，或媒体地址不可访问。请检查地址与编码格式；可使用 WebM（VP8／VP9 + Opus），或通过下方链接打开原文件。";
  }));
  media.addEventListener("loadedmetadata", () => { status.textContent = ""; });
  try {
    const sources = media.matches("[src],[data-resource-media]") ? [media] : candidates;
    for (const item of sources) {
      const local = item.dataset.resourceMedia || item.getAttribute("src");
      if (!isLocalResource(local)) continue;
      if (!props.sessionId) throw Error("媒体缺少会话上下文");
      item.removeAttribute("src");
      const result = await api("resource.media", { sessionId: String(props.sessionId), reference: local });
      if (version !== generation || !media.isConnected) return;
      item.src = result.url;
    }
    media.load();
  } catch (error) {
    if (version === generation && media.isConnected) status.textContent = `媒体加载失败：${error.message}`;
  }
}

watch(
  [html, () => props.sessionId],
  async () => {
    if (lastSession !== props.sessionId) {
      previews.clear();
      lastSession = props.sessionId;
    }
    const version = ++generation;
    observer?.disconnect();
    await nextTick();
    if (version !== generation || !root.value) return;
    root.value.querySelectorAll("video,audio").forEach((media) => prepareMedia(media, version));
    observer = new IntersectionObserver(
      (entries) =>
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            observer.unobserve(entry.target);
            loadImage(entry.target, version);
          }
        }),
      { rootMargin: "200px" },
    );
    root.value
      .querySelectorAll("[data-resource-image]")
      .forEach((node) => observer.observe(node));
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  generation++;
  observer?.disconnect();
  previews.clear();
});

async function click(event) {
  const image = event.target.closest("[data-resource-image]");
  if (image?.dataset.failed) {
    delete image.dataset.failed;
    await loadImage(image, generation);
    return;
  }
  const target = event.target.closest("img");
  if (target && root.value?.contains(target)) {
    event.preventDefault();
    event.stopPropagation();
    const reference = target.closest("[data-resource-image]")?.dataset
      .resourceImage;
    previewImage.value = {
      url: target.src,
      source: reference
        ? { sessionId: String(props.sessionId), reference }
        : { url: target.src, filename: target.alt || "image" },
    };
    return;
  }
  const anchor = event.target.closest("a");
  if (anchor) {
    const reference = anchor.dataset.resourceReference;
    const href = anchor.getAttribute("href") || "";
    if (!reference && href.startsWith("#")) return;
    event.preventDefault();
    try {
      if (reference) {
        if (!props.sessionId) throw Error("文件缺少会话上下文");
        await api("resource.open", {
          sessionId: String(props.sessionId),
          reference,
        });
      } else if (/^(https?:|mailto:)/i.test(href))
        await api("link.open", { text: href });
      else throw Error("不支持的链接协议");
    } catch (error) {
      ElMessage.error(error.message || String(error));
    }
    return;
  }
  const action = event.target?.closest?.("[data-code-action]");
  if (!action) return;
  const block = action.closest(".rendered-code-block");
  if (!block) return;
  event.preventDefault();
  const source = codeBlockSource(block);
  if (!source && action.dataset.codeAction !== "toggle-html") return;
  if (action.dataset.codeAction === "copy") {
    try {
      await invoke("code.copy", { text: source });
      ElMessage.success("复制成功");
    } catch (error) {
      ElMessage.error(`复制失败：${error?.message || String(error)}`);
    }
  } else if (action.dataset.codeAction === "download") {
    try {
      const data = await api("code.save", {
        text: source,
        filename: `code.${codeFileExtension(block.dataset.codeLang)}`,
      });
      if (data?.saved) ElMessage.success(`已保存到 ${data.path}`);
    } catch (error) {
      ElMessage.error(`保存失败：${error?.message || String(error)}`);
    }
  } else if (action.dataset.codeAction === "toggle-html") {
    const previewing = block.dataset.htmlView !== "source";
    block.dataset.htmlView = previewing ? "source" : "preview";
    action.textContent = previewing ? "预览" : "源码";
  }
}
</script>

<template>
  <div ref="root" class="markdown-body" v-html="html" @click="click" />
  <ImagePreview
    v-if="previewImage"
    :image="previewImage"
    @close="previewImage = null"
  />
</template>
