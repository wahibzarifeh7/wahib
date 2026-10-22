package com.wz.rasid.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wz.rasid.core.Governorate
import com.wz.rasid.core.MtnCodes
import com.wz.rasid.core.SyriatelCodes
import com.wz.rasid.data.SimLayout
import com.wz.rasid.ussd.UssdCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, onRequestPermissions: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        ReadinessCard(vm, onRequestPermissions)
        // Keyed on the stored values so the fields refresh after a restore or first load.
        androidx.compose.runtime.key(settings.mtn) { MtnCard(settings.mtn) { codes -> vm.launch { vm.repo.saveMtn(codes); vm.toast("تم حفظ إعدادات MTN") } } }
        androidx.compose.runtime.key(settings.syriatel) {
            SyriatelCard(settings.syriatel) { codes -> vm.launch { vm.repo.saveSyriatel(codes); vm.toast("تم حفظ إعدادات سيرياتيل") } }
        }
        SectionCard("ترتيب الشرائح") {
            Text(
                "يتعرف التطبيق على شريحة كل شبكة تلقائياً. إذا لم ينجح ذلك على جهازك حدد مكان تركيب الشرائح.",
                style = MaterialTheme.typography.bodySmall,
            )
            SimLayout.entries.forEach { layout ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.launch { vm.repo.setSimLayout(layout) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.simLayout == layout, onClick = { vm.launch { vm.repo.setSimLayout(layout) } })
                    Text(layout.label)
                }
            }
        }
        PasswordCard(vm)
        BackupCard(vm)
        Text(
            "تُحفظ الرموز السرية على هذا الجهاز فقط ولا تُضمَّن في ملفات النسخ الاحتياطي.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("فتح إعدادات إمكانية الوصول") }
    }
}

@Composable
private fun ReadinessCard(vm: MainViewModel, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(vm.missingPermissions()) }
    var capture by remember { mutableStateOf(UssdCaptureService.isEnabled(context)) }
    // Re-checked on return from the permission prompt or the system accessibility settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = vm.missingPermissions()
        capture = UssdCaptureService.isEnabled(context)
    }
    SectionCard("جاهزية USSD") {
        StatRow("إذن الاتصال وحالة الهاتف", if (missing.isEmpty()) "ممنوح" else "غير ممنوح")
        if (missing.isNotEmpty()) OutlinedButton(onClick = onRequestPermissions) { Text("منح الإذن") }
        StatRow("خدمة التقاط الردود (للتنفيذ عبر الاتصال)", if (capture) "مفعلة" else "غير مفعلة")
        Text(
            "يُستخدم التنفيذ المباشر عبر واجهة أندرويد افتراضياً. خدمة إمكانية الوصول اختيارية وتلزم فقط عند اختيار \"تنفيذ عبر الاتصال\".",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MtnCard(initial: MtnCodes, onSave: (MtnCodes) -> Unit) {
    var pin by remember { mutableStateOf(initial.pin) }
    var cashPin by remember { mutableStateOf(initial.cashPin) }
    var dslCode by remember { mutableStateOf(initial.dslCode) }
    var area by remember { mutableStateOf(initial.dslAreaCode) }
    var areaMenu by remember { mutableStateOf(false) }
    SectionCard("إعدادات MTN") {
        Text("أدخل الرموز كما هي معتمدة لدى مركزك.", style = MaterialTheme.typography.bodySmall)
        InputField(pin, { pin = it }, "الرمز السري", keyboardType = KeyboardType.NumberPassword, secret = true)
        InputField(cashPin, { cashPin = it }, "رمز الكاش pin", keyboardType = KeyboardType.NumberPassword, secret = true)
        InputField(dslCode, { dslCode = it }, "كود DSL", keyboardType = KeyboardType.Number)
        Box {
            OutlinedButton(onClick = { areaMenu = true }, modifier = Modifier.fillMaxWidth()) {
                val label = Governorate.entries.firstOrNull { it.areaCode == area }?.let { "${it.label} — ${it.areaCode}" }
                Text(label ?: if (area.isBlank()) "اختر المحافظة — رمز المنطقة" else area)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = areaMenu, onDismissRequest = { areaMenu = false }) {
                Governorate.entries.forEach { g ->
                    DropdownMenuItem(text = { Text("${g.label} (${g.areaCode})") }, onClick = { area = g.areaCode; areaMenu = false })
                }
            }
        }
        Button(onClick = { onSave(MtnCodes(pin, cashPin, dslCode, area)) }, modifier = Modifier.fillMaxWidth()) { Text("حفظ إعدادات MTN") }
    }
}

@Composable
private fun SyriatelCard(initial: SyriatelCodes, onSave: (SyriatelCodes) -> Unit) {
    var ssc by remember { mutableStateOf(initial.distributorCode) }
    var spc by remember { mutableStateOf(initial.secretCode) }
    SectionCard("إعدادات سيرياتيل") {
        Text("تأكد من صحة الرموز قبل تنفيذ العمليات.", style = MaterialTheme.typography.bodySmall)
        InputField(ssc, { ssc = it }, "رمز الموزع (SSC)", keyboardType = KeyboardType.Number)
        InputField(spc, { spc = it }, "الرمز السري (SPC)", keyboardType = KeyboardType.NumberPassword, secret = true)
        Button(onClick = { onSave(SyriatelCodes(ssc, spc)) }, modifier = Modifier.fillMaxWidth()) { Text("حفظ الرموز") }
    }
}

@Composable
private fun PasswordCard(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    SectionCard("كلمة السر") {
        Text(
            "تُطلب كلمة السر لتغيير حالة العمليات وحذف سجل الورديات واستعادة النسخ الاحتياطية. كلمة السر الافتراضية: 123456",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { open = true }) { Text("تغيير كلمة السر") }
    }
    if (open) {
        var old by remember { mutableStateOf("") }
        var new1 by remember { mutableStateOf("") }
        var new2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("تغيير كلمة السر") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputField(old, { old = it }, "كلمة السر القديمة", keyboardType = KeyboardType.NumberPassword, secret = true)
                    InputField(new1, { new1 = it }, "كلمة السر الجديدة", keyboardType = KeyboardType.NumberPassword, secret = true)
                    InputField(new2, { new2 = it }, "تأكيد كلمة السر الجديدة", keyboardType = KeyboardType.NumberPassword, secret = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        new1.isBlank() -> vm.toast("كلمة السر الجديدة لا يمكن أن تكون فارغة")
                        new1 != new2 -> vm.toast("كلمة السر الجديدة غير متطابقة")
                        else -> vm.launch {
                            if (vm.repo.changePassword(old, new1)) {
                                vm.toast("تم تغيير كلمة السر")
                                open = false
                            } else {
                                vm.toast("كلمة السر القديمة غير صحيحة")
                            }
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun BackupCard(vm: MainViewModel) {
    val context = LocalContext.current
    var pendingRestore by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.launch {
            val ok = runCatching {
                val json = vm.repo.exportBackup()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("no stream")
                }
            }.isSuccess
            vm.toast(if (ok) "تم حفظ النسخة الاحتياطية" else "تعذّر الكتابة إلى الموقع المحدد")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }
            }.getOrNull()
            if (text == null) vm.toast("تعذّر قراءة الملف") else pendingRestore = text
        }
    }

    SectionCard("النسخ الاحتياطي") {
        Text(
            "يصدّر الإعدادات العامة، فئات الأسعار، الأرصدة، الزبائن، الدفعات، العمليات والورديات.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                exportLauncher.launch("rasid-backup-$stamp.json")
            }) { Text("تصدير") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("استيراد") }
        }
    }

    pendingRestore?.let { json ->
        PasswordDialog(
            title = "استعادة نسخة احتياطية",
            text = "سيتم استبدال جميع البيانات الحالية ببيانات الملف. الرموز السرية المحفوظة على الجهاز تبقى كما هي.",
            onDismiss = { pendingRestore = null },
        ) { pw ->
            vm.withPassword(pw) {
                vm.repo.importBackup(json)
                    .onSuccess { vm.toast("تم الاستيراد بنجاح") }
                    .onFailure { vm.toast(it.message ?: "ملف غير صالح أو تالف") }
            }
        }
    }
}
