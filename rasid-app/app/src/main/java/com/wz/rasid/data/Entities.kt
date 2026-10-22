package com.wz.rasid.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.Channel
import com.wz.rasid.core.Sale
import com.wz.rasid.core.TxStatus

@Entity(
    tableName = "transactions",
    indices = [Index("shiftId"), Index("customerId"), Index("timestamp")],
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: Channel,
    /** Mobile number, or landline for DSL bills. */
    val target: String,
    val amount: Double,
    val sellingPrice: Double,
    val costPrice: Double,
    val profit: Double,
    val isDebt: Boolean,
    val status: TxStatus,
    val timestamp: Long,
    val shiftId: Long,
    val customerId: Long?,
    val customerName: String,
    val notes: String,
    /** USSD string with secret codes masked. */
    val ussdCode: String?,
    val ussdResponse: String?,
) {
    fun toSale() = Sale(channel, amount, sellingPrice, profit, isDebt, status)
}

@Entity(
    tableName = "customers",
    indices = [Index(value = ["name"], unique = true), Index(value = ["phone"], unique = true)],
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val createdAt: Long,
)

@Entity(
    tableName = "payments",
    indices = [Index("customerId")],
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val amount: Double,
    val time: Long,
    val notes: String,
)

@Entity(tableName = "balances")
data class ChannelBalanceEntity(
    @PrimaryKey val channel: Channel,
    val current: Double,
    val minimum: Double,
    val costPer10k: Double,
    val updatedAt: Long,
)

/** One row per number used in completed operations; [channel] is the one used most recently. */
data class NumberUsage(
    val target: String,
    val channel: Channel,
    val lastUsed: Long,
    val uses: Int,
)

fun ChannelBalanceEntity.isLow(): Boolean = minimum > 0 && current <= minimum

@Entity(tableName = "categories", indices = [Index("carrier")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carrier: Carrier,
    val value: Double,
    val price: Double,
)

@Entity(tableName = "shifts")
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long?,
    /** JSON object of channel name to balance at shift start. */
    val openingBalances: String,
    val closingBalances: String?,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
