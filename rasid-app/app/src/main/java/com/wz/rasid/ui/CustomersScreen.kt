package com.wz.rasid.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Accounting
import com.wz.rasid.data.CustomerEntity

@Composable
fun CustomersScreen(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val customers by vm.customers.collectAsState()
    val balances by vm.customerBalances.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<CustomerEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                prefill = c.getString(0).orEmpty() to c.getString(1).orEmpty()
                adding = true
            }
        }
    }

    val filtered = customers.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }
    val totalDebt = balances.values.filter { it > 0 }.sum()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            InputField(query, { query = it }, "ابحث عن زبون", Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("إجمالي الديون: ${money(totalDebt)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = {
                    pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }) { Text("استيراد من جهات الاتصال") }
            }
            HorizontalDivider()
            if (filtered.isEmpty()) Text("لا يوجد زبائن", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { c ->
                    val owed = balances[c.id] ?: 0.0
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            Text(c.phone, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            when {
                                owed > 0 -> "مدين بـ: ${money(owed)}"
                                owed < 0 -> "دائن: ${money(-owed)}"
                                else -> "لا يوجد ديون"
                            },
                            color = if (owed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { prefill = null; adding = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        ) { Icon(Icons.Default.Add, "إضافة زبون") }
    }

    if (adding || editing != null) {
        val existing = editing
        CustomerDialog(
            title = if (existing == null) "إضافة زبون" else "تعديل الزبون",
            initialName = existing?.name ?: prefill?.first.orEmpty(),
            initialPhone = existing?.phone ?: prefill?.second.orEmpty(),
            onDismiss = { adding = false; editing = null },
        ) { name, phone ->
            vm.launch {
                vm.repo.saveCustomer(existing?.id, name, phone)
                    .onSuccess { vm.toast(if (existing == null) "تم الحفظ" else "تم تعديل بيانات الزبون بنجاح") }
                    .onFailure { vm.toast(it.message ?: "تعذر حفظ بيانات الزبون") }
            }
        }
    }
}

@Composable
fun CustomerDialog(
    title: String,
    initialName: String,
    initialPhone: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(name, { name = it }, "اسم الزبون")
                InputField(phone, { phone = it }, "رقم الهاتف", keyboardType = KeyboardType.Phone)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, phone); onDismiss() }, enabled = name.isNotBlank() && phone.isNotBlank()) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomerDetailScreen(vm: MainViewModel, customerId: Long, onDeleted: () -> Unit) {
    val customer by remember(customerId) { vm.repo.customer(customerId) }.collectAsState(null)
    val entries by remember(customerId) { vm.repo.ledger(customerId) }.collectAsState(emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val c = customer ?: return
    val lines = Accounting.statement(entries)
    val balance = lines.lastOrNull()?.runningBalance ?: 0.0

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SectionCard(c.name) {
            Text(c.phone)
            StatRow(
                "الرصيد الحالي",
                when {
                    balance > 0 -> "${money(balance)} ل.س (ذمة مستحقة)"
                    balance < 0 -> "${money(-balance)} ل.س (رصيد دائن)"
                    else -> "0 ل.س (الحساب مصفّر)"
                },
                if (balance > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { dialog = "payment" }) { Text("إضافة دفعة") }
                OutlinedButton(onClick = { dialog = "settle" }, enabled = balance > 0) { Text("رصد الحساب") }
                OutlinedButton(onClick = {
                    val text = Accounting.statementText(c.name, lines)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "مشاركة كشف الحساب عبر..."))
                }) { Text("مشاركة كشف الحساب") }
                OutlinedButton(onClick = { dialog = "edit" }) { Text("تعديل") }
                OutlinedButton(onClick = { dialog = "delete" }) { Text("حذف") }
            }
        }
        Text("كشف الحساب", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
        HorizontalDivider()
        if (lines.isEmpty()) Text("لا توجد حركات لهذا الزبون", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines.reversed()) { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.entry.description)
                        Text(formatTime(line.entry.time), style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            (if (line.entry.amount >= 0) "+" else "−") + money(kotlin.math.abs(line.entry.amount)),
                            color = if (line.entry.amount >= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("الرصيد: ${money(line.runningBalance)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }

    when (dialog) {
        "payment" -> AmountDialog("إضافة دفعة", "قيمة الدفعة", withNotes = true, onDismiss = { dialog = null }) { amount, notes ->
            if (amount <= 0) {
                vm.toast("الرجاء إدخال دفعة صحيحة")
            } else {
                vm.launch {
                    vm.repo.addPayment(customerId, amount, notes)
                    vm.toast("تمت إضافة الدفعة بنجاح")
                }
            }
        }
        "settle" -> ConfirmDialog(
            title = "رصد الحساب",
            text = "هل أنت متأكد من رصد الحساب؟ سيتم إضافة دفعة تلقائية بقيمة ${money(balance)} لتصفير الحساب، مع الاحتفاظ بكامل السجل.",
            onDismiss = { dialog = null },
        ) {
            vm.launch {
                vm.repo.settle(customerId)
                vm.toast("تمت إضافة دفعة رصد الحساب بنجاح")
            }
        }
        "edit" -> CustomerDialog("تعديل الزبون", c.name, c.phone, onDismiss = { dialog = null }) { name, phone ->
            vm.launch {
                vm.repo.saveCustomer(c.id, name, phone)
                    .onSuccess { vm.toast("تم تعديل بيانات الزبون بنجاح") }
                    .onFailure { vm.toast(it.message ?: "تعذر حفظ بيانات الزبون") }
            }
        }
        "delete" -> ConfirmDialog(
            title = "حذف الزبون",
            text = "هل أنت متأكد من حذف هذا الزبون؟ لا يمكن حذف الزبون إذا كان لديه عمليات أو دفعات مرتبطة.",
            confirmLabel = "نعم، حذف",
            onDismiss = { dialog = null },
        ) {
            vm.launch {
                if (vm.repo.deleteCustomer(customerId)) {
                    vm.toast("تم حذف الزبون بنجاح")
                    onDeleted()
                } else {
                    vm.toast("لا يمكن حذف هذا الزبون لأنه يملك عمليات أو دفعات مرتبطة.")
                }
            }
        }
    }
}
