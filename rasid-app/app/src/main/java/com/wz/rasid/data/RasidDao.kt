package com.wz.rasid.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface RasidDao {
    // Transactions

    @Query(
        "SELECT * FROM transactions WHERE (:shiftId IS NULL OR shiftId = :shiftId) " +
            "AND timestamp BETWEEN :from AND :to ORDER BY timestamp DESC",
    )
    fun observeTransactions(shiftId: Long?, from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE shiftId = :shiftId ORDER BY timestamp DESC")
    fun observeShiftTransactions(shiftId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE customerId = :customerId ORDER BY timestamp DESC")
    fun observeCustomerTransactions(customerId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE isDebt = 1 AND customerId IS NOT NULL")
    fun observeDebtTransactions(): Flow<List<TransactionEntity>>

    /** Numbers from completed operations only, so mistyped numbers from failed transfers are never suggested. */
    @Query(
        "SELECT target, channel, MAX(timestamp) AS lastUsed, COUNT(*) AS uses FROM transactions " +
            "WHERE status IN ('SUCCESS', 'RECORDED') GROUP BY target",
    )
    fun observeNumberUsage(): Flow<List<NumberUsage>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastTransaction(): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: Long): TransactionEntity?

    @Insert
    suspend fun insertTransaction(tx: TransactionEntity): Long

    @Update
    suspend fun updateTransaction(tx: TransactionEntity)

    /** Clears a shift's log but keeps completed debt sales, which customer balances depend on. */
    @Query(
        "DELETE FROM transactions WHERE shiftId = :shiftId " +
            "AND NOT (isDebt = 1 AND customerId IS NOT NULL AND status IN ('SUCCESS', 'RECORDED'))",
    )
    suspend fun deleteShiftTransactions(shiftId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE shiftId = :shiftId")
    suspend fun countShiftTransactions(shiftId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE customerId = :customerId")
    suspend fun countCustomerTransactions(customerId: Long): Int

    // Customers

    @Query("SELECT * FROM customers ORDER BY name COLLATE NOCASE")
    fun observeCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id")
    fun observeCustomer(id: Long): Flow<CustomerEntity?>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun customer(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE phone = :phone LIMIT 1")
    suspend fun customerByPhone(phone: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateCustomer(customer: CustomerEntity)

    @Delete
    suspend fun deleteCustomer(customer: CustomerEntity)

    // Payments

    @Query("SELECT * FROM payments")
    fun observePayments(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE customerId = :customerId ORDER BY time DESC")
    fun observeCustomerPayments(customerId: Long): Flow<List<PaymentEntity>>

    @Query("SELECT COUNT(*) FROM payments WHERE customerId = :customerId")
    suspend fun countCustomerPayments(customerId: Long): Int

    @Insert
    suspend fun insertPayment(payment: PaymentEntity): Long

    @Delete
    suspend fun deletePayment(payment: PaymentEntity)

    // Balances

    @Query("SELECT * FROM balances")
    fun observeBalances(): Flow<List<ChannelBalanceEntity>>

    @Query("SELECT * FROM balances")
    suspend fun balances(): List<ChannelBalanceEntity>

    @Query("SELECT * FROM balances WHERE channel = :channel")
    suspend fun balance(channel: Channel): ChannelBalanceEntity?

    @Upsert
    suspend fun upsertBalance(balance: ChannelBalanceEntity)

    // Categories

    @Query("SELECT * FROM categories ORDER BY carrier, value")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories WHERE carrier = :carrier")
    suspend fun countCategories(carrier: Carrier): Int

    @Upsert
    suspend fun upsertCategory(category: CategoryEntity)

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    // Shifts

    @Query("SELECT * FROM shifts ORDER BY id DESC")
    fun observeShifts(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts WHERE endTime IS NULL ORDER BY id DESC LIMIT 1")
    fun observeOpenShift(): Flow<ShiftEntity?>

    @Query("SELECT * FROM shifts WHERE endTime IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openShift(): ShiftEntity?

    @Insert
    suspend fun insertShift(shift: ShiftEntity): Long

    @Update
    suspend fun updateShift(shift: ShiftEntity)

    // Settings

    @Query("SELECT * FROM settings")
    fun observeSettings(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): String?

    @Upsert
    suspend fun putSetting(setting: SettingEntity)

    // Backup

    @Query("SELECT * FROM transactions") suspend fun allTransactions(): List<TransactionEntity>
    @Query("SELECT * FROM customers") suspend fun allCustomers(): List<CustomerEntity>
    @Query("SELECT * FROM payments") suspend fun allPayments(): List<PaymentEntity>
    @Query("SELECT * FROM categories") suspend fun allCategories(): List<CategoryEntity>
    @Query("SELECT * FROM shifts") suspend fun allShifts(): List<ShiftEntity>
    @Query("SELECT * FROM settings") suspend fun allSettings(): List<SettingEntity>

    @Insert suspend fun insertTransactions(rows: List<TransactionEntity>)
    @Insert suspend fun insertCustomers(rows: List<CustomerEntity>)
    @Insert suspend fun insertPayments(rows: List<PaymentEntity>)
    @Upsert suspend fun upsertBalances(rows: List<ChannelBalanceEntity>)
    @Insert suspend fun insertCategories(rows: List<CategoryEntity>)
    @Insert suspend fun insertShifts(rows: List<ShiftEntity>)
    @Upsert suspend fun upsertSettings(rows: List<SettingEntity>)

    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM payments") suspend fun clearPayments()
    @Query("DELETE FROM customers") suspend fun clearCustomers()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM shifts") suspend fun clearShifts()
}
