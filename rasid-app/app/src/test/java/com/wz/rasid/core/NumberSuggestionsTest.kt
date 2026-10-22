package com.wz.rasid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberSuggestionsTest {
    private val known = listOf(
        KnownNumber("0944123456", carrier = Carrier.MTN, lastUsed = 10, uses = 1),
        KnownNumber("0944123999", carrier = Carrier.MTN, lastUsed = 5, uses = 4),
        KnownNumber("0933111222", carrier = Carrier.SYRIATEL, lastUsed = 20, uses = 2),
        KnownNumber("011-2223333", carrier = Carrier.MTN, isLandline = true, lastUsed = 1, uses = 1),
    )

    @Test
    fun completesByPrefixMostUsedFirst() {
        val s = NumberSuggestions.suggest("0944", known, landline = false)
        assertEquals(listOf("0944123999", "0944123456"), s.map { it.number })
    }

    @Test
    fun acceptsInternationalAndArabicDigitInput() {
        assertEquals(listOf("0933111222"), NumberSuggestions.suggest("+96393", known, landline = false).map { it.number })
        assertEquals(listOf("0933111222"), NumberSuggestions.suggest("٠٩٣٣", known, landline = false).map { it.number })
        assertEquals(2, NumberSuggestions.suggest("944", known, landline = false).size)
    }

    @Test
    fun needsTwoDigitsAndHidesExactMatch() {
        assertTrue(NumberSuggestions.suggest("0", known, landline = false).isEmpty())
        assertTrue(NumberSuggestions.suggest("0933111222", known, landline = false).isEmpty())
    }

    @Test
    fun keepsLandlinesSeparateFromMobiles() {
        assertEquals(listOf("011-2223333"), NumberSuggestions.suggest("0112", known, landline = true).map { it.number })
        assertTrue(NumberSuggestions.suggest("01", known, landline = false).isEmpty())
    }

    @Test
    fun mergeNamesUsedNumbersAndAddsNewCustomers() {
        val merged = NumberSuggestions.merge(
            known,
            mapOf("0933111222" to "أحمد", "0955000111" to "سامر"),
        )
        assertEquals("أحمد", merged.first { it.number == "0933111222" }.name)
        assertEquals("سامر", merged.first { it.number == "0955000111" }.name)
        assertEquals(5, merged.size)
    }
}
