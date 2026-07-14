package com.lhstack.tools.agent.model.http

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCancelTest {
    @Test
    fun `cancel interrupts every concurrently registered operation`() {
        val cancel = ModelCancel()
        val interrupted = AtomicInteger()

        cancel.registerInterrupt { interrupted.incrementAndGet() }
        cancel.registerInterrupt { interrupted.incrementAndGet() }
        cancel.registerInterrupt { interrupted.incrementAndGet() }
        cancel.cancel()

        assertTrue(cancel.isCancelled())
        assertEquals(3, interrupted.get())
    }

    @Test
    fun `cleared operation is not interrupted`() {
        val cancel = ModelCancel()
        val interrupted = AtomicInteger()
        val cleared = cancel.registerInterrupt { interrupted.incrementAndGet() }
        cancel.registerInterrupt { interrupted.incrementAndGet() }

        cancel.clearInterrupt(cleared)
        cancel.cancel()

        assertEquals(1, interrupted.get())
    }

    @Test
    fun `registration after cancellation is interrupted immediately`() {
        val cancel = ModelCancel()
        val interrupted = AtomicInteger()
        cancel.cancel()

        cancel.registerInterrupt { interrupted.incrementAndGet() }

        assertEquals(1, interrupted.get())
    }
}
