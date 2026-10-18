package com.align.tuition

object FeeMath {
    fun pendingFee(monthlyFee: Long, paidAmount: Long): Long =
        (monthlyFee - paidAmount).coerceAtLeast(0L)

    fun isFullyPaid(monthlyFee: Long, paidAmount: Long): Boolean =
        pendingFee(monthlyFee, paidAmount) == 0L
}
