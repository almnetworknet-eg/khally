package com.khallybalak.child

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khallybalak.shared.KhallyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ChildState.start(this)
        setContent { KhallyTheme { ChildHome(resumeTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
private fun ChildHome(tick: Int) {
    val ctx = LocalContext.current
    var paired by remember { mutableStateOf(Pairing.isPaired(ctx)) }
    val device by ChildState.device.collectAsState()
    val loaded by ChildState.loaded.collectAsState()
    LaunchedEffect(loaded, device.id) {
        if (paired && loaded && device.id.isEmpty()) { Pairing.unpairLocal(ctx); paired = false }
    }
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Shield, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Text("خلي بالك", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("تطبيق حماية الأبناء", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!paired) PairCard { paired = true } else SetupCard(tick)
            com.khallybalak.shared.BrandFooter()
        }
    }
}

@Composable
private fun PairCard(onPaired: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(com.khallybalak.shared.ServerConfig.displayAddress) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ربط الجهاز بولي الأمر", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("اكتب نفس عنوان السيرفر اللي في تطبيق ولي الأمر، ومنه اضغط \"إضافة جهاز ابن\" واكتب الكود هنا.")
            com.khallybalak.shared.ServerField(server, { server = it }, enabled = !busy)
            OutlinedTextField(
                code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                label = { Text("كود الربط") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = code.length == 8 && server.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        runCatching { Pairing.pair(ctx, server, code) }
                            .onSuccess { onPaired() }
                            .onFailure { error = it.message ?: "حدث خطأ" }
                        busy = false
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("ربط") }
        }
    }
}

@Composable
private fun SetupCard(tick: Int) {
    val ctx = LocalContext.current
    val perms = remember(tick) { Perms.status(ctx) }
    val admin = remember(tick) { AdminController(ctx) }
    val allOk = perms.all { it.granted }

    if (allOk) Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(48.dp), tint = Color(0xFF2E7D32))
            Text("الجهاز محمي الآن ✅", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
            Text(
                if (admin.isOwner) "وضع الحماية القصوى (مالك الجهاز) مفعّل"
                else "للحماية القصوى (منع إعادة ضبط المصنع) راجع دليل التثبيت",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B5E20),
            )
        }
    } else Text(
        "فعّل الصلاحيات التالية بالترتيب لإكمال الحماية:",
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth(),
    )

    perms.forEach { p ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (p.granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                    tint = if (p.granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.title, fontWeight = FontWeight.Bold)
                    Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!p.granted) TextButton({
                    Perms.startGrace(ctx)
                    runCatching { ctx.startActivity(p.intent) }
                }) { Text("تفعيل") }
            }
        }
    }
}
