package com.wz.rasid.data

import com.wz.rasid.core.Carrier
import com.wz.rasid.core.Channel
import com.wz.rasid.core.TxStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON backup of everything except secret codes and the password, so a shared
 * backup file never carries the distributor PINs.
 */
object Backup {
    private const val FORMAT = "rasid-backup"
    private const val VERSION = 1

    class Parsed(
        val balances: List<ChannelBalanceEntity>,
        val categories: List<CategoryEntity>,
        val customers: List<CustomerEntity>,
        val payments: List<PaymentEntity>,
        val shifts: List<ShiftEntity>,
        val transactions: List<TransactionEntity>,
        val settings: List<SettingEntity>,
    )

    suspend fun export(dao: RasidDao): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
        root.put("balances", JSONArray(dao.balances().map {
            JSONObject().put("channel", it.channel.name).put("current", it.current).put("minimum", it.minimum)
                .put("costPer10k", it.costPer10k).put("updatedAt", it.updatedAt)
        }))
        root.put("categories", JSONArray(dao.allCategories().map {
            JSONObject().put("id", it.id).put("carrier", it.carrier.name).put("value", it.value).put("price", it.price)
        }))
        root.put("customers", JSONArray(dao.allCustomers().map {
            JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone).put("createdAt", it.createdAt)
        }))
        root.put("payments", JSONArray(dao.allPayments().map {
            JSONObject().put("id", it.id).put("customerId", it.customerId).put("amount", it.amount)
                .put("time", it.time).put("notes", it.notes)
        }))
        root.put("shifts", JSONArray(dao.allShifts().map {
            JSONObject().put("id", it.id).put("startTime", it.startTime).put("endTime", it.endTime ?: JSONObject.NULL)
                .put("openingBalances", it.openingBalances).put("closingBalances", it.closingBalances ?: JSONObject.NULL)
        }))
        root.put("transactions", JSONArray(dao.allTransactions().map {
            JSONObject().put("id", it.id).put("channel", it.channel.name).put("target", it.target)
                .put("amount", it.amount).put("sellingPrice", it.sellingPrice).put("costPrice", it.costPrice)
                .put("profit", it.profit).put("isDebt", it.isDebt).put("status", it.status.name)
                .put("timestamp", it.timestamp).put("shiftId", it.shiftId)
                .put("customerId", it.customerId ?: JSONObject.NULL).put("customerName", it.customerName)
                .put("notes", it.notes).put("ussdCode", it.ussdCode ?: JSONObject.NULL)
                .put("ussdResponse", it.ussdResponse ?: JSONObject.NULL)
        }))
        root.put("settings", JSONObject().apply {
            dao.allSettings().filter { it.key !in Keys.SECRET }.forEach { put(it.key, it.value) }
        })
        return root.toString(2)
    }

    private fun JSONObject.optStr(key: String): String? = if (isNull(key)) null else getString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

    /** Throws on malformed input so nothing is written unless the whole file is readable. */
    fun parse(json: String): Parsed {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "ملف غير صالح أو تالف" }
        require(root.optInt("version") <= VERSION) { "النسخة الاحتياطية من إصدار أحدث من التطبيق" }
        val settings = root.optJSONObject("settings")
        return Parsed(
            balances = root.optJSONArray("balances").mapObjects {
                ChannelBalanceEntity(
                    Channel.valueOf(it.getString("channel")), it.getDouble("current"), it.getDouble("minimum"),
                    it.getDouble("costPer10k"), it.getLong("updatedAt"),
                )
            },
            categories = root.optJSONArray("categories").mapObjects {
                CategoryEntity(it.getLong("id"), Carrier.valueOf(it.getString("carrier")), it.getDouble("value"), it.getDouble("price"))
            },
            customers = root.optJSONArray("customers").mapObjects {
                CustomerEntity(it.getLong("id"), it.getString("name"), it.getString("phone"), it.getLong("createdAt"))
            },
            payments = root.optJSONArray("payments").mapObjects {
                PaymentEntity(it.getLong("id"), it.getLong("customerId"), it.getDouble("amount"), it.getLong("time"), it.optString("notes"))
            },
            shifts = root.optJSONArray("shifts").mapObjects {
                ShiftEntity(it.getLong("id"), it.getLong("startTime"), it.optLongOrNull("endTime"), it.getString("openingBalances"), it.optStr("closingBalances"))
            },
            transactions = root.optJSONArray("transactions").mapObjects {
                TransactionEntity(
                    id = it.getLong("id"),
                    channel = Channel.valueOf(it.getString("channel")),
                    target = it.getString("target"),
                    amount = it.getDouble("amount"),
                    sellingPrice = it.getDouble("sellingPrice"),
                    costPrice = it.getDouble("costPrice"),
                    profit = it.getDouble("profit"),
                    isDebt = it.getBoolean("isDebt"),
                    status = TxStatus.valueOf(it.getString("status")),
                    timestamp = it.getLong("timestamp"),
                    shiftId = it.getLong("shiftId"),
                    customerId = it.optLongOrNull("customerId"),
                    customerName = it.optString("customerName"),
                    notes = it.optString("notes"),
                    ussdCode = it.optStr("ussdCode"),
                    ussdResponse = it.optStr("ussdResponse"),
                )
            },
            settings = settings?.let { s ->
                s.keys().asSequence().filter { it !in Keys.SECRET }.map { SettingEntity(it, s.getString(it)) }.toList()
            }.orEmpty(),
        )
    }

    /** Replaces all data; must run inside a database transaction. Secret codes on the device are kept. */
    suspend fun restore(dao: RasidDao, p: Parsed) {
        dao.clearTransactions()
        dao.clearPayments()
        dao.clearCustomers()
        dao.clearCategories()
        dao.clearShifts()
        dao.insertCustomers(p.customers)
        dao.insertPayments(p.payments)
        dao.insertCategories(p.categories)
        dao.insertShifts(p.shifts)
        dao.insertTransactions(p.transactions)
        dao.upsertBalances(p.balances)
        dao.upsertSettings(p.settings)
    }
}
