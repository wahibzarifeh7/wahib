package com.wz.rasid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wz.rasid.core.Accounting
import com.wz.rasid.core.TxStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF00695C)

@Composable
fun RasidTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF4DB6AC), secondary = Color(0xFFFFB74D))
    } else {
        lightColorScheme(primary = Teal, secondary = Color(0xFFEF6C00))
    }
    MaterialTheme(colorScheme = colors) {
        // The whole UI is Arabic, so it is laid out right-to-left regardless of the device language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

fun money(v: Double): String = Accounting.money(v)

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

fun formatTime(t: Long): String = timeFormat.format(Date(t))

/** Parses yyyy-MM-dd; returns null for blank or malformed input. */
fun parseDay(s: String): Long? = s.trim().takeIf { it.isNotEmpty() }?.let {
    runCatching { dayFormat.apply { isLenient = false }.parse(it)?.time }.getOrNull()
}

fun String.toAmount(): Double? =
    com.wz.rasid.core.UssdResponse.toLatinDigits(this).replace(",", "").trim().toDoubleOrNull()

@Composable
fun statusColor(status: TxStatus): Color = when (status) {
    TxStatus.SUCCESS, TxStatus.RECORDED -> Color(0xFF2E7D32)
    TxStatus.FAILED -> MaterialTheme.colorScheme.error
    TxStatus.PENDING, TxStatus.REVIEW -> Color(0xFFEF6C00)
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun InputField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "تأكيد",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** Asks for the accounting password; the caller verifies it. */
@Composable
fun PasswordDialog(title: String, text: String, onDismiss: () -> Unit, onPassword: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                InputField(pw, { pw = it }, "كلمة السر", keyboardType = KeyboardType.NumberPassword, secret = true)
            }
        },
        confirmButton = { TextButton(onClick = { onPassword(pw); onDismiss() }, enabled = pw.isNotEmpty()) { Text("تأكيد") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** A single numeric input dialog, e.g. adding stock or a payment. */
@Composable
fun AmountDialog(
    title: String,
    label: String,
    initial: String = "",
    withNotes: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (Double, String) -> Unit,
) {
    var amount by remember { mutableStateOf(initial) }
    var notes by remember { mutableStateOf("") }
    val parsed = amount.toAmount()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(amount, { amount = it }, label)
                if (withNotes) InputField(notes, { notes = it }, "ملاحظات")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(parsed!!, notes); onDismiss() }, enabled = parsed != null) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
