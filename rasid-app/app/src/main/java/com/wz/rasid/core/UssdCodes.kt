package com.wz.rasid.core

import java.math.BigDecimal

data class MtnCodes(
    val pin: String = "",
    val cashPin: String = "",
    val dslCode: String = "",
    val dslAreaCode: String = "",
)

data class SyriatelCodes(
    val distributorCode: String = "",
    val secretCode: String = "",
)

class MissingCodesException(message: String) : IllegalStateException(message)

/** Builds the distributor USSD strings for transfers and inquiries on MTN and Syriatel. */
object UssdCodes {
    const val MTN_PIN_MISSING = "يرجى إدخال الرمز السري في إعدادات MTN."
    const val MTN_CASH_PIN_MISSING = "يرجى إدخال رمز الكاش في إعدادات MTN."
    const val MTN_DSL_MISSING = "يرجى إدخال كود DSL ومحافظة DSL في إعدادات MTN."
    const val SYRIATEL_SPC_MISSING = "يرجى إدخال الرمز السري (SPC) في إعدادات سيرياتيل."
    const val SYRIATEL_CODES_MISSING = "يرجى إدخال رمز الموزع (SSC) والرمز السري (SPC) في إعدادات سيرياتيل."

    private val MOBILE = Regex("^09\\d{8}$")

    private fun need(value: String, message: String): String =
        value.trim().ifEmpty { throw MissingCodesException(message) }

    /** Mobile numbers in local form: digits only, with a 963 / 00963 prefix replaced by 0. */
    fun normalizeMobile(raw: String): String {
        var d = raw.filter(Char::isDigit).removePrefix("00")
        if (d.startsWith("963")) d = d.removePrefix("963")
        if (d.length == 9 && d.startsWith("9")) d = "0$d"
        return d
    }

    fun isValidMobile(raw: String): Boolean = MOBILE.matches(normalizeMobile(raw))

    /**
     * DSL bills address the landline as 963 + area code (no leading 0) + subscriber number.
     * Accepts "011-1234567", "0111234567" or a bare "1234567" combined with [defaultArea].
     */
    fun dslLine(raw: String, defaultArea: String): String {
        val parts = raw.split('-', ' ', '/').map { it.filter(Char::isDigit) }.filter { it.isNotEmpty() }
        val area: String
        val number: String
        when {
            parts.size >= 2 -> {
                area = parts[0]
                number = parts.drop(1).joinToString("")
            }
            parts.size == 1 && (parts[0].startsWith("0") || parts[0].startsWith("963")) -> {
                area = ""
                number = parts[0]
            }
            else -> {
                area = need(defaultArea.filter(Char::isDigit), MTN_DSL_MISSING)
                number = parts.firstOrNull().orEmpty()
            }
        }
        val local = (area.removePrefix("963").trimStart('0') + number.removePrefix("963").trimStart('0'))
        require(local.isNotEmpty()) { "رقم الهاتف الأرضي غير صالح" }
        return "963$local"
    }

    fun formatAmount(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toLong().toString()
        else BigDecimal(amount.toString()).stripTrailingZeros().toPlainString()

    fun transfer(channel: Channel, target: String, amount: Double, mtn: MtnCodes, syriatel: SyriatelCodes): String {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        val amt = formatAmount(amount)
        if (channel != Channel.MTN_DSL) require(isValidMobile(target)) { "رقم الموبايل غير صالح" }
        val phone = normalizeMobile(target)
        return when (channel) {
            Channel.MTN_PREPAID -> "*150*${need(mtn.pin, MTN_PIN_MISSING)}*$phone*$amt#"
            Channel.MTN_POSTPAID -> "*154*${need(mtn.pin, MTN_PIN_MISSING)}*$phone*$amt#"
            Channel.MTN_CASH -> "*151*1*${need(mtn.cashPin, MTN_CASH_PIN_MISSING)}*$phone*$amt#"
            Channel.MTN_DSL -> "*160*${need(mtn.dslCode, MTN_DSL_MISSING)}*${dslLine(target, mtn.dslAreaCode)}*$amt#"
            Channel.SYRIATEL_PREPAID -> syriatelTransfer("*150*1*", "1", phone, amt, syriatel)
            Channel.SYRIATEL_POSTPAID -> syriatelTransfer("*150*1*", "5", phone, amt, syriatel)
            Channel.SYRIATEL_CASH -> syriatelTransfer("*150*10*", "1", phone, amt, syriatel)
        }
    }

    /** Syriatel repeats the target number as a confirmation field. */
    private fun syriatelTransfer(prefix: String, kind: String, phone: String, amt: String, codes: SyriatelCodes): String =
        "$prefix${need(codes.secretCode, SYRIATEL_SPC_MISSING)}*$kind*$amt*$phone*$phone#"

    fun hasBalanceInquiry(channel: Channel): Boolean =
        channel != Channel.MTN_POSTPAID && channel != Channel.MTN_DSL

    /** Balance inquiry for a channel, or null when the carrier has no inquiry code for it. */
    fun balanceInquiry(channel: Channel, syriatel: SyriatelCodes): String? = when (channel) {
        Channel.MTN_PREPAID -> "*155*1#"
        Channel.MTN_CASH -> "*151*3#"
        Channel.MTN_POSTPAID, Channel.MTN_DSL -> null
        Channel.SYRIATEL_PREPAID -> "*150*2*${syriatelPair(syriatel)}*1#"
        Channel.SYRIATEL_POSTPAID -> "*150*2*${syriatelPair(syriatel)}*5#"
        Channel.SYRIATEL_CASH -> "*150*12*${syriatelPair(syriatel)}*3#"
    }

    fun lastTransfers(carrier: Carrier, syriatel: SyriatelCodes, count: Int = 0): String = when (carrier) {
        Carrier.MTN -> "*155*3#"
        Carrier.SYRIATEL -> "*150*6*${syriatelPair(syriatel)}*$count#"
    }

    private fun syriatelPair(codes: SyriatelCodes): String {
        val ssc = need(codes.distributorCode, SYRIATEL_CODES_MISSING)
        val spc = need(codes.secretCode, SYRIATEL_CODES_MISSING)
        return "$ssc*$spc"
    }

    /** Hides secret codes when a USSD string is shown or stored. */
    fun mask(code: String, mtn: MtnCodes, syriatel: SyriatelCodes): String {
        var out = code
        listOf(mtn.pin, mtn.cashPin, syriatel.secretCode)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .forEach { secret -> out = out.replace("*$secret*", "*${"•".repeat(secret.length)}*") }
        return out
    }
}
