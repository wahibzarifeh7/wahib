@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wz.rasid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Carrier
import com.wz.rasid.core.UssdCodes
import com.wz.rasid.data.CategoryEntity

@Composable
fun CategoriesScreen(vm: MainViewModel) {
    val categories by vm.categories.collectAsState()
    var carrier by rememberSaveable { mutableStateOf(Carrier.MTN) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    val shown = categories.filter { it.carrier == carrier }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Carrier.entries.forEach { c ->
                    FilterChip(selected = carrier == c, onClick = { carrier = c }, label = { Text(c.label) })
                }
            }
            HorizontalDivider()
            LazyColumn {
                items(shown, key = { it.id }) { cat ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("القيمة ${money(cat.value)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("السعر ${money(cat.price)}", Modifier.weight(1f))
                        IconButton(onClick = { editing = cat }) { Icon(Icons.Default.Edit, "تعديل") }
                        IconButton(onClick = { deleting = cat }) { Icon(Icons.Default.Delete, "حذف") }
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Icon(Icons.Default.Add, "إضافة فئة")
        }
    }

    if (adding || editing != null) {
        val existing = editing
        CategoryDialog(existing, onDismiss = { adding = false; editing = null }) { value, price ->
            vm.launch {
                vm.repo.saveCategory(existing?.id, existing?.carrier ?: carrier, value, price)
                vm.toast("تم الحفظ")
            }
        }
    }
    deleting?.let { cat ->
        ConfirmDialog(
            title = "تأكيد الحذف",
            text = "هل أنت متأكد من حذف هذه الفئة؟ لن يمكنك التراجع عن هذا الإجراء.",
            confirmLabel = "نعم، حذف",
            onDismiss = { deleting = null },
        ) { vm.launch { vm.repo.deleteCategory(cat) } }
    }
}

@Composable
private fun CategoryDialog(existing: CategoryEntity?, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var value by remember { mutableStateOf(existing?.value?.let(UssdCodes::formatAmount).orEmpty()) }
    var price by remember { mutableStateOf(existing?.price?.let(UssdCodes::formatAmount).orEmpty()) }
    val v = value.toAmount()
    val p = price.toAmount()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة فئة" else "تعديل فئة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(value, { value = it }, "قيمة الفئة")
                NumberField(price, { price = it }, "السعر")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(v!!, p!!); onDismiss() }, enabled = v != null && v > 0 && p != null && p >= 0) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
