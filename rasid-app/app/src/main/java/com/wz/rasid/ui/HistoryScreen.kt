@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wz.rasid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Accounting
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.TxStatus
import com.wz.rasid.data.TransactionEntity

private const val DAY_MS = 24 * 60 * 60 * 1000L

@Composable
fun HistoryScreen(vm: MainViewModel, initialShiftId: Long? = null) {
    var shiftText by rememberSaveable { mutableStateOf(initialShiftId?.toString().orEmpty()) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var carrierFilter by rememberSaveable { mutableStateOf<Carrier?>(null) }
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }

    val shiftId = shiftText.trim().toLongOrNull()
    val from = parseDay(fromText) ?: 0L
    val to = parseDay(toText)?.plus(DAY_MS - 1) ?: Long.MAX_VALUE
    val rows by remember(shiftId, from, to) { vm.repo.transactions(shiftId, from, to) }.collectAsState(emptyList())
    val shown = rows.filter { carrierFilter == null || it.channel.carrier == carrierFilter }
    val totals = Accounting.totals(shown.map { it.toSale() })

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            InputField(shiftText, { shiftText = it }, "رقم الوردية (فارغ=الكل)", Modifier.weight(1f), KeyboardType.Number)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputField(fromText, { fromText = it }, "من (yyyy-MM-dd)", Modifier.weight(1f))
            InputField(toText, { toText = it }, "إلى (yyyy-MM-dd)", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = carrierFilter == null, onClick = { carrierFilter = null }, label = { Text("الكل") })
            Carrier.entries.forEach { c ->
                FilterChip(selected = carrierFilter == c, onClick = { carrierFilter = c }, label = { Text(c.label) })
            }
        }
        Text(
            "العدد: ${totals.count} | الإجمالي: ${money(totals.sales)} | الربح: ${money(totals.profit)}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        HorizontalDivider()
        if (shown.isEmpty()) Text("لا توجد عمليات", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { tx ->
                TransactionRow(tx) { selected = tx }
                HorizontalDivider()
            }
        }
    }

    selected?.let { tx ->
        TransactionDetails(vm, tx, onDismiss = { selected = null })
    }
}

@Composable
fun TransactionRow(tx: TransactionEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${tx.channel.label} — ${tx.target}", fontWeight = FontWeight.SemiBold)
            val who = if (tx.customerName.isNotBlank()) " — ${tx.customerName}" else ""
            Text("${formatTime(tx.timestamp)} — الوردية ${tx.shiftId}$who", style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(tx.amount), fontWeight = FontWeight.SemiBold)
            Text(
                tx.status.label + if (tx.isDebt) " · دين" else "",
                color = statusColor(tx.status),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TransactionDetails(vm: MainViewModel, tx: TransactionEntity, onDismiss: () -> Unit) {
    var newStatus by remember { mutableStateOf<TxStatus?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تفاصيل المعاملة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatRow("النوع", tx.channel.label)
                StatRow("الرقم", tx.target)
                StatRow("القيمة", money(tx.amount))
                StatRow("سعر البيع", money(tx.sellingPrice))
                StatRow("الكلفة", money(tx.costPrice))
                StatRow("الربح", money(tx.profit))
                StatRow("الحالة", tx.status.label, statusColor(tx.status))
                StatRow("دين/كاش", if (tx.isDebt) "دين" else "نقدي")
                if (tx.customerName.isNotBlank()) StatRow("الزبون", tx.customerName)
                StatRow("التاريخ والوقت", formatTime(tx.timestamp))
                if (tx.notes.isNotBlank()) Text("ملاحظات: ${tx.notes}")
                tx.ussdCode?.let { Text("الكود: $it", style = MaterialTheme.typography.bodySmall) }
                tx.ussdResponse?.let {
                    Text("رد الشبكة:", fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text("تعديل الحالة:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TxStatus.SUCCESS, TxStatus.FAILED, TxStatus.REVIEW).filter { it != tx.status }.forEach { s ->
                        FilterChip(selected = false, onClick = { newStatus = s }, label = { Text(s.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )

    newStatus?.let { s ->
        PasswordDialog(
            title = "تأكيد العملية",
            text = "تغيير حالة العملية إلى \"${s.label}\" يؤثر على الحسابات والأرصدة. يرجى إدخال كلمة السر للمتابعة.",
            onDismiss = { newStatus = null },
        ) { pw ->
            vm.withPassword(pw) {
                vm.repo.setStatus(tx.id, s)
                vm.toast("تم الحفظ")
                onDismiss()
            }
        }
    }
}
