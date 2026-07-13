package com.lhstack.tools.concurrent

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent 运行时线程池。
 *
 * awake-claw 用 tokio 承接模型请求与同轮工具并发；Kotlin 侧用线程池顶上。
 * 编译目标为 JDK17，但运行时可能是 JDK21+（JBR 21）。因此优先用反射创建
 * 虚拟线程池（Executors.newVirtualThreadPerTaskExecutor），拿不到再退回
 * 通用线程池。反射避免了编译期直接引用 JDK21 API。
 */
object AgentExecutors {

    private const val THREAD_NAME_PREFIX = "jtools-agent-"

    /**
     * 全局共享执行器：承接模型请求、同轮工具并发。
     * 虚拟线程下每个任务一根线程，天然适合大量阻塞式 IO（HTTP、工具调用）。
     */
    val shared: ExecutorService by lazy { createExecutor() }

    private fun createExecutor(): ExecutorService {
        return tryCreateVirtualThreadExecutor() ?: createPlatformThreadPool()
    }

    /**
     * 反射调用 JDK21 的 Executors.newVirtualThreadPerTaskExecutor()。
     * 运行时若无该方法（JDK < 21）或调用失败，返回 null 交由上层回退。
     */
    private fun tryCreateVirtualThreadExecutor(): ExecutorService? {
        return try {
            val method = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
            method.invoke(null) as? ExecutorService
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 通用平台线程池回退方案：核心线程随 CPU 伸缩，队列无界前先扩容到上限，
     * 空闲线程 60s 回收。命名带序号，便于线程转储定位。
     */
    private fun createPlatformThreadPool(): ExecutorService {
        val processors = Runtime.getRuntime().availableProcessors()
        val corePoolSize = processors.coerceAtLeast(2)
        val maxPoolSize = (processors * 8).coerceAtLeast(32)
        val executor = ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            namedThreadFactory(),
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
        executor.allowCoreThreadTimeOut(true)
        return executor
    }

    private fun namedThreadFactory(): ThreadFactory {
        val counter = AtomicLong(0)
        return ThreadFactory { runnable ->
            Thread(runnable).apply {
                name = "$THREAD_NAME_PREFIX${counter.incrementAndGet()}"
                isDaemon = true
            }
        }
    }
}
