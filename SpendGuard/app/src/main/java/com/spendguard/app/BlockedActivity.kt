package com.spendguard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen page shown in place of a locked UPI app. */
class BlockedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Limits(this).breached()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                BlockedScreen(
                    s,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun BlockedScreen(s: PeriodStatus?, onOpenApp: () -> Unit, onClose: () -> Unit) {
    Surface(color = Color(0xFF7F1D1D), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Spending limit reached",
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            if (s != null) {
                Text(
                    "${s.period.label}: ${rupees(s.spent)} spent of ${rupees(s.limit)}",
                    color = Color(0xFFFECACA), fontSize = 17.sp, textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Payment apps stay locked until the next ${s?.period?.name?.lowercase() ?: "period"} starts.",
                color = Color(0xFFFECACA), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF7F1D1D))) {
                Text("OK")
            }
            TextButton(onClick = onOpenApp) { Text("Open SpendGuard", color = Color.White) }
        }
    }
}
