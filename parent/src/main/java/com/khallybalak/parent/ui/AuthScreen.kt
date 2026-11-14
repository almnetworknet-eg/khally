package com.khallybalak.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.khallybalak.parent.ParentRepo
import com.khallybalak.shared.ServerConfig
import com.khallybalak.shared.ServerField
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onDone: () -> Unit) {
    var server by rememberSaveable { mutableStateOf(ServerConfig.displayAddress) }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(register: Boolean) = scope.launch {
        if (email.isBlank() || pass.length < 6) {
            error = "اكتب بريد صحيح وكلمة مرور 6 أحرف على الأقل"; return@launch
        }
        busy = true; error = null
        runCatching {
            ServerConfig.verifyAndSave(server)
            if (register) ParentRepo.register(email.trim(), pass)
            else ParentRepo.login(email.trim(), pass)
        }.onSuccess { onDone() }
            .onFailure { error = it.message ?: "تعذر الاتصال بالسيرفر" }
        busy = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Shield, null, Modifier.size(84.dp), tint = MaterialTheme.colorScheme.primary)
            Text("خلي بالك من ابنك", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("لوحة تحكم ولي الأمر", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ServerField(server, { server = it }, enabled = !busy)
            OutlinedTextField(
                email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
            Button({ submit(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول") }
            OutlinedButton({ submit(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("إنشاء حساب جديد") }
            com.khallybalak.shared.BrandFooter()
        }
    }
}
