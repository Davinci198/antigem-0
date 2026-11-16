package com.example.gemini.ui.agents

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.hermes.NousLogin

@Composable
fun NousLoginDialog(
    login: NousLogin,
    busy: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    LaunchedEffect(login.loginId, login.url) {
        if (login.url.isNotBlank()) open(login.url)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Conectare la Nous", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (login.state) {
                    "pending" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Aprobă conectarea în pagina Nous.", fontSize = 13.sp)
                        }
                        Text("Codul tău", fontWeight = FontWeight.SemiBold)
                        Text(login.code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { open(login.url) }, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(Icons.Outlined.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Deschide din nou pagina")
                        }
                    }
                    "error" -> {
                        Text(login.error ?: "Autentificarea Nous nu a pornit.", fontSize = 13.sp)
                        Button(onClick = onRetry, enabled = !busy) { Text("Încearcă din nou") }
                    }
                    else -> Text("Stare autentificare: ${login.state}")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = if (login.state == "pending") onCancel else onDismiss) {
                Text(if (login.state == "pending") "Anulează" else "Închide")
            }
        },
    )
}
