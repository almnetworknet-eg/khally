package com.khallybalak.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun isOnline(lastSeen: Long) = System.currentTimeMillis() - lastSeen < 3 * 60_000

fun lastSeenText(lastSeen: Long): String {
    if (lastSeen == 0L) return "لم يتصل بعد"
    if (isOnline(lastSeen)) return "متصل الآن"
    val min = (System.currentTimeMillis() - lastSeen) / 60_000
    return when {
        min < 60 -> "آخر ظهور منذ $min دقيقة"
        min < 60 * 24 -> "آخر ظهور منذ ${min / 60} ساعة"
        else -> "آخر ظهور منذ ${min / (60 * 24)} يوم"
    }
}

fun formatMinutes(min: Int): String =
    if (min < 60) "$min د" else "${min / 60} س ${min % 60} د"

@Composable
fun LetterAvatar(text: String, size: Dp = 44.dp, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.trim().take(1).ifEmpty { "؟" },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun OnlineDot(online: Boolean) {
    Box(
        Modifier.size(10.dp).clip(CircleShape)
            .background(if (online) Color(0xFF2E7D32) else Color(0xFF9E9E9E))
    )
}

/** حقلين "من" و"إلى" بصيغة 24 ساعة HH:mm */
@Composable
fun TimeRangeFields(start: String, end: String, onStart: (String) -> Unit, onEnd: (String) -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            start, { onStart(it.take(5)) }, label = { Text("من (16:00)") }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            end, { onEnd(it.take(5)) }, label = { Text("إلى (18:00)") }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}
