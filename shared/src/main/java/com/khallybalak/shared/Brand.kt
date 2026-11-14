package com.khallybalak.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

const val BRAND = "almeng.net"

/** توقيع almeng.net أسفل الشاشات */
@Composable
fun BrandFooter(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Text(
        text = "تطوير $BRAND",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clickable { runCatching { uri.openUri("https://$BRAND") } }
            .padding(vertical = 12.dp),
    )
}
