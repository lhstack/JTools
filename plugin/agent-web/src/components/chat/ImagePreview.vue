<script setup>
import { ref } from "vue";
import { ElMessage } from "element-plus";
import {
  ZoomOut,
  ZoomIn,
  RefreshLeft,
  RefreshRight,
  FullScreen,
  CopyDocument,
  Download,
} from "@element-plus/icons-vue";
import { api } from "../../bridge/jcefBridge";

const props = defineProps({ image: { type: Object, required: true } });
defineEmits(["close"]);
const busy = ref(false);
const originalSize = ref(false);
function toggleSize(reset) {
  originalSize.value = !originalSize.value;
  reset();
}
async function transfer(action) {
  if (busy.value) return;
  busy.value = true;
  try {
    const result = await api(`image.${action}`, props.image.source);
    if (action === "copy") ElMessage.success("图片已复制");
    else if (result.saved) ElMessage.success(`已保存到 ${result.path}`);
  } catch (error) {
    ElMessage.error(
      `${action === "copy" ? "复制" : "下载"}失败：${error.message || String(error)}`,
    );
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <el-image-viewer :url-list="[image.url]" teleported @close="$emit('close')">
    <template #toolbar="{ actions, reset }">
      <div class="image-preview-toolbar" :class="{ 'original-size': originalSize }" @click.stop @mousedown.stop>
        <button
          type="button"
          title="缩小"
          aria-label="缩小"
          @click="actions('zoomOut')"
        >
          <el-icon><ZoomOut /></el-icon>
        </button>
        <button
          type="button"
          title="放大"
          aria-label="放大"
          @click="actions('zoomIn')"
        >
          <el-icon><ZoomIn /></el-icon>
        </button>
        <button
          type="button"
          title="原始尺寸／适应窗口"
          aria-label="原始尺寸／适应窗口"
          @click="toggleSize(reset)"
        >
          <el-icon><FullScreen /></el-icon>
        </button>
        <span class="divider" />
        <button
          type="button"
          title="向左旋转"
          aria-label="向左旋转"
          @click="actions('anticlockwise')"
        >
          <el-icon><RefreshLeft /></el-icon>
        </button>
        <button
          type="button"
          title="向右旋转"
          aria-label="向右旋转"
          @click="actions('clockwise')"
        >
          <el-icon><RefreshRight /></el-icon>
        </button>
        <span class="divider" />
        <button
          type="button"
          title="复制图片"
          aria-label="复制图片"
          :disabled="busy"
          @click="transfer('copy')"
        >
          <el-icon><CopyDocument /></el-icon>
        </button>
        <button
          type="button"
          title="下载图片"
          aria-label="下载图片"
          :disabled="busy"
          @click="transfer('save')"
        >
          <el-icon><Download /></el-icon>
        </button>
      </div>
    </template>
  </el-image-viewer>
</template>

<style scoped>
.image-preview-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.image-preview-toolbar button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.image-preview-toolbar button:hover {
  background: rgba(255, 255, 255, 0.15);
}
.image-preview-toolbar button:disabled {
  opacity: 0.5;
  cursor: wait;
}
.image-preview-toolbar .el-icon {
  font-size: 21px;
}
.divider {
  height: 20px;
  width: 1px;
  background: rgba(255, 255, 255, 0.25);
}
</style>

<style>
.el-image-viewer__wrapper:has(.image-preview-toolbar) .el-image-viewer__canvas { position:absolute; inset:64px 56px 104px; width:auto; height:auto; overflow:hidden; }
.el-image-viewer__wrapper:has(.image-preview-toolbar:not(.original-size)) .el-image-viewer__img { width:100%; height:100%; object-fit:contain; }
.el-image-viewer__wrapper:has(.image-preview-toolbar.original-size) .el-image-viewer__img { max-width:none; max-height:none; }
@media (max-width:600px) { .el-image-viewer__wrapper:has(.image-preview-toolbar) .el-image-viewer__canvas { inset:64px 16px 104px; } }
</style>
