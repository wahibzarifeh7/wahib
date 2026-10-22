@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wz.rasid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Accounting
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.Category
import com.wz.rasid.core.Channel
import com.wz.rasid.core.KnownNumber
import com.wz.rasid.core.NumberSuggestions
import com.wz.rasid.core.OpType
import com.wz.rasid.core.UssdCodes
import com.wz.rasid.data.CustomerEntity
import com.wz.rasid.data.TransferDraft
import com.wz.rasid.data.isLow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(vm: MainViewModel) {
    val balances by vm.balances.collectAsState()
    val categories by vm.categories.collectAsState()
    val customers by vm.customers.collectAsState()
    val shiftTx by vm.shiftTransactions.collectAsState()
    val knownNumbers by vm.knownNumbers.collectAsState()

    var carrier by rememberSaveable { mutableStateOf(Carrier.MTN) }
    var type by rememberSaveable { mutableStateOf(OpType.PREPAID) }
    var target by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var priceEdited by rememberSaveable { mutableStateOf(false) }
    var isDebt by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var customerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickCustomer by remember { mutableStateOf(false) }
    var askNewCustomer by remember { mutableStateOf<TransferDraft?>(null) }

    val channel = Channel.of(carrier, type) ?: Channel.forCarrier(carrier).first()
    val carrierCategories = categories.filter { it.carrier == carrier }.map { Category(it.value, it.price) }
    val selectedCustomer = customers.firstOrNull { it.id == customerId }
    val amountValue = amount.toAmount()
    val priceValue = price.toAmount()

    fun setAmount(v: String) {
        amount = v
        if (!priceEdited) {
            val a = v.toAmount()
            price = a?.let { Accounting.priceFor(it, carrierCategories) ?: it }?.let(UssdCodes::formatAmount).orEmpty()
        }
    }

    fun setTarget(v: String) {
        target = v
        // Link a known customer automatically when their number is typed.
        if (customerId == null && channel.type != OpType.DSL) {
            val n = UssdCodes.normalizeMobile(v)
            customers.firstOrNull { it.phone == n }?.let { customerId = it.id }
        }
    }

    fun draft(): TransferDraft? {
        val a = amountValue
        val p = priceValue
        if (target.isBlank() || a == null || a <= 0 || p == null || p < 0) {
            vm.toast("يرجى إكمال البيانات أولاً")
            return null
        }
        return TransferDraft(
            channel = channel,
            target = if (channel.type == OpType.DSL) target.trim() else UssdCodes.normalizeMobile(target),
            amount = a,
            sellingPrice = p,
            isDebt = isDebt,
            customerId = selectedCustomer?.id,
            customerName = selectedCustomer?.name.orEmpty(),
            notes = notes.trim(),
        )
    }

    fun clearForm() {
        target = ""
        amount = ""
        price = ""
        priceEdited = false
        isDebt = false
        notes = ""
        customerId = null
    }

    fun submit(withUssd: Boolean) {
        val d = draft() ?: return
        if (d.isDebt && d.customerId == null) {
            askNewCustomer = d
            return
        }
        if (withUssd) vm.prepareTransfer(d) else vm.recordWithoutTransfer(d)
        clearForm()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val totals = Accounting.totals(shiftTx.map { it.toSale() })
        SectionCard("الوردية الحالية") {
            StatRow("عدد العمليات", totals.count.toString())
            StatRow("إجمالي المبيعات", money(totals.sales))
            StatRow("إجمالي الأرباح", money(totals.profit), MaterialTheme.colorScheme.primary)
            StatRow("إجمالي الديون", money(totals.debts))
        }

        balances.filter { it.isLow() }.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                Text("تنبيه: رصيد ${b.channel.label} انخفض إلى الحد الأدنى (${money(b.current)})", color = MaterialTheme.colorScheme.error)
            }
        }

        SectionCard("عملية جديدة") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Carrier.entries.forEach { c ->
                    FilterChip(
                        selected = carrier == c,
                        onClick = {
                            carrier = c
                            if (Channel.of(c, type) == null) type = OpType.PREPAID
                        },
                        label = { Text(c.label) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Channel.forCarrier(carrier).forEach { ch ->
                    FilterChip(selected = channel == ch, onClick = { type = ch.type }, label = { Text(ch.type.label) })
                }
            }
            balances.firstOrNull { it.channel == channel }?.let {
                Text("الرصيد الحالي: ${money(it.current)}", style = MaterialTheme.typography.bodySmall)
            }

            InputField(
                target,
                ::setTarget,
                if (channel.type == OpType.DSL) "الهاتف الأرضي (مثال: 011-2223333)" else "رقم الموبايل",
                keyboardType = KeyboardType.Phone,
            )

            val landline = channel.type == OpType.DSL
            val suggestions = remember(target, knownNumbers, landline) {
                NumberSuggestions.suggest(target, knownNumbers, landline)
            }
            NumberSuggestionList(target, landline, suggestions) { k ->
                // A number belongs to one network, so follow it when picking a mobile.
                if (!landline && k.carrier != null && k.carrier != carrier) {
                    carrier = k.carrier
                    if (Channel.of(k.carrier, type) == null) type = OpType.PREPAID
                }
                setTarget(k.number)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedCustomer != null) {
                    AssistChip(
                        onClick = { pickCustomer = true },
                        label = { Text(selectedCustomer.name) },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                    )
                    IconButton(onClick = { customerId = null }) { Icon(Icons.Default.Close, "إلغاء اختيار الزبون") }
                } else {
                    TextButton(onClick = { pickCustomer = true }) { Text("اختيار الزبون (اختياري)") }
                }
            }

            if (carrierCategories.isNotEmpty()) {
                Text("فئات الرصيد", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    carrierCategories.forEach { cat ->
                        AssistChip(
                            onClick = {
                                priceEdited = false
                                setAmount(UssdCodes.formatAmount(cat.value))
                            },
                            label = { Text("${money(cat.value)} ← ${money(cat.price)}") },
                        )
                    }
                }
            }

            NumberField(amount, ::setAmount, "القيمة (وحدات / مبلغ)")
            NumberField(price, { price = it; priceEdited = true }, "سعر البيع")

            val cost = amountValue?.let { a -> Accounting.cost(a, balances.firstOrNull { it.channel == channel }?.costPer10k ?: 10_000.0) }
            if (cost != null && priceValue != null) {
                Text(
                    "الكلفة: ${money(cost)} — الربح: ${money(Accounting.profit(priceValue, cost))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isDebt = !isDebt }) {
                Checkbox(checked = isDebt, onCheckedChange = { isDebt = it })
                Text("تسجيل دين")
            }
            InputField(notes, { notes = it }, "ملاحظات")

            Button(onClick = { submit(withUssd = true) }, modifier = Modifier.fillMaxWidth()) { Text("تحويل") }
            OutlinedButton(onClick = { submit(withUssd = false) }, modifier = Modifier.fillMaxWidth()) {
                Text("إضافة للسجل بدون تحويل فعلي")
            }
            TextButton(onClick = {
                vm.launch {
                    val last = vm.repo.lastTransaction() ?: return@launch
                    carrier = last.channel.carrier
                    type = last.channel.type
                    target = last.target
                    amount = UssdCodes.formatAmount(last.amount)
                    price = UssdCodes.formatAmount(last.sellingPrice)
                    priceEdited = true
                    customerId = last.customerId
                }
            }) { Text("تعبئة الحقول من آخر عملية") }
        }
    }

    if (pickCustomer) {
        CustomerPicker(customers, onDismiss = { pickCustomer = false }) {
            customerId = it.id
            if (target.isBlank() && channel.type != OpType.DSL) target = it.phone
        }
    }

    askNewCustomer?.let { pending ->
        NewDebtCustomerDialog(
            phone = pending.target,
            onDismiss = { askNewCustomer = null },
            onPick = { pickCustomer = true },
            onSave = { name ->
                vm.launch {
                    vm.repo.saveCustomer(null, name, pending.target)
                        .onSuccess { id ->
                            customerId = id
                            vm.toast("تم حفظ الزبون. أعد الضغط على تنفيذ العملية.")
                        }
                        .onFailure { vm.toast(it.message ?: "تعذر حفظ الزبون") }
                }
            },
        )
    }
}

@Composable
fun CustomerPicker(customers: List<CustomerEntity>, onDismiss: () -> Unit, onPick: (CustomerEntity) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = customers.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختيار زبون") },
        text = {
            Column {
                InputField(query, { query = it }, "بحث بالاسم أو الرقم")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { c ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(c); onDismiss() }.padding(vertical = 10.dp),
                        ) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text(c.phone, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                if (filtered.isEmpty()) Text("لا يوجد", Modifier.padding(8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

@Composable
private fun NewDebtCustomerDialog(phone: String, onDismiss: () -> Unit, onPick: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("زبون مطلوب") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("لا يمكن تسجيل عملية دين بدون تحديد زبون. هل تريد حفظ الرقم $phone كزبون جديد أم اختياره من القائمة؟")
                InputField(name, { name = it }, "اسم الزبون")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name); onDismiss() }, enabled = name.isNotBlank()) { Text("زبون جديد") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(); onDismiss() }) { Text("اختيار زبون") }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        },
    )
}

/** Previously used numbers that complete what has been typed; the untyped rest is highlighted. */
@Composable
private fun NumberSuggestionList(typed: String, landline: Boolean, suggestions: List<KnownNumber>, onPick: (KnownNumber) -> Unit) {
    if (suggestions.isEmpty()) return
    val prefixLength = NumberSuggestions.typedDigits(typed, landline).length
    Card(Modifier.fillMaxWidth()) {
        suggestions.forEachIndexed { i, k ->
            if (i > 0) HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onPick(k) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val digitsOnly = k.number.all(Char::isDigit)
                Text(
                    buildAnnotatedString {
                        if (digitsOnly && prefixLength <= k.number.length) {
                            append(k.number.take(prefixLength))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                                append(k.number.drop(prefixLength))
                            }
                        } else {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(k.number) }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    if (k.name.isNotBlank()) Text(k.name, style = MaterialTheme.typography.bodyMedium)
                    val info = listOfNotNull(
                        k.carrier?.label,
                        if (k.uses > 0) "${k.uses} عملية" else "زبون محفوظ",
                    ).joinToString(" · ")
                    Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
