<script setup>
import ChatView from './views/ChatView.vue'
import SessionsView from './views/SessionsView.vue'
import LogsView from './views/LogsView.vue'
import CatalogView from './views/CatalogView.vue'
import PromptsView from './views/PromptsView.vue'
import AgentsView from './views/AgentsView.vue'
import McpView from './views/McpView.vue'
import ProjectSubagentsView from './views/ProjectSubagentsView.vue'
import SkillsView from './views/SkillsView.vue'
import SettingsView from './views/SettingsView.vue'
import EnvironmentsView from './views/EnvironmentsView.vue'
const page = window.__JTOOLS_PAGE__ || 'chat'
const pages = {
  chat: ChatView,
  sessions: SessionsView,
  logs: LogsView,
  catalog: CatalogView,
  prompts: PromptsView,
  agents: AgentsView,
  skills: SkillsView,
  mcp: McpView,
  subagents: ProjectSubagentsView,
  environments: EnvironmentsView,
  settings: SettingsView,
}
</script>
<template>
  <component :is="pages[page] || ChatView" />
</template>
