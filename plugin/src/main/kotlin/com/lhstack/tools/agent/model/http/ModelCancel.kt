package com.lhstack.tools.agent.model.http

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 取消令牌。对齐 awake 的 CancellationToken。
 * 普通实例自己持有 cancelled 状态；[live] 视图把 isCancelled/cancel/interrupt 转发到当前批次令牌，
 * 对应 awake 的 ToolCancelSlot：只有工具批次进行中才存在可取消的 tool token。
 */
class ModelCancel(
    private val live: AtomicReference<ModelCancel>? = null,
) {
    private val cancelled = AtomicBoolean(false)
    private val interruptIds = AtomicLong(0)
    private val interruptActions = linkedMapOf<Long, () -> Unit>()
    private val lock = Any()

    fun cancel() {
        val target = currentLive()
        if (live != null) {
            target?.cancel()
            return
        }
        val actions = synchronized(lock) {
            cancelled.set(true)
            interruptActions.values.toList().also { interruptActions.clear() }
        }
        actions.forEach { action -> runCatching(action) }
    }

    fun isCancelled(): Boolean {
        val target = currentLive()
        if (live != null) return target?.isCancelled() == true
        return cancelled.get()
    }

    /** Registers one operation interrupt. A cancelled token invokes it immediately. */
    fun registerInterrupt(action: () -> Unit): Long {
        val target = currentLive()
        if (live != null) return target?.registerInterrupt(action) ?: -1L
        val id = interruptIds.incrementAndGet()
        val executeNow = synchronized(lock) {
            if (cancelled.get()) true else {
                interruptActions[id] = action
                false
            }
        }
        if (executeNow) action()
        return id
    }

    fun clearInterrupt(id: Long) {
        val target = currentLive()
        if (live != null) {
            target?.clearInterrupt(id)
            return
        }
        synchronized(lock) { interruptActions.remove(id) }
    }

    fun reset() {
        synchronized(lock) {
            cancelled.set(false)
            interruptActions.clear()
        }
        live?.set(null)
    }

    private fun currentLive(): ModelCancel? = live?.get()?.takeUnless { it === this }
}

class ModelRequestCancelledException : RuntimeException("model request cancelled")
