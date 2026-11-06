package com.dailytodo.mydeepseek

import org.junit.Assert.*
import org.junit.Test

class PetFrameTest {
    @Test fun aBurstSchedulesOnceAndPaintsOnlyTheNewestFrame() {
        val queue = FrameMailbox<Int>()
        assertTrue(queue.offer(1))
        for (i in 2..240) assertFalse(queue.offer(i))
        assertEquals(240, queue.take())
        assertNull(queue.take())
        assertTrue(queue.offer(241))
        assertEquals(241, queue.take())
    }

    @Test fun movingAcrossFractionalPixelsDoesNotResizeTheSurface() {
        for (density in listOf(1.0, 2.625, 3.0, 3.5)) {
            val widths = (0..800).map { i ->
                val left = i * 0.13
                PetFrame.extent(((left + 264.0) - left) * density)
            }.toSet()
            assertEquals(1, widths.size)
            assertTrue(widths.single() >= 264.0 * density)
        }
    }

    @Test fun invalidBridgeCoordinatesAreRejected() {
        val good = PetFrame(0.0, 1.0, 100.0, 100.0, 2.0, 3.0, 200.0, 5.0, 0.0, 0.0)
        assertTrue(good.valid())
        assertFalse(good.copy(x = Double.NaN).valid())
        assertFalse(good.copy(vx = Double.POSITIVE_INFINITY).valid())
        assertFalse(good.copy(width = 0.0).valid())
        assertFalse(good.copy(size = 2000.0).valid())
    }
}
