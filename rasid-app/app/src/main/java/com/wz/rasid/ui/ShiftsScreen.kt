package com.wz.rasid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Accounting
import com.wz.rasid.data.ShiftEntity

@Composable
fun ShiftsScreen(vm: MainViewModel, onBrowse: (Long) -> Unit) {
    val shifts by vm.shifts.collectAsState()
    val all by remember { vm.repo.transactions(null, 0L, Long.MAX_VALUE) }.collectAsState(emptyList())
    var confirmNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ShiftEntity?>(null) }
    val byShift = all.groupBy { it.shiftId }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Button(onClick = { confirmNew = true }, modifier = Modifier.fillMaxWidth()) { Text("بدء وردية جديدة") }
        Text("سجل الورديات", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        shifts.forEach { shift ->
            val txs = byShift[shift.id].orEmpty()
            val totals = Accounting.totals(txs.map { it.toSale() })
            val open = shift.endTime == null
            SectionCard(if (open) "الوردية ${shift.id} (الحالية)" else "الوردية ${shift.id}") {
                StatRow("تاريخ البداية", formatTime(shift.startTime))
                shift.endTime?.let { StatRow("تاريخ النهاية", formatTime(it)) }
                StatRow("عدد العمليات", totals.count.toString())
                StatRow("إجمالي المبيعات", money(totals.sales))
                StatRow("إجمالي الأرباح", money(totals.profit), MaterialTheme.colorScheme.primary)
                StatRow("إجمالي الديون", money(totals.debts))
                Accounting.profitByChannel(txs.map { it.toSale() }).forEach { (ch, p) ->
                    StatRow("ربح ${ch.label}", money(p))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onBrowse(shift.id) }) { Text("تصفح الوردية") }
                    OutlinedButton(onClick = { deleting = shift }, enabled = txs.isNotEmpty()) { Text("حذف سجل الوردية") }
                }
            }
        }
    }

    if (confirmNew) {
        ConfirmDialog(
            title = "بدء وردية جديدة",
            text = "هل أنت متأكد من إنهاء الوردية الحالية وبدء وردية جديدة؟ سيتم تصفير الإحصائيات الظاهرة.",
            onDismiss = { confirmNew = false },
        ) {
            vm.launch {
                val id = vm.repo.startNewShift()
                vm.toast("بدأت الوردية $id")
            }
        }
    }

    deleting?.let { shift ->
        val warning = if (shift.endTime == null) "تنبيه: هذه هي الوردية الحالية.\n" else ""
        PasswordDialog(
            title = "حذف سجل الوردية",
            text = "${warning}سيتم حذف سجل عمليات الوردية رقم ${shift.id}. سيتم الإبقاء على عمليات الدين. هذا الإجراء لا يمكن التراجع عنه.",
            onDismiss = { deleting = null },
        ) { pw ->
            vm.withPassword(pw) {
                val (deleted, kept) = vm.repo.deleteShiftLog(shift.id)
                vm.toast("تم حذف $deleted عملية" + if (kept > 0) " — تم الإبقاء على $kept عملية دين" else "")
            }
        }
    }
}
