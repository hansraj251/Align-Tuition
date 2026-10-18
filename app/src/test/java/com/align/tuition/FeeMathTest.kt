package com.align.tuition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeeMathTest {
    @Test
    fun pendingFeeUsesEachStudentsOwnMonthlyFee() {
        assertEquals(300L, FeeMath.pendingFee(800L, 500L))
        assertEquals(700L, FeeMath.pendingFee(1200L, 500L))
    }

    @Test
    fun fullPaymentLeavesNoPendingBalance() {
        assertEquals(0L, FeeMath.pendingFee(800L, 800L))
        assertTrue(FeeMath.isFullyPaid(800L, 800L))
    }

    @Test
    fun extraPaymentDoesNotCreateNegativePendingFee() {
        assertEquals(0L, FeeMath.pendingFee(800L, 900L))
        assertTrue(FeeMath.isFullyPaid(800L, 900L))
    }

    @Test
    fun partialPaymentIsNotFullyPaid() {
        assertFalse(FeeMath.isFullyPaid(1000L, 400L))
    }
}
