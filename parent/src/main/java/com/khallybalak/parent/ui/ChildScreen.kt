package com.khallybalak.parent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.khallybalak.parent.ParentRepo
import com.khallybalak.shared.AppRule
import com.khallybalak.shared.InstalledApp
import com.khallybalak.shared.Policy
import com.khallybalak.shared.TimeWindow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildScreen(id: String, onBack: () -> Unit, onLive: (String) -> Unit) {
    val child by remember(id) { ParentRepo.childFlow(id) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<InstalledApp?>(null) }
    var editBedtime by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val c = child
    if (c == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val p = c.policy

    fun save(newPolicy: Policy, msg: String = "تم الحفظ ✓") = scope.launch {
        runCatching { ParentRepo.updatePolicy(id, newPolicy) }
            .onSuccess { snackbar.showSnackbar(msg) }
            .onFailure { snackbar.showSnackbar("فشل الحفظ: ${it.localizedMessage}") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(c.name, fontWeight = FontWeight.Bold)
                        Text(lastSeenText(c.lastSeen), style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                actions = { IconButton({ confirmRemove = true }) { Icon(Icons.Default.DeleteOutline, "فك الربط") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (c.alert.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(c.alert, Modifier.weight(1f).padding(horizontal = 8.dp))
                        IconButton({ scope.launch { runCatching { ParentRepo.clearAlert(id) } } }) {
                            Icon(Icons.Default.Close, "إخفاء")
                        }
                    }
                }
            }

            // أزرار سريعة
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton({ onLive(c.name) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("الشاشة لايف")
                    }
                    FilledTonalButton(
                        {
                            scope.launch {
                                runCatching { ParentRepo.lockNow(id) }
                                snackbar.showSnackbar("تم إرسال أمر قفل الشاشة")
                            }
                        },
                        Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Lock, null); Spacer(Modifier.width(6.dp)); Text("قفل الشاشة الآن")
                    }
                }
            }

            // الإعدادات العامة
            item {
                ElevatedCard {
                    SettingSwitch("قفل الجهاز بالكامل", "لا يفتح إلا الاتصال حتى تلغي القفل", p.lockDevice) {
                        save(p.copy(lockDevice = it))
                    }
                    HorizontalDivider()
                    SettingSwitch("منع تثبيت وحذف التطبيقات", "يقفل متجر Play وشاشات التثبيت والحذف", p.blockInstallUninstall) {
                        save(p.copy(blockInstallUninstall = it))
                    }
                    HorizontalDivider()
                    SettingSwitch("قفل إعدادات الهاتف", "يمنع الدخول إلى الإعدادات", p.blockSettings) {
                        save(p.copy(blockSettings = it))
                    }
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("وقت النوم") },
                        supportingContent = {
                            Text(
                                if (p.bedtimeStart.isEmpty()) "غير مفعّل"
                                else "الجهاز مقفول من ${p.bedtimeStart} إلى ${p.bedtimeEnd}"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Bedtime, null) },
                        modifier = Modifier.clickable { editBedtime = true },
                    )
                }
            }

            item {
                Column {
                    Text(
                        "التطبيقات (${c.apps.size}) — اضغط على أي تطبيق لتحديد قواعده",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        query, { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text("ابحث عن تطبيق") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            if (c.apps.isEmpty()) item {
                Text("سيظهر هنا تطبيقات جهاز الابن بعد دقائق من تفعيل الحماية.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val apps = c.apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            items(apps, key = { it.pkg }) { app ->
                AppRow(app, p.rules.find { it.pkg == app.pkg }) { editing = app }
            }
        }
    }

    editing?.let { app ->
        AppRuleDialog(
            app = app,
            initial = p.rules.find { it.pkg == app.pkg } ?: AppRule(pkg = app.pkg),
            onDismiss = { editing = null },
            onSave = { rule ->
                val others = p.rules.filter { it.pkg != rule.pkg }
                val active = rule.blocked || rule.dailyLimitMin > 0 || rule.windowStart.isNotEmpty()
                save(p.copy(rules = if (active) others + rule else others))
                editing = null
            },
        )
    }

    if (editBedtime) BedtimeDialog(p, onDismiss = { editBedtime = false }) { s, e ->
        save(p.copy(bedtimeStart = s, bedtimeEnd = e)); editBedtime = false
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("فك ربط ${c.name}؟") },
        text = { Text("سيتم إيقاف كل القيود على هذا الجهاز وحذف بياناته.") },
        confirmButton = {
            TextButton({ scope.launch { runCatching { ParentRepo.removeChild(id) }; onBack() } }) {
                Text("فك الربط", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("إلغاء") } },
    )
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun AppRow(app: InstalledApp, rule: AppRule?, onClick: () -> Unit) {
    val status = when {
        rule == null -> null
        rule.blocked -> "محظور"
        else -> listOfNotNull(
            rule.dailyLimitMin.takeIf { it > 0 }?.let { "حد ${formatMinutes(it)}/يوم" },
            rule.windowStart.takeIf { it.isNotEmpty() }?.let { "من $it إلى ${rule.windowEnd}" },
        ).joinToString(" • ")
    }
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { LetterAvatar(app.label, 40.dp) },
            headlineContent = { Text(app.label, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Column {
                    Text("استخدام اليوم: ${formatMinutes(app.usageMin)}")
                    status?.let {
                        Text(
                            it,
                            color = if (rule?.blocked == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            trailingContent = {
                when {
                    rule?.blocked == true -> Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error)
                    (rule?.dailyLimitMin ?: 0) > 0 -> Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary)
                    rule?.windowStart?.isNotEmpty() == true -> Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }
}

@Composable
private fun AppRuleDialog(app: InstalledApp, initial: AppRule, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    var blocked by remember { mutableStateOf(initial.blocked) }
    var limit by remember { mutableStateOf(if (initial.dailyLimitMin > 0) initial.dailyLimitMin.toString() else "") }
    var ws by remember { mutableStateOf(initial.windowStart) }
    var we by remember { mutableStateOf(initial.windowEnd) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("حظر التطبيق نهائياً", Modifier.weight(1f))
                    Switch(blocked, { blocked = it })
                }
                OutlinedTextField(
                    limit, { limit = it.filter(Char::isDigit).take(4) },
                    enabled = !blocked, singleLine = true,
                    label = { Text("الحد اليومي بالدقائق (فارغ = بلا حد)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("مسموح فقط في هذه الفترة يومياً (اتركها فارغة = طوال اليوم):", style = MaterialTheme.typography.bodySmall)
                TimeRangeFields(ws, we, { ws = it }, { we = it }, enabled = !blocked)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                if (!TimeWindow.isValidRange(ws, we)) {
                    error = "اكتب الوقت بصيغة 24 ساعة مثل 16:00"
                } else onSave(
                    AppRule(
                        pkg = app.pkg, blocked = blocked,
                        dailyLimitMin = limit.toIntOrNull() ?: 0,
                        windowStart = ws.trim(), windowEnd = we.trim(),
                    )
                )
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun BedtimeDialog(p: Policy, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var s by remember { mutableStateOf(p.bedtimeStart.ifEmpty { "22:00" }) }
    var e by remember { mutableStateOf(p.bedtimeEnd.ifEmpty { "07:00" }) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("وقت النوم 🌙") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("خلال هذه الفترة يُقفل الجهاز تلقائياً كل يوم.")
                TimeRangeFields(s, e, { s = it }, { e = it })
                if (error) Text("صيغة الوقت غير صحيحة", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                if (TimeWindow.parse(s) == null || TimeWindow.parse(e) == null) error = true
                else onSave(s.trim(), e.trim())
            }) { Text("تفعيل") }
        },
        dismissButton = {
            Row {
                if (p.bedtimeStart.isNotEmpty()) TextButton({ onSave("", "") }) { Text("إيقاف") }
                TextButton(onDismiss) { Text("إلغاء") }
            }
        },
    )
}
