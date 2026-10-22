package com.wz.rasid.core

/** A number the shop has sold to before, or a saved customer's number. */
data class KnownNumber(
    val number: String,
    val name: String = "",
    val carrier: Carrier? = null,
    val isLandline: Boolean = false,
    val lastUsed: Long = 0,
    val uses: Int = 0,
)

/** Completes a partially typed number from numbers used in completed operations and saved customers. */
object NumberSuggestions {
    const val MIN_DIGITS = 2

    /**
     * Puts a partially typed mobile into the stored 09… form so "+963 94", "0094" and "94"
     * all match "094…". Landlines are compared on their digits only.
     */
    fun typedDigits(input: String, landline: Boolean): String {
        var d = UssdResponse.toLatinDigits(input).filter(Char::isDigit)
        if (landline) return d
        d = d.removePrefix("00")
        return when {
            d.startsWith("963") -> "0" + d.removePrefix("963")
            d.startsWith("9") -> "0$d"
            else -> d
        }
    }

    /** Most-used, then most-recent numbers that start with what was typed, excluding an exact match. */
    fun suggest(input: String, known: List<KnownNumber>, landline: Boolean, limit: Int = 5): List<KnownNumber> {
        val typed = typedDigits(input, landline)
        if (typed.length < MIN_DIGITS) return emptyList()
        return known.asSequence()
            .filter { it.isLandline == landline }
            .filter {
                val n = it.number.filter(Char::isDigit)
                n.startsWith(typed) && n != typed
            }
            .sortedWith(compareByDescending<KnownNumber> { it.uses }.thenByDescending { it.lastUsed })
            .take(limit)
            .toList()
    }

    /** Adds customer names to used numbers, and includes customers who have no completed operations yet. */
    fun merge(used: List<KnownNumber>, customers: Map<String, String>): List<KnownNumber> {
        val withNames = used.map { k -> customers[k.number]?.let { k.copy(name = it) } ?: k }
        val seen = used.filter { !it.isLandline }.map { it.number }.toSet()
        return withNames + customers.filterKeys { it !in seen }.map { (phone, name) -> KnownNumber(phone, name) }
    }
}
