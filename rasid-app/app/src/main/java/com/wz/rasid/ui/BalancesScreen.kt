package com.wz.rasid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.UssdCodes
import com.wz.rasid.data.ChannelBalanceEntity
import com.wz.rasid.data.isLow

private sealed interface BalanceAction {
    val balance: ChannelBalanceEntity

    data class Add(override val balance: ChannelBalanceEntity) : BalanceAction
    data class Adjust(override val balance: ChannelBalanceEntity) : BalanceAction
    data class Limits(override val balance: ChannelBalanceEntity) : BalanceAction
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BalancesScreen(vm: MainViewModel) {
    val balances by vm.balances.collectAsState()
    var action by remember { mutableStateOf<BalanceAction?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Carrier.entries.forEach { carrier ->
            Text(
                if (carrier == Carrier.MTN) "أرصدة MTN" else "أرصدة سيرياتيل",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            balances.filter { it.channel.carrier == carrier }.sortedBy { it.channel.ordinal }.forEach { b ->
                SectionCard(b.channel.label) {
                    StatRow("الرصيد الحالي", money(b.current), if (b.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    StatRow("الحد الأدنى", money(b.minimum))
                    StatRow("تكلفة 10,000 وحدة", money(b.costPer10k))
                    if (b.isLow()) Text("انخفض إلى الحد الأدنى", color = MaterialTheme.colorScheme.error)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { action = BalanceAction.Add(b) }) { Text("إدخال الرصيد") }
                        OutlinedButton(onClick = { action = BalanceAction.Adjust(b) }) { Text("تعديل") }
                        OutlinedButton(onClick = { action = BalanceAction.Limits(b) }) { Text("الحد والتكلفة") }
                        if (UssdCodes.hasBalanceInquiry(b.channel)) {
                            OutlinedButton(onClick = { vm.balanceInquiry(b.channel) }) { Text("استعلام") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { vm.lastTransfers(carrier) }, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("استعلام آخر تحويلات ${carrier.label}")
            }
        }
    }

    when (val a = action) {
        is BalanceAction.Add -> AmountDialog(
            title = "إدخال الرصيد — ${a.balance.channel.label}",
            label = "المبلغ المضاف",
            onDismiss = { action = null },
        ) { amount, _ ->
            vm.launch {
                vm.repo.addStock(a.balance.channel, amount)
                vm.toast("تم حفظ الأرصدة بنجاح")
            }
        }
        is BalanceAction.Adjust -> AmountDialog(
            title = "تعديل الرصيد الحالي — ${a.balance.channel.label}",
            label = "الرصيد الحالي",
            initial = UssdCodes.formatAmount(a.balance.current),
            onDismiss = { action = null },
        ) { value, _ ->
            vm.launch {
                vm.repo.setCurrentBalance(a.balance.channel, value)
                vm.toast("تم حفظ الأرصدة بنجاح")
            }
        }
        is BalanceAction.Limits -> LimitsDialog(a.balance, onDismiss = { action = null }) { min, cost ->
            vm.launch {
                vm.repo.setLimits(a.balance.channel, min, cost)
                vm.toast("تم الحفظ")
            }
        }
        null -> Unit
    }
}

@Composable
private fun LimitsDialog(balance: ChannelBalanceEntity, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var min by remember { mutableStateOf(UssdCodes.formatAmount(balance.minimum)) }
    var cost by remember { mutableStateOf(UssdCodes.formatAmount(balance.costPer10k)) }
    val minV = min.toAmount()
    val costV = cost.toAmount()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حفظ الحد والتكلفة — ${balance.channel.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(min, { min = it }, "الحد الأدنى (للتنبيه)")
                NumberField(cost, { cost = it }, "تكلفة 10,000 وحدة")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(minV!!, costV!!); onDismiss() },
                enabled = minV != null && costV != null && minV >= 0 && costV >= 0,
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
