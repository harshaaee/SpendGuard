package com.spendguard.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

val AppColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    secondary = Color(0xFF2E7D32),
    error = Color(0xFFB91C1C)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = AppColors) { App() } }
    }
}

// ---------- helpers ----------

private fun hasSmsPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

private fun isBlockerEnabled(ctx: Context): Boolean {
    val expected = ComponentName(ctx, BlockerService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private data class AppInfo(val pkg: String, val name: String)

private fun installedApps(ctx: Context, all: Boolean): List<AppInfo> {
    val pm = ctx.packageManager
    val launchable = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).map { it.activityInfo.packageName }.toSet()
    return launchable
        .filter { it != ctx.packageName && (all || it in Limits.DEFAULT_UPI_APPS) }
        .map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            AppInfo(pkg, label)
        }
        .sortedBy { it.name.lowercase() }
}

/** Re-runs [block] every time the screen comes back to the foreground. */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

// ---------- screens ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }   // bump to refresh everything
    var tab by remember { mutableIntStateOf(0) }
    var smsOk by remember { mutableStateOf(hasSmsPermission(ctx)) }
    var blockerOk by remember { mutableStateOf(isBlockerEnabled(ctx)) }

    OnResume {
        smsOk = hasSmsPermission(ctx)
        blockerOk = isBlockerEnabled(ctx)
        tick++
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        smsOk = hasSmsPermission(ctx)
        if (smsOk) { SmsIngest.scanInbox(ctx); Notifier.checkAndAlert(ctx); tick++ }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SpendGuard", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                listOf("Home", "Limits", "Apps").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(listOf("₹", "🎯", "🔒")[i], fontSize = 20.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!smsOk || !blockerOk) {
                SetupCard(
                    smsOk, blockerOk,
                    onSms = {
                        val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                        permLauncher.launch(perms.toTypedArray())
                    },
                    onBlocker = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                )
            }
            when (tab) {
                0 -> HomeTab(tick) { tick++ }
                1 -> LimitsTab(tick) { tick++ }
                2 -> AppsTab()
            }
        }
    }
}

@Composable
private fun SetupCard(smsOk: Boolean, blockerOk: Boolean, onSms: () -> Unit, onBlocker: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E6))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setup", fontWeight = FontWeight.Bold)
            if (!smsOk) {
                Text("1. Allow SMS access so spends are read from bank / UPI messages.")
                Button(onClick = onSms) { Text("Allow SMS access") }
            }
            if (!blockerOk) {
                Text("2. Turn on \"SpendGuard UPI Blocker\" in Accessibility (Installed apps / Downloaded apps) so payment apps can be locked.")
                OutlinedButton(onClick = onBlocker) { Text("Open Accessibility settings") }
            }
        }
    }
}

@Composable
private fun HomeTab(tick: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val limits = remember { Limits(ctx) }
    val db = remember { Db.get(ctx) }
    val statuses = remember(tick) { limits.allStatus() }
    val txns = remember(tick) { db.recent() }
    val breached = statuses.firstOrNull { it.exceeded }
    val paused = remember(tick) { limits.pausedUntil > System.currentTimeMillis() }
    var showAdd by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (breached != null && limits.blockingEnabled) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2))) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        Text(
                            if (paused) "⏸ Lock paused for emergency" else "🔒 UPI apps locked — ${breached.period.label.lowercase()} limit crossed",
                            fontWeight = FontWeight.Bold, color = Color(0xFF991B1B)
                        )
                        if (!paused) TextButton(onClick = { showUnlock = true }) { Text("Emergency 10-min unlock") }
                    }
                }
            }
        }
        items(statuses) { PeriodCard(it) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }) { Text("+ Add expense") }
                OutlinedButton(onClick = {
                    if (hasSmsPermission(ctx)) { SmsIngest.scanInbox(ctx); Notifier.checkAndAlert(ctx); refresh() }
                }) { Text("Rescan SMS") }
            }
        }
        item { Text("Recent spends", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        if (txns.isEmpty()) item { Text("No spends yet. Debit SMS from your bank or UPI apps will show up here.", color = Color.Gray) }
        items(txns, key = { it.id }) { t ->
            TxnRow(t) { db.delete(t.id); refresh() }
        }
    }

    if (showAdd) AddExpenseDialog(onDismiss = { showAdd = false }) { amt, note ->
        db.addManual(amt, note); Notifier.checkAndAlert(ctx); BlockerService.instance?.recheckNow()
        showAdd = false; refresh()
    }
    if (showUnlock) UnlockDialog(onDismiss = { showUnlock = false }) {
        limits.pausedUntil = System.currentTimeMillis() + 10 * 60 * 1000
        showUnlock = false; refresh()
    }
}

@Composable
private fun PeriodCard(s: PeriodStatus) {
    val color = when {
        s.limit <= 0 -> MaterialTheme.colorScheme.primary
        s.exceeded -> Color(0xFFB91C1C)
        s.fraction >= 0.8f -> Color(0xFFD97706)
        else -> Color(0xFF15803D)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.period.label, fontWeight = FontWeight.SemiBold)
                Text(if (s.limit > 0) "Limit ${rupees(s.limit)}" else "No limit set", color = Color.Gray, fontSize = 13.sp)
            }
            Text(rupees(s.spent), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
            if (s.limit > 0) {
                LinearProgressIndicator(
                    progress = { s.fraction },
                    color = color,
                    modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp)
                )
                Text(
                    if (s.exceeded) "Over by ${rupees(s.spent - s.limit)}" else "${rupees(s.remaining)} left",
                    fontSize = 13.sp, color = color, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TxnRow(t: Txn, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF6F7F6), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.merchant, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                DateFormat.format("dd MMM, hh:mm a", t.time).toString() + if (t.source == "manual") " · manual" else " · SMS",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        Text(rupees(t.amount), fontWeight = FontWeight.Bold)
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.Gray) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove this entry?") },
        text = { Text("Use this if a message was misread or isn't really a spend.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun AddExpenseDialog(onDismiss: () -> Unit, onAdd: (Double, String) -> Unit) {
    var amt by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add cash / other expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amt, { amt = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount ₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("What for?") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { amt.toDoubleOrNull()?.takeIf { it > 0 }?.let { onAdd(it, note) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun UnlockDialog(onDismiss: () -> Unit, onUnlock: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val phrase = "I NEED THIS"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency unlock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Payment apps will open for 10 minutes. Type  $phrase  to continue.")
                OutlinedTextField(typed, { typed = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = typed.trim().uppercase() == phrase, onClick = onUnlock) { Text("Unlock 10 min") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LimitsTab(tick: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val limits = remember { Limits(ctx) }
    val fields = remember(tick) {
        Period.values().associateWith { p -> mutableStateOf(limits.limit(p).let { if (it > 0) it.toLong().toString() else "" }) }
    }
    var blocking by remember(tick) { mutableStateOf(limits.blockingEnabled) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Spending targets", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Leave blank for no limit. Week runs Monday to Sunday.", color = Color.Gray, fontSize = 13.sp)
        Period.values().forEach { p ->
            val st = fields.getValue(p)
            OutlinedTextField(
                value = st.value,
                onValueChange = { st.value = it.filter(Char::isDigit); saved = false },
                label = { Text(when (p) { Period.DAY -> "Daily limit ₹"; Period.WEEK -> "Weekly limit ₹"; Period.MONTH -> "Monthly limit ₹" }) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { blocking = !blocking; saved = false }) {
            Column(Modifier.weight(1f)) {
                Text("Lock UPI apps when a limit is crossed", fontWeight = FontWeight.Medium)
                Text("Choose which apps on the Apps tab.", fontSize = 12.sp, color = Color.Gray)
            }
            Switch(checked = blocking, onCheckedChange = { blocking = it; saved = false })
        }
        Button(onClick = {
            Period.values().forEach { p -> limits.setLimit(p, fields.getValue(p).value.toDoubleOrNull() ?: 0.0) }
            limits.blockingEnabled = blocking
            Notifier.checkAndAlert(ctx)
            BlockerService.instance?.recheckNow()
            saved = true; refresh()
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (saved) Text("Saved ✓", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AppsTab() {
    val ctx = LocalContext.current
    val limits = remember { Limits(ctx) }
    var showAll by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(limits.blockedApps) }
    val apps = remember(showAll) { installedApps(ctx, showAll) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apps to lock", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (showAll) "All installed apps" else "Payment apps found on this phone", fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "UPI only" else "Show all") }
        }
        if (apps.isEmpty()) Text("No known UPI apps found. Tap \"Show all\" to pick apps yourself.", Modifier.padding(16.dp), color = Color.Gray)
        LazyColumn {
            items(apps, key = { it.pkg }) { a ->
                val on = a.pkg in selected
                Row(
                    Modifier.fillMaxWidth().clickable {
                        selected = if (on) selected - a.pkg else selected + a.pkg
                        limits.blockedApps = selected
                    }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = on, onCheckedChange = {
                        selected = if (it) selected + a.pkg else selected - a.pkg
                        limits.blockedApps = selected
                    })
                    Column {
                        Text(a.name)
                        Text(a.pkg, fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}
