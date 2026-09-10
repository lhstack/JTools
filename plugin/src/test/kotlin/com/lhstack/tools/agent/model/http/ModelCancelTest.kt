package com.lhstack.tools.agent.model.http

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `live slot only cancels current tool batch`() {
        val slot = AtomicReference<ModelCancel>(null)
        val view = ModelCancel(slot)
        val interrupted = AtomicInteger()

        view.cancel()
        assertFalse(view.isCancelled())
        assertEquals(0, interrupted.get())

        val batch = ModelCancel()
        slot.set(batch)
        view.registerInterrupt { interrupted.incrementAndGet() }
        view.cancel()

        assertTrue(batch.isCancelled())
        assertEquals(1, interrupted.get())

        slot.set(null)
        assertFalse(view.isCancelled())
    }
}
