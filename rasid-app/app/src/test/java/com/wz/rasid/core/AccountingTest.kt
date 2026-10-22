package com.wz.rasid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingTest {
    @Test
    fun costAndProfitUsePricePer10kUnits() {
        assertEquals(9500.0, Accounting.cost(10_000.0, 9500.0), 0.0)
        assertEquals(475.0, Accounting.cost(500.0, 9500.0), 0.0)
        assertEquals(75.0, Accounting.profit(550.0, 475.0), 0.0)
    }

    @Test
    fun categoryPriceMatchesExactDenomination() {
        val cats = listOf(Category(500.0, 550.0), Category(1000.0, 1100.0))
        assertEquals(1100.0, Accounting.priceFor(1000.0, cats)!!, 0.0)
        assertNull(Accounting.priceFor(750.0, cats))
    }

    @Test
    fun totalsCountOnlyCompletedOperations() {
        val sales = listOf(
            Sale(Channel.MTN_PREPAID, 1000.0, 1100.0, 150.0, isDebt = false, status = TxStatus.SUCCESS),
            Sale(Channel.MTN_PREPAID, 500.0, 550.0, 75.0, isDebt = true, status = TxStatus.RECORDED),
            Sale(Channel.SYRIATEL_CASH, 9000.0, 9200.0, 200.0, isDebt = true, status = TxStatus.FAILED),
            Sale(Channel.SYRIATEL_CASH, 9000.0, 9200.0, 200.0, isDebt = false, status = TxStatus.REVIEW),
        )
        assertEquals(Totals(2, 1650.0, 225.0, 550.0), Accounting.totals(sales))
        assertEquals(mapOf(Channel.MTN_PREPAID to 225.0), Accounting.profitByChannel(sales))
    }

    @Test
    fun statementKeepsRunningBalance() {
        val lines = Accounting.statement(
            listOf(
                LedgerEntry(3, "دفعة", -1000.0),
                LedgerEntry(1, "MTN مسبق", 1100.0),
                LedgerEntry(2, "سيرياتيل مسبق", 550.0),
            ),
        )
        assertEquals(listOf(1100.0, 1650.0, 650.0), lines.map { it.runningBalance })
        val text = Accounting.statementText("أحمد", lines, now = 0)
        assertTrue(text.startsWith("كشف حساب الزبون: أحمد"))
        assertTrue(text.endsWith("الرصيد الحالي: 650 ل.س (ذمة مستحقة)"))
    }

    @Test
    fun formatsMoney() {
        assertEquals("1,250", Accounting.money(1250.0))
        assertEquals("12.50", Accounting.money(12.5))
    }
}
