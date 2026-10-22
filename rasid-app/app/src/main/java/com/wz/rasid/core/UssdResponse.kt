package com.wz.rasid.core

/** Classifies the free-text carrier reply to a USSD request. */
object UssdResponse {
    // Checked first: several failure phrases contain a success phrase ("لم يتم التحويل").
    private val FAILURE = listOf(
        "لم يتم", "لم تتم", "غير ناجح", "فشل", "رصيد غير كاف", "الرصيد غير كاف", "رصيد غير كافٍ",
        "لا تملك رصيد", "لا يكفي", "غير كاف", "الرقم غير صحيح", "رقم غير صالح", "رقم أو رمز غير صحيح",
        "رمز غير صحيح", "كود غير صحيح", "pin غير صحيح", "غير مخول", "ليس لديك سماحية", "ليس لديك صلاحية",
        "لا توجد سماحية", "تعذر تنفيذ الطلب", "ملغي", "تم الإلغاء",
        "insufficient", "invalid", "failed", "not allowed", "wrong pin",
    )
    private val PENDING = listOf(
        "قيد المعالجة", "طلبك قيد", "جاري", "يرجى الانتظار", "انتظر",
        "in progress", "being processed",
    )
    private val SUCCESS = listOf(
        "تم التحويل", "تم تحويل", "تم التعبئة", "تم تعبئة", "تمت العملية", "تم الدفع", "تم تسديد", "بنجاح",
        "successful", "success",
    )

    fun classify(reply: String?): TxStatus {
        val text = reply?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return TxStatus.REVIEW
        return when {
            FAILURE.any { text.contains(it) } -> TxStatus.FAILED
            PENDING.any { text.contains(it) } -> TxStatus.PENDING
            SUCCESS.any { text.contains(it) } -> TxStatus.SUCCESS
            else -> TxStatus.REVIEW
        }
    }

    private val NUMBER = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?")

    /** All numbers in a reply, with thousands separators and Arabic-Indic digits handled. */
    fun numbers(reply: String): List<Double> =
        NUMBER.findAll(toLatinDigits(reply)).mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()

    /** Best guess at the balance in an inquiry reply: the first number after "رصيد", else the largest. */
    fun extractBalance(reply: String): Double? {
        val text = toLatinDigits(reply)
        val idx = text.indexOf("رصيد").takeIf { it >= 0 } ?: text.lowercase().indexOf("balance")
        if (idx >= 0) numbers(text.substring(idx)).firstOrNull()?.let { return it }
        return numbers(text).maxOrNull()
    }

    fun toLatinDigits(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٬' -> ','
                    '٫' -> '.'
                    else -> c
                },
            )
        }
    }
}
