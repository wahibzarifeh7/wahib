package com.wz.rasid.core

enum class Carrier(val label: String) {
    MTN("MTN"),
    SYRIATEL("سيرياتيل"),
}

enum class OpType(val label: String) {
    PREPAID("مسبق"),
    POSTPAID("لاحق"),
    CASH("كاش"),
    DSL("DSL"),
}

/** One sellable stock: a carrier plus an operation type, each with its own balance and cost. */
enum class Channel(val carrier: Carrier, val type: OpType) {
    MTN_PREPAID(Carrier.MTN, OpType.PREPAID),
    MTN_POSTPAID(Carrier.MTN, OpType.POSTPAID),
    MTN_CASH(Carrier.MTN, OpType.CASH),
    MTN_DSL(Carrier.MTN, OpType.DSL),
    SYRIATEL_PREPAID(Carrier.SYRIATEL, OpType.PREPAID),
    SYRIATEL_POSTPAID(Carrier.SYRIATEL, OpType.POSTPAID),
    SYRIATEL_CASH(Carrier.SYRIATEL, OpType.CASH);

    val label: String get() = "${carrier.label} ${type.label}"

    companion object {
        fun of(carrier: Carrier, type: OpType): Channel? =
            entries.firstOrNull { it.carrier == carrier && it.type == type }

        fun forCarrier(carrier: Carrier): List<Channel> = entries.filter { it.carrier == carrier }
    }
}

enum class TxStatus(val label: String) {
    SUCCESS("ناجحة"),
    FAILED("فاشلة"),
    PENDING("قيد المعالجة"),
    REVIEW("تحتاج تدقيق"),
    RECORDED("مسجلة بدون تحويل");

    /** Completed operations consume stock and count toward sales, profit and debts. */
    val isCompleted: Boolean get() = this == SUCCESS || this == RECORDED
}

/** Syrian governorates and their landline area codes, used for DSL bills. */
enum class Governorate(val label: String, val areaCode: String) {
    DAMASCUS("دمشق", "011"),
    RURAL_DAMASCUS("ريف دمشق", "011"),
    ALEPPO("حلب", "021"),
    HOMS("حمص", "031"),
    HAMA("حماة", "033"),
    LATAKIA("اللاذقية", "041"),
    TARTUS("طرطوس", "043"),
    IDLIB("إدلب", "023"),
    RAQQA("الرقة", "022"),
    DEIR_EZ_ZOR("دير الزور", "051"),
    HASAKAH("الحسكة", "052"),
    DARAA("درعا", "015"),
    SUWAYDA("السويداء", "016"),
    QUNEITRA("القنيطرة", "014"),
}
