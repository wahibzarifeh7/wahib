package com.wz.rasid.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

data class Category(val value: Double, val price: Double)

/** Minimal view of a transaction for totals; the data layer maps its rows onto this. */
data class Sale(
    val channel: Channel,
    val amount: Double,
    val sellingPrice: Double,
    val profit: Double,
    val isDebt: Boolean,
    val status: TxStatus,
)

data class Totals(val count: Int, val sales: Double, val profit: Double, val debts: Double) {
    companion object {
        val ZERO = Totals(0, 0.0, 0.0, 0.0)
    }
}

/** A line in a customer statement: a debt sale (positive) or a payment (negative). */
data class LedgerEntry(val time: Long, val description: String, val amount: Double)

data class StatementLine(val entry: LedgerEntry, val runningBalance: Double)

object Accounting {
    fun round2(v: Double): Double = round(v * 100) / 100

    /** Purchase cost of [amount] units when 10,000 units cost [costPer10k]. */
    fun cost(amount: Double, costPer10k: Double): Double = round2(amount * costPer10k / 10_000)

    fun profit(sellingPrice: Double, cost: Double): Double = round2(sellingPrice - cost)

    /** The configured selling price for an exact denomination, if there is one. */
    fun priceFor(amount: Double, categories: List<Category>): Double? =
        categories.firstOrNull { abs(it.value - amount) < 0.001 }?.price

    fun totals(sales: List<Sale>): Totals {
        val done = sales.filter { it.status.isCompleted }
        return Totals(
            count = done.size,
            sales = round2(done.sumOf { it.sellingPrice }),
            profit = round2(done.sumOf { it.profit }),
            debts = round2(done.filter { it.isDebt }.sumOf { it.sellingPrice }),
        )
    }

    fun profitByChannel(sales: List<Sale>): Map<Channel, Double> =
        sales.filter { it.status.isCompleted }
            .groupBy { it.channel }
            .mapValues { (_, list) -> round2(list.sumOf { it.profit }) }

    /** Chronological statement; a positive running balance means the customer owes money. */
    fun statement(entries: List<LedgerEntry>): List<StatementLine> {
        var running = 0.0
        return entries.sortedBy { it.time }.map { e ->
            running = round2(running + e.amount)
            StatementLine(e, running)
        }
    }

    fun balance(entries: List<LedgerEntry>): Double = round2(entries.sumOf { it.amount })

    fun money(v: Double): String {
        val r = round2(v)
        val whole = r % 1.0 == 0.0
        return if (whole) String.format(Locale.US, "%,d", r.toLong()) else String.format(Locale.US, "%,.2f", r)
    }

    /** Plain-text statement suitable for sharing over WhatsApp or SMS. */
    fun statementText(customerName: String, lines: List<StatementLine>, now: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val balance = lines.lastOrNull()?.runningBalance ?: 0.0
        return buildString {
            appendLine("كشف حساب الزبون: $customerName")
            appendLine("التاريخ: ${fmt.format(Date(now))}")
            appendLine("------------------------")
            for (line in lines) {
                val sign = if (line.entry.amount >= 0) "+" else "-"
                appendLine("${fmt.format(Date(line.entry.time))} | ${line.entry.description} | $sign${money(abs(line.entry.amount))} | الرصيد: ${money(line.runningBalance)}")
            }
            appendLine("------------------------")
            append(
                when {
                    balance > 0 -> "الرصيد الحالي: ${money(balance)} ل.س (ذمة مستحقة)"
                    balance < 0 -> "الرصيد الحالي: ${money(-balance)} ل.س (رصيد دائن)"
                    else -> "الرصيد الحالي: 0 ل.س (الحساب مصفّر)"
                },
            )
        }
    }
}
