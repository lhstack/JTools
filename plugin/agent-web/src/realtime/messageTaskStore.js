import { reactive } from 'vue'

const statusRank = { pending: 0, processing: 1, completed: 2, failed: 2, cancelled: 2 }
export const messageTaskState = reactive({ sessions: {} })

function sessionTasks(sessionId) {
  const key = String(sessionId)
  if (!messageTaskState.sessions[key]) messageTaskState.sessions[key] = { tasks: [] }
  return messageTaskState.sessions[key]
}

function normalize(raw) {
  const envelope = raw?.data && typeof raw.data === 'object' ? raw.data : null
  const candidate = envelope?.type === 'message_task' ? { ...raw, ...envelope } : raw
  if (!candidate) return null
  const sessionId = Number(candidate.session_id)
  const id = Number(candidate.id)
  if (!Number.isFinite(sessionId) || !Number.isFinite(id)) return null
  return { ...candidate, id, session_id: sessionId, type: 'message_task' }
}

function mergeTask(current, incoming) {
  if (!current) return { ...incoming }
  const currentRank = statusRank[current.status] ?? -1
  const incomingRank = statusRank[incoming.status] ?? -1
  const status = incomingRank < currentRank ? current.status : incoming.status
  return {
    ...current,
    ...incoming,
    status,
    turn_id: incoming.turn_id || current.turn_id,
    content: incoming.content ?? current.content,
    attachments: incoming.attachments ?? current.attachments,
    attachment_items: incoming.attachment_items ?? current.attachment_items,
  }
}

export function consumeMessageTask(raw) {
  const task = normalize(raw)
  if (!task) return null
  if (task.deleted) {
    removeMessageTask(task.session_id, task.id)
    return task
  }
  const target = sessionTasks(task.session_id)
  const index = target.tasks.findIndex((item) => Number(item.id) === Number(task.id))
  const merged = mergeTask(index >= 0 ? target.tasks[index] : null, task)
  if (['completed', 'failed', 'cancelled'].includes(merged.status)) {
    if (index >= 0) target.tasks.splice(index, 1)
    return merged
  }
  if (index >= 0) target.tasks.splice(index, 1, merged)
  else target.tasks.push(merged)
  if (merged.status === 'processing') {
    target.tasks = target.tasks.filter((item) => Number(item.id) === Number(merged.id) || item.status !== 'processing')
  }
  target.tasks.sort((left, right) => Number(left.id) - Number(right.id))
  return merged
}

export function replaceMessageTasks(sessionId, tasks) {
  const target = sessionTasks(sessionId)
  target.tasks = (tasks || []).map(normalize).filter(Boolean).filter((task) => ['pending', 'processing'].includes(task.status))
  target.tasks.sort((left, right) => Number(left.id) - Number(right.id))
  return target.tasks
}

export function messageTasksForSession(sessionId) {
  return sessionId ? sessionTasks(sessionId).tasks : []
}

export function removeMessageTask(sessionId, taskId) {
  const target = sessionTasks(sessionId)
  const index = target.tasks.findIndex((task) => Number(task.id) === Number(taskId))
  if (index >= 0) target.tasks.splice(index, 1)
}
