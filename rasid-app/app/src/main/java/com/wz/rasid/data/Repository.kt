package com.wz.rasid.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.wz.rasid.core.Accounting
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.Channel
import com.wz.rasid.core.KnownNumber
import com.wz.rasid.core.LedgerEntry
import com.wz.rasid.core.MtnCodes
import com.wz.rasid.core.NumberSuggestions
import com.wz.rasid.core.OpType
import com.wz.rasid.core.SyriatelCodes
import com.wz.rasid.core.TxStatus
import com.wz.rasid.core.UssdCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.security.MessageDigest

enum class SimLayout(val label: String) {
    AUTO("تلقائي (حسب الشبكة)"),
    MTN_FIRST("الفتحة 1: MTN — الفتحة 2: سيرياتيل"),
    SYRIATEL_FIRST("الفتحة 1: سيرياتيل — الفتحة 2: MTN"),
}

data class AppSettings(
    val mtn: MtnCodes = MtnCodes(),
    val syriatel: SyriatelCodes = SyriatelCodes(),
    val simLayout: SimLayout = SimLayout.AUTO,
) {
    companion object {
        fun from(values: Map<String, String>) = AppSettings(
            mtn = MtnCodes(
                pin = values[Keys.MTN_PIN].orEmpty(),
                cashPin = values[Keys.MTN_CASH_PIN].orEmpty(),
                dslCode = values[Keys.MTN_DSL_CODE].orEmpty(),
                dslAreaCode = values[Keys.MTN_DSL_AREA].orEmpty(),
            ),
            syriatel = SyriatelCodes(
                distributorCode = values[Keys.SYR_SSC].orEmpty(),
                secretCode = values[Keys.SYR_SPC].orEmpty(),
            ),
            simLayout = values[Keys.SIM_LAYOUT]?.let { v -> SimLayout.entries.firstOrNull { it.name == v } } ?: SimLayout.AUTO,
        )
    }
}

object Keys {
    const val MTN_PIN = "mtn_pin"
    const val MTN_CASH_PIN = "mtn_cash_pin"
    const val MTN_DSL_CODE = "mtn_dsl_code"
    const val MTN_DSL_AREA = "mtn_dsl_area"
    const val SYR_SSC = "syr_ssc"
    const val SYR_SPC = "syr_spc"
    const val SIM_LAYOUT = "sim_layout"
    const val PASSWORD_HASH = "password_hash"

    /** Never written to backup files. */
    val SECRET = setOf(MTN_PIN, MTN_CASH_PIN, SYR_SPC, PASSWORD_HASH)
}

data class TransferDraft(
    val channel: Channel,
    val target: String,
    val amount: Double,
    val sellingPrice: Double,
    val isDebt: Boolean,
    val customerId: Long?,
    val customerName: String,
    val notes: String,
)

class Repository(private val db: AppDatabase) {
    private val dao = db.dao()

    val settings: Flow<AppSettings> =
        dao.observeSettings().map { rows -> AppSettings.from(rows.associate { it.key to it.value }) }
    val balances: Flow<List<ChannelBalanceEntity>> = dao.observeBalances()
    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()
    val customers: Flow<List<CustomerEntity>> = dao.observeCustomers()
    val shifts: Flow<List<ShiftEntity>> = dao.observeShifts()
    val openShift: Flow<ShiftEntity?> = dao.observeOpenShift()

    /** Outstanding balance per customer id; positive means the customer owes money. */
    val customerBalances: Flow<Map<Long, Double>> =
        combine(dao.observeDebtTransactions(), dao.observePayments()) { txs, payments ->
            val totals = HashMap<Long, Double>()
            txs.filter { it.status.isCompleted }.forEach { tx ->
                tx.customerId?.let { totals.merge(it, tx.sellingPrice) { a, b -> a + b } }
            }
            payments.forEach { totals.merge(it.customerId, -it.amount) { a, b -> a + b } }
            totals.mapValues { Accounting.round2(it.value) }
        }

    val knownNumbers: Flow<List<KnownNumber>> =
        combine(dao.observeNumberUsage(), dao.observeCustomers()) { usage, customers ->
            NumberSuggestions.merge(
                usage.map {
                    KnownNumber(
                        number = it.target,
                        carrier = it.channel.carrier,
                        isLandline = it.channel.type == OpType.DSL,
                        lastUsed = it.lastUsed,
                        uses = it.uses,
                    )
                },
                customers.associate { it.phone to it.name },
            )
        }

    fun transactions(shiftId: Long?, from: Long, to: Long): Flow<List<TransactionEntity>> =
        dao.observeTransactions(shiftId, from, to)

    fun shiftTransactions(shiftId: Long): Flow<List<TransactionEntity>> = dao.observeShiftTransactions(shiftId)

    fun customer(id: Long): Flow<CustomerEntity?> = dao.observeCustomer(id)

    fun customerPayments(id: Long): Flow<List<PaymentEntity>> = dao.observeCustomerPayments(id)

    fun ledger(customerId: Long): Flow<List<LedgerEntry>> =
        combine(dao.observeCustomerTransactions(customerId), dao.observeCustomerPayments(customerId)) { txs, payments ->
            ledgerEntries(txs, payments)
        }

    private fun ledgerEntries(txs: List<TransactionEntity>, payments: List<PaymentEntity>): List<LedgerEntry> =
        txs.filter { it.isDebt && it.status.isCompleted }.map {
            LedgerEntry(it.timestamp, "${it.channel.label} ${it.target} (${Accounting.money(it.amount)})", it.sellingPrice)
        } + payments.map {
            LedgerEntry(it.time, if (it.notes.isBlank()) "دفعة" else "دفعة: ${it.notes}", -it.amount)
        }

    suspend fun lastTransaction(): TransactionEntity? = dao.lastTransaction()

    /** Creates the per-channel balance rows, starter price categories and the first shift. */
    suspend fun ensureDefaults(): Unit = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = dao.balances().map { it.channel }.toSet()
        Channel.entries.filter { it !in existing }.forEach {
            dao.upsertBalance(ChannelBalanceEntity(it, current = 0.0, minimum = 0.0, costPer10k = 10_000.0, updatedAt = now))
        }
        if (dao.countCategories(Carrier.MTN) == 0) {
            listOf(500.0 to 550.0, 1000.0 to 1100.0).forEach { (v, p) -> dao.upsertCategory(CategoryEntity(carrier = Carrier.MTN, value = v, price = p)) }
        }
        if (dao.countCategories(Carrier.SYRIATEL) == 0) {
            listOf(500.0 to 550.0, 1000.0 to 1100.0, 2000.0 to 2200.0).forEach { (v, p) -> dao.upsertCategory(CategoryEntity(carrier = Carrier.SYRIATEL, value = v, price = p)) }
        }
        if (dao.openShift() == null) newShift()
        Unit
    }

    private suspend fun snapshot(): String =
        JSONObject().apply { dao.balances().forEach { put(it.channel.name, it.current) } }.toString()

    private suspend fun newShift(): Long =
        dao.insertShift(ShiftEntity(startTime = System.currentTimeMillis(), endTime = null, openingBalances = snapshot(), closingBalances = null))

    private suspend fun currentShiftId(): Long = dao.openShift()?.id ?: newShift()

    // Transactions

    suspend fun record(draft: TransferDraft, status: TxStatus, ussdCode: String?, response: String?): TransactionEntity =
        db.withTransaction {
            val costPer10k = dao.balance(draft.channel)?.costPer10k ?: 10_000.0
            val cost = Accounting.cost(draft.amount, costPer10k)
            val tx = TransactionEntity(
                channel = draft.channel,
                target = draft.target,
                amount = draft.amount,
                sellingPrice = draft.sellingPrice,
                costPrice = cost,
                profit = Accounting.profit(draft.sellingPrice, cost),
                isDebt = draft.isDebt,
                status = status,
                timestamp = System.currentTimeMillis(),
                shiftId = currentShiftId(),
                customerId = draft.customerId,
                customerName = draft.customerName,
                notes = draft.notes,
                ussdCode = ussdCode,
                ussdResponse = response,
            )
            val id = dao.insertTransaction(tx)
            if (status.isCompleted) adjustStock(draft.channel, -draft.amount)
            tx.copy(id = id)
        }

    /** Changing whether an operation counts as completed moves its amount back into or out of stock. */
    suspend fun setStatus(id: Long, status: TxStatus): Unit = db.withTransaction {
        val tx = dao.transaction(id) ?: return@withTransaction
        if (tx.status.isCompleted != status.isCompleted) {
            adjustStock(tx.channel, if (status.isCompleted) -tx.amount else tx.amount)
        }
        dao.updateTransaction(tx.copy(status = status))
    }

    // Balances

    private suspend fun adjustStock(channel: Channel, delta: Double) {
        val b = dao.balance(channel) ?: return
        dao.upsertBalance(b.copy(current = Accounting.round2(b.current + delta), updatedAt = System.currentTimeMillis()))
    }

    suspend fun addStock(channel: Channel, amount: Double) = db.withTransaction { adjustStock(channel, amount) }

    suspend fun setCurrentBalance(channel: Channel, value: Double) {
        val b = dao.balance(channel) ?: return
        dao.upsertBalance(b.copy(current = value, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setLimits(channel: Channel, minimum: Double, costPer10k: Double) {
        val b = dao.balance(channel) ?: return
        dao.upsertBalance(b.copy(minimum = minimum, costPer10k = costPer10k, updatedAt = System.currentTimeMillis()))
    }

    // Shifts

    suspend fun startNewShift(): Long = db.withTransaction {
        dao.openShift()?.let { dao.updateShift(it.copy(endTime = System.currentTimeMillis(), closingBalances = snapshot())) }
        newShift()
    }

    /** Returns (deleted, kept): completed debt sales stay so customer balances remain correct. */
    suspend fun deleteShiftLog(shiftId: Long): Pair<Int, Int> = db.withTransaction {
        val total = dao.countShiftTransactions(shiftId)
        val deleted = dao.deleteShiftTransactions(shiftId)
        deleted to (total - deleted)
    }

    // Customers

    suspend fun saveCustomer(id: Long?, name: String, phone: String): Result<Long> {
        val n = name.trim()
        val p = UssdCodes.normalizeMobile(phone).ifEmpty { phone.trim() }
        if (n.isEmpty() || p.isEmpty()) return Result.failure(IllegalArgumentException("اسم الزبون ورقم الهاتف مطلوبان"))
        return try {
            if (id == null) {
                Result.success(dao.insertCustomer(CustomerEntity(name = n, phone = p, createdAt = System.currentTimeMillis())))
            } else {
                val old = dao.customer(id) ?: return Result.failure(IllegalArgumentException("لم يتم العثور على الزبون"))
                dao.updateCustomer(old.copy(name = n, phone = p))
                Result.success(id)
            }
        } catch (e: SQLiteConstraintException) {
            Result.failure(IllegalArgumentException("الاسم أو الرقم مستخدم مسبقاً"))
        }
    }

    suspend fun customerByPhone(phone: String): CustomerEntity? = dao.customerByPhone(UssdCodes.normalizeMobile(phone))

    /** Customers with linked operations or payments are kept so their history stays intact. */
    suspend fun deleteCustomer(id: Long): Boolean = db.withTransaction {
        val c = dao.customer(id) ?: return@withTransaction false
        if (dao.countCustomerTransactions(id) > 0 || dao.countCustomerPayments(id) > 0) return@withTransaction false
        dao.deleteCustomer(c)
        true
    }

    suspend fun addPayment(customerId: Long, amount: Double, notes: String) {
        dao.insertPayment(PaymentEntity(customerId = customerId, amount = amount, time = System.currentTimeMillis(), notes = notes.trim()))
    }

    suspend fun deletePayment(payment: PaymentEntity) = dao.deletePayment(payment)

    /** Adds a payment equal to the outstanding debt, zeroing the account while keeping its history. */
    suspend fun settle(customerId: Long): Double = db.withTransaction {
        val entries = ledgerEntries(
            dao.observeCustomerTransactions(customerId).first(),
            dao.observeCustomerPayments(customerId).first(),
        )
        val balance = Accounting.balance(entries)
        if (balance > 0) {
            dao.insertPayment(PaymentEntity(customerId = customerId, amount = balance, time = System.currentTimeMillis(), notes = "رصد الحساب"))
        }
        balance
    }

    // Categories

    suspend fun saveCategory(id: Long?, carrier: Carrier, value: Double, price: Double) =
        dao.upsertCategory(CategoryEntity(id = id ?: 0, carrier = carrier, value = value, price = price))

    suspend fun deleteCategory(category: CategoryEntity) = dao.deleteCategory(category)

    // Settings

    private suspend fun put(key: String, value: String) = dao.putSetting(SettingEntity(key, value.trim()))

    suspend fun saveMtn(codes: MtnCodes) = db.withTransaction {
        put(Keys.MTN_PIN, codes.pin)
        put(Keys.MTN_CASH_PIN, codes.cashPin)
        put(Keys.MTN_DSL_CODE, codes.dslCode)
        put(Keys.MTN_DSL_AREA, codes.dslAreaCode)
    }

    suspend fun saveSyriatel(codes: SyriatelCodes) = db.withTransaction {
        put(Keys.SYR_SSC, codes.distributorCode)
        put(Keys.SYR_SPC, codes.secretCode)
    }

    suspend fun setSimLayout(layout: SimLayout) = put(Keys.SIM_LAYOUT, layout.name)

    suspend fun checkPassword(password: String): Boolean =
        (dao.setting(Keys.PASSWORD_HASH) ?: hash(DEFAULT_PASSWORD)) == hash(password)

    suspend fun changePassword(old: String, new: String): Boolean {
        if (!checkPassword(old) || new.isBlank()) return false
        put(Keys.PASSWORD_HASH, hash(new))
        return true
    }

    // Backup

    suspend fun exportBackup(): String = Backup.export(dao)

    suspend fun importBackup(json: String): Result<Unit> = runCatching {
        val parsed = Backup.parse(json)
        db.withTransaction { Backup.restore(dao, parsed) }
        ensureDefaults()
    }

    companion object {
        const val DEFAULT_PASSWORD = "123456"

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
