<script setup>
const value = defineModel({ type: Number, default: null })

</script>

<template>
  <div class="resource-tool-timeout">
    <span>工具超时（秒）</span>
    <el-input-number v-model="value" :min="1" :max="2147483647" :precision="0"
      controls-position="right" placeholder="继承 Agent" aria-label="工具超时（秒）" />
    <small>留空沿用处理 Agent 的最大运行时间，未设置时为 120 秒。每次最多 3 个资源。</small>
  </div>
</template>

<style scoped>
.resource-tool-timeout { display:grid; grid-column:1 / -1; gap:5px; min-width:0; margin-top:6px; font-size:11px; color:var(--jb-muted); }
.resource-tool-timeout .el-input-number { width:100%; max-width:180px; }
.resource-tool-timeout small { font-size:10px; line-height:1.4; }
</style>
