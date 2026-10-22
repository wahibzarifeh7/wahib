package com.wz.rasid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wz.rasid.core.TxStatus

@Composable
fun UssdDialogHost(vm: MainViewModel) {
    val state by vm.dialog.collectAsState()
    when (val d = state) {
        is UssdDialog.Confirm -> ConfirmTransfer(vm, d)
        is UssdDialog.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(d.title) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("جاري تنفيذ العملية. يرجى إبقاء التطبيق مفتوحاً وانتظار رد الشبكة.")
                }
            },
            confirmButton = {},
        )
        is UssdDialog.Result -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text(d.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.status?.let {
                        Text("الحالة: ${it.label}", color = statusColor(it), fontWeight = FontWeight.Bold)
                        if (it == TxStatus.REVIEW || it == TxStatus.PENDING) {
                            Text(
                                "تأكد من رسائل الشبكة ثم عدّل حالة العملية من سجل التحويل إذا لزم.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text("رد الشبكة:", fontWeight = FontWeight.SemiBold)
                    Text(d.message, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissDialog) { Text("موافق") } },
            dismissButton = {
                val channel = d.balanceChannel
                val value = d.extractedBalance
                if (channel != null && value != null) {
                    TextButton(onClick = {
                        vm.launch {
                            vm.repo.setCurrentBalance(channel, value)
                            vm.toast("تم حفظ الأرصدة بنجاح")
                        }
                        vm.dismissDialog()
                    }) { Text("اعتماد ${money(value)} كرصيد حالي") }
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun ConfirmTransfer(vm: MainViewModel, d: UssdDialog.Confirm) {
    val draft = d.draft
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text("تحقق قبل التنفيذ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatRow("النوع", draft.channel.label)
                StatRow("الرقم", draft.target)
                StatRow("القيمة", money(draft.amount))
                StatRow("سعر البيع", money(draft.sellingPrice))
                StatRow("دين/كاش", if (draft.isDebt) "دين" else "نقدي")
                if (draft.customerName.isNotBlank()) StatRow("الزبون", draft.customerName)
                Text("الكود: ${d.masked}", style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            }
        },
        confirmButton = { TextButton(onClick = { vm.executeTransfer(d, viaDialer = false) }) { Text("تنفيذ") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.executeTransfer(d, viaDialer = true) }) { Text("تنفيذ عبر الاتصال") }
                TextButton(onClick = vm::dismissDialog) { Text("إلغاء") }
            }
        },
    )
}
