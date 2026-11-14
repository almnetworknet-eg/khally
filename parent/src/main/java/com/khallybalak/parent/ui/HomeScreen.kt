package com.khallybalak.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khallybalak.parent.ParentRepo
import com.khallybalak.shared.ChildDevice
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onLogout: () -> Unit) {
    val children by remember { ParentRepo.childrenFlow() }.collectAsState(initial = null)
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("أبنائي", fontWeight = FontWeight.Bold)
                        Text(com.khallybalak.shared.BRAND, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = { IconButton(onLogout) { Icon(Icons.AutoMirrored.Filled.Logout, "خروج") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("إضافة جهاز ابن") },
            )
        },
    ) { pad ->
        val list = children
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.PhoneAndroid, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Text("لا توجد أجهزة مربوطة بعد", style = MaterialTheme.typography.titleMedium)
                Text(
                    "اضغط \"إضافة جهاز ابن\" للحصول على كود الربط، ثم اكتبه في تطبيق الابن.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { child -> ChildCard(child) { onOpen(child.id) } }
            }
        }
    }
    if (showAdd) AddChildDialog { showAdd = false }
}

@Composable
private fun ChildCard(c: ChildDevice, onClick: () -> Unit) {
    val currentLabel = c.apps.find { it.pkg == c.currentApp }?.label
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LetterAvatar(c.name, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(c.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OnlineDot(isOnline(c.lastSeen))
                    Text(lastSeenText(c.lastSeen), style = MaterialTheme.typography.bodySmall)
                }
                if (isOnline(c.lastSeen) && currentLabel != null)
                    Text("يستخدم الآن: $currentLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (c.battery >= 0) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.BatteryStd, null)
                Text("${c.battery}%", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (c.alert.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(" ${c.alert}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AddChildDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ربط جهاز ابن جديد") },
        text = {
            val c = code
            if (c == null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اكتب اسم الابن، ثم ولّد كود الربط.")
                OutlinedTextField(name, { name = it }, label = { Text("اسم الابن") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ثبّت تطبيق الابن على جهازه وافتحه ثم اكتب الكود:")
                Text(
                    c, fontSize = 34.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("الكود صالح لمدة 15 دقيقة", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (code == null) TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { ParentRepo.createPairCode(name.trim()) }
                            .onSuccess { code = it }
                            .onFailure { error = it.localizedMessage }
                        busy = false
                    }
                },
            ) { Text(if (busy) "جاري…" else "توليد الكود") }
            else TextButton(onDismiss) { Text("تم") }
        },
        dismissButton = { if (code == null) TextButton(onDismiss) { Text("إلغاء") } },
    )
}
