package com.munjed.husk

import com.munjed.husk.helper.nextQuoteIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuotesTest {

    @Test
    fun `next quote is never the one just shown and always in range`() {
        val random = Random(7)
        repeat(1000) {
            val current = random.nextInt(80)
            val next = nextQuoteIndex(current, 80, random)
            assertNotEquals(current, next)
            assertTrue(next in 0 until 80)
        }
    }

    @Test
    fun `a stale index from a longer list still lands in range`() {
        // the list can shrink between versions while the saved index stays
        repeat(100) { assertTrue(nextQuoteIndex(500, 80) in 0 until 80) }
    }

    @Test
    fun `one quote or none stays put`() {
        assertEquals(0, nextQuoteIndex(0, 1))
        assertEquals(0, nextQuoteIndex(3, 0))
    }
}
