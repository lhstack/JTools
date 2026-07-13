package com.lhstack.tools.agent.model.http

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 模型请求取消令牌。对齐 awake-claw 的 Arc<tokio::sync::Notify>。
 *
 * awake 用 tokio::select! 在 cancel.notified() 与请求 future 之间竞速；
 * Kotlin 侧 OkHttp 通过注册中断动作绑定当前 Call；取消时由请求层调用 Call.cancel()
 * 并关闭响应体，打断阻塞中的 SSE 读取。
 */
class ModelCancel {

    private val cancelled = AtomicBoolean(false)
    private val interruptAction = AtomicReference<(() -> Unit)?>(null)

    fun cancel() {
        cancelled.set(true)
        interruptAction.getAndSet(null)?.invoke()
    }

    fun isCancelled(): Boolean = cancelled.get()

    /**
     * 注册中断动作（如关闭响应流）。若已取消则立即执行。
     */
    fun registerInterrupt(action: () -> Unit) {
        interruptAction.set(action)
        if (cancelled.get()) {
            interruptAction.getAndSet(null)?.invoke()
        }
    }

    fun clearInterrupt() {
        interruptAction.set(null)
    }

    fun reset() {
        cancelled.set(false)
        interruptAction.set(null)
    }
}

/** 模型请求被取消时抛出，对齐 awake 的 ModelRequestCancelled。 */
class ModelRequestCancelledException : RuntimeException("model request cancelled")
