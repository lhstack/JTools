package com.lhstack.tools.agent.model.http

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A cancellation token that can interrupt every concurrent operation registered against it. */
class ModelCancel {
    private val cancelled = AtomicBoolean(false)
    private val interruptIds = AtomicLong(0)
    private val interruptActions = linkedMapOf<Long, () -> Unit>()
    private val lock = Any()

    fun cancel() {
        val actions = synchronized(lock) {
            cancelled.set(true)
            interruptActions.values.toList().also { interruptActions.clear() }
        }
        actions.forEach { action -> runCatching(action) }
    }

    fun isCancelled(): Boolean = cancelled.get()

    /** Registers one operation interrupt. A cancelled token invokes it immediately. */
    fun registerInterrupt(action: () -> Unit): Long {
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
        synchronized(lock) { interruptActions.remove(id) }
    }

    fun reset() {
        synchronized(lock) {
            cancelled.set(false)
            interruptActions.clear()
        }
    }
}

class ModelRequestCancelledException : RuntimeException("model request cancelled")
